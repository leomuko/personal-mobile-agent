import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { OA_ID } from '../output-adherence-preparation.mjs';
import { adherenceSnapshot, createAdherenceFreeze, admitAdherenceExecution, reserveAdherenceAttempt } from '../output-adherence-freeze.mjs';
import { V4_APKS, prepareV4Apks } from '../v4-execution-freeze.mjs';
import { sha256 } from '../v3-feasibility.mjs';

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'oa-freeze-host-only-'));
  try {
    for (const path of Object.keys(adherenceSnapshot())) {
      mkdirSync(dirname(join(root, path)), { recursive: true }); copyFileSync(path, join(root, path));
    }
    for (const [i, path] of V4_APKS.entries()) {
      mkdirSync(dirname(join(root, path)), { recursive: true }); writeFileSync(join(root, path), Buffer.alloc(100001, i + 1));
    }
    const review = join(root, 'synthetic-code-review.json');
    writeFileSync(review, JSON.stringify({ verdict: 'PASS', scope: 'output-adherence-native-integration-and-host-qa',
      snapshotSha256: sha256(JSON.stringify(adherenceSnapshot(root))), deviceExecutionAuthorized: false }));
    const output = join(root, 'freeze'), frozen = createAdherenceFreeze(output, review, root);
    const freeze = join(output, 'execution-freeze.json'), auth = join(root, 'synthetic-device-authorization.json');
    const authorization = { schema: 1, protocolId: OA_ID, approved: true, scope: 'single-supervised-output-adherence-six-request',
      freezeSha256: sha256(readFileSync(freeze)), profileSha256: frozen.profileSha256, serial: 'synthetic', device: 'SM-S928B',
      requests: 6, deadlineMs: 30000 };
    writeFileSync(auth, JSON.stringify(authorization));
    return run({ root, output, review, frozen, freeze, auth, authorization });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('a reviewed six-control freeze is not a device permission and reserves one immutable attempt', () => fixture(f => {
  assert.equal(f.frozen.deviceExecutionAuthorized, false); assert.equal(f.frozen.requests, 6);
  const admission = admitAdherenceExecution(f.freeze, f.auth, 'synthetic', f.root);
  assert.equal(admission.frozen.plan.entries.length, 6); assert.equal(admission.frozen.oracles.controls.length, 6);
  const ledger = reserveAdherenceAttempt(admission, 'first', f.output, f.root), original = readFileSync(ledger);
  assert.throws(() => reserveAdherenceAttempt(admission, 'second', f.output, f.root), /EEXIST/);
  assert.deepEqual(readFileSync(ledger), original);
}));

test('stale or non-PASS implementation review cannot freeze; changed executable sources cannot be admitted', () => fixture(f => {
  const review = JSON.parse(readFileSync(f.review)); review.verdict = 'FAIL'; writeFileSync(f.review, JSON.stringify(review));
  assert.throws(() => createAdherenceFreeze(join(f.root, 'second'), f.review, f.root), /implementation review/);
  writeFileSync(join(f.root, 'scripts/output-adherence-evidence.mjs'), '// changed');
  assert.throws(() => admitAdherenceExecution(f.freeze, f.auth, 'synthetic', f.root), /stale output-adherence/);
}));

test('old V4 permission, a different device, expanded request count or timing cannot authorize OA', () => fixture(f => {
  for (const edit of [a => { a.scope = 'single-supervised-v4-eight-request'; }, a => { a.serial = 'other'; },
    a => { a.requests = 8; }, a => { a.deadlineMs = 60000; }, a => { a.profileSha256 = 'wrong'; }]) {
    const auth = structuredClone(f.authorization); edit(auth); writeFileSync(f.auth, JSON.stringify(auth));
    assert.throws(() => admitAdherenceExecution(f.freeze, f.auth, 'synthetic', f.root), /S24 authorization/);
  }
}));

test('private APK staging ignores post-admission builds and rejects damaged frozen copies', () => fixture(f => {
  const admission = admitAdherenceExecution(f.freeze, f.auth, 'synthetic', f.root);
  for (const path of V4_APKS) writeFileSync(join(f.root, path), 'concurrent build');
  const apks = prepareV4Apks(admission, f.freeze);
  try { assert.deepEqual(apks.files.map(p => sha256(readFileSync(p.path))), V4_APKS.map(p => f.frozen.apks[p])); }
  finally { apks.close(); }
  writeFileSync(join(f.output, 'snapshot', V4_APKS[1]), 'corrupt');
  assert.throws(() => prepareV4Apks(admission, f.freeze), /Frozen APK hash mismatch/);
}));

test('failed counting proof blocks execution admission before it can read a device', () => fixture(f => {
  const path = join(f.root, 'build/encoder-counting/counting-proof.json');
  const proof = JSON.parse(readFileSync(path)); proof.passed = false; writeFileSync(path, JSON.stringify(proof));
  assert.throws(() => admitAdherenceExecution(f.freeze, f.auth, 'synthetic', f.root), /counting proof/);
}));

test('the complete OA wrapper rejects absent authorization or corrupt bundled APKs before any fake ADB call', () => fixture(f => {
  const marker = join(f.root, 'adb-accessed'), fake = join(f.root, 'fake-adb');
  writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`, { mode: 0o755 });
  const args = ['scripts/run-p2-embedding-gate.mjs', '--output-adherence', '--android-pressure'];
  for (const flags of [[], ['--adherence-freeze', f.freeze]]) {
    const r = spawnSync(process.execPath, [...args, ...flags], { cwd: f.root, encoding: 'utf8',
      env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'synthetic', P2_ENCODER_MODEL: '' } });
    assert.notEqual(r.status, 0); assert.match(r.stderr, /authorization/); assert.equal(existsSync(marker), false);
  }
  writeFileSync(join(f.output, 'snapshot', V4_APKS[1]), 'corrupt');
  const r = spawnSync(process.execPath, [...args, '--adherence-freeze', f.freeze, '--adherence-authorization', f.auth],
    { cwd: f.root, encoding: 'utf8', env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'synthetic', P2_ENCODER_MODEL: '' } });
  assert.notEqual(r.status, 0); assert.match(r.stderr, /Frozen APK hash mismatch/); assert.equal(existsSync(marker), false);
  assert.equal(existsSync(join(f.root, 'benchmarks/results/output-adherence-ledger')), false);
}));
