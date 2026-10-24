import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, copyFileSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { OA_ID, OA_ROOT, OA_PROTOCOL, OA_INPUTS, OA_VALIDATOR, OA_VALIDATOR_SHA,
  prepareControl, buildPreparation, writePreparation, verifyPreparation } from '../output-adherence-preparation.mjs';
import { sha256 } from '../v3-feasibility.mjs';

const read = path => readFileSync(path, 'utf8');
const data = JSON.parse(read(`${OA_ROOT}/controls.json`));
const instructions = read(`${OA_ROOT}/verifier-instructions.txt`).replace(/\n$/, '');
function sandbox(run) {
  const root = mkdtempSync(join(tmpdir(), 'oa-host-only-'));
  try {
    for (const path of OA_INPUTS) {
      mkdirSync(dirname(join(root, path)), { recursive: true }); copyFileSync(path, join(root, path));
    }
    return run(root);
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('six-control preparation is deterministic, review-only and never an execution approval', () => {
  const a = buildPreparation(), b = buildPreparation();
  assert.deepEqual(a, b);
  assert.equal(a.prepared.protocolId, OA_ID); assert.equal(a.prepared.status, 'awaiting-independent-judgments');
  for (const key of ['nativeCollectionAuthorized', 'semanticEnablement', 'devicePlan', 'latencyApproved', 'resourceProfileApproved'])
    assert.equal(a.prepared[key], false);
  assert.deepEqual(a.prepared.controls.map(c => c.id), ['oa01', 'oa02', 'oa03', 'oa04', 'oa05', 'oa06']);
  assert.deepEqual(a.prepared.controls.map(c => c.review.expected.state), ['S', 'C', 'M', 'U', 'M', 'S']);
  assert.equal(a.prepared.verifier.contextTokens, 2048); assert.equal(a.prepared.verifier.reservedOutputTokens, 512);
  assert.equal(a.prepared.encoder.tokenLimit, 192); assert.equal(a.prepared.proposedDeadlineMs, 30000);
  assert.equal(sha256(readFileSync(OA_VALIDATOR)), OA_VALIDATOR_SHA);
});

test('exact input packs contain only whitelisted question metadata and delivered evidence, not gold', () => {
  for (const c of buildPreparation().prepared.controls) {
    assert.deepEqual(Object.keys(c.input), ['question', 'role', 'payload', 'payloadSha256', 'encoderInputSha256', 'inputBound', 'detailIds', 'passages']);
    const body = JSON.parse(c.input.payload.slice(instructions.length + '\nDATA\n'.length, -'\nEND DATA'.length));
    assert.deepEqual(Object.keys(body), ['q', 'd', 'p']);
    assert.equal(body.q, c.input.question); assert.deepEqual(body.d.map(d => d.i), c.input.detailIds);
    for (const d of body.d) assert.deepEqual(Object.keys(d), ['i', 'e', 'a', 'c', 'u']);
    for (const [i, p] of body.p.entries()) {
      assert.deepEqual(Object.keys(p), ['p', 'r', 'b', 't']);
      assert.equal(p.t, c.input.passages[i].original); assert.equal(p.b, 0);
    }
    assert.equal(c.input.payloadSha256, sha256(c.input.payload));
    assert.equal(c.input.encoderInputSha256, sha256(body.q));
    assert.equal(c.input.inputBound, 128 + 96 + Buffer.byteLength(c.input.payload));
    assert.ok(c.input.inputBound + 512 <= 2048);
  }
});

test('UTF-16 witnesses retain exact original text and required conflict records are never dropped', () => {
  const controls = buildPreparation().prepared.controls;
  assert.equal(controls[1].input.passages.length, 2); assert.equal(controls[1].review.witnesses.length, 2);
  for (const c of controls) for (const w of c.review.witnesses) {
    const p = c.input.passages.find(p => p.p === w.p);
    assert.equal(p.original.slice(w.b, w.e), w.q); assert.ok(Buffer.byteLength(w.q) <= 96);
  }
  const unicode = controls[5], w = unicode.review.witnesses[0];
  assert.notEqual(w.b, [...unicode.input.passages[0].original.slice(0, w.b)].length);
  assert.match(unicode.input.passages[0].original.slice(0, w.b), /Cafe\u0301/);
});

test('overflow, reserved markers and malformed Unicode reject before packing without truncation', () => {
  for (const question of ['x'.repeat(181), '<|system|>', '<start_of_turn>', '<turn|>', '\ud800', '\udc00', '   ']) {
    const c = structuredClone(data.controls[0]); c.question = question;
    assert.throws(() => prepareControl(c, instructions));
  }
  const c = structuredClone(data.controls[1]); c.passages[1].text += 'x'.repeat(161);
  assert.throws(() => prepareControl(c, instructions), /Text size/);
  const small = data.controls[0], overhead = Buffer.byteLength(prepareControl(small, '').input.payload);
  assert.equal(Buffer.byteLength(prepareControl(small, 'x'.repeat(1312 - overhead)).input.payload), 1312);
  assert.throws(() => prepareControl(small, 'x'.repeat(1313 - overhead)), /Text size/);
});

test('incomplete, duplicate, reordered or inconsistent gold bindings cannot be prepared', () => {
  for (const edit of [c => c.judgments.pop(), c => c.judgments[0].details.pop(),
    c => c.witnesses.pop(), c => c.expected.details[0].passageIds.push('unknown'),
    c => { c.expected.state = 'U'; }, c => { c.witnesses[0].q = 'not present'; },
    c => { c.expected.details[0].i = 'b'; }, c => { c.passages.push({ ...c.passages[0] }); }]) {
    const c = structuredClone(data.controls[0]); edit(c); assert.throws(() => prepareControl(c, instructions));
  }
});

test('preparation lock reproduces exact artifacts and refuses replacement', () => sandbox(root => {
  writePreparation(root); const verified = verifyPreparation(root);
  assert.equal(verified.executionReady, false); assert.equal(verified.controls, 6);
  assert.throws(() => writePreparation(root), /Refusing to replace/);
}));

test('tampered inputs, derived packs, bridge rows or validator invalidate the review lock', () => sandbox(root => {
  writePreparation(root);
  for (const path of [OA_PROTOCOL, `${OA_ROOT}/controls.json`, `${OA_ROOT}/prepared.json`,
    `${OA_ROOT}/validator-cases.tsv`, `${OA_ROOT}/validator-cases.sha256`, OA_VALIDATOR]) {
    const file = join(root, path), original = readFileSync(file);
    writeFileSync(file, Buffer.concat([original, Buffer.from('\n')]));
    assert.throws(() => verifyPreparation(root), /Stale preparation/); writeFileSync(file, original);
  }
}));

test('a self-certified review flag or reduced lock file list is never admitted', () => sandbox(root => {
  writePreparation(root); const path = join(root, OA_ROOT, 'preparation-lock.json');
  const lock = JSON.parse(readFileSync(path));
  for (const edit of [l => { l.judgmentApproval = true; }, l => { l.executionReady = true; },
    l => { delete l.files[OA_VALIDATOR]; }]) {
    const copy = structuredClone(lock); edit(copy); writeFileSync(path, JSON.stringify(copy));
    assert.throws(() => verifyPreparation(root), /Invalid preparation lock/);
  }
}));

test('partial output artifacts block publication before any new artifact is written', () => sandbox(root => {
  writeFileSync(join(root, OA_ROOT, 'validator-cases.tsv'), 'existing');
  assert.throws(() => writePreparation(root), /Refusing to replace/);
  assert.equal(existsSync(join(root, OA_ROOT, 'prepared.json')), false);
  assert.equal(existsSync(join(root, OA_ROOT, 'preparation-lock.json')), false);
}));

test('preparation CLI refuses execution options without accessing fake ADB', () => {
  const root = mkdtempSync(join(tmpdir(), 'oa-no-adb-'));
  try {
    const marker = join(root, 'accessed'), fake = join(root, 'fake-adb');
    writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`, { mode: 0o755 });
    const result = spawnSync(process.execPath, ['scripts/output-adherence-preparation.mjs', '--execute'],
      { env: { ...process.env, ADB: fake }, encoding: 'utf8' });
    assert.notEqual(result.status, 0); assert.match(result.stderr, /HOST ONLY/); assert.equal(existsSync(marker), false);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
