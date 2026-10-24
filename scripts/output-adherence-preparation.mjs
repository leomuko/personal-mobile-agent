import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { sha256, V3_FREEZE, V3_ROOT } from './v3-feasibility.mjs';

export const OA_ID = 'evidence-output-adherence-v1-draft-1';
export const OA_ROOT = 'planning/fixtures/output-adherence-v1';
export const OA_PROTOCOL = 'planning/P2_OUTPUT_ADHERENCE_PROTOCOL_V1.md';
export const OA_VALIDATOR = 'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt';
export const OA_VALIDATOR_SHA = '10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000';
export const OA_INPUTS = [OA_PROTOCOL, `${OA_ROOT}/controls.json`, `${OA_ROOT}/verifier-instructions.txt`,
  'scripts/output-adherence-preparation.mjs', 'scripts/tests/output-adherence-preparation.test.mjs',
  'shared/src/jvmTest/kotlin/dev/edgecompanion/core/inference/verification/OutputAdherenceFixtureTest.kt',
  OA_VALIDATOR, 'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ChatGeneration.kt',
  `${V3_ROOT}/protocol.json`, `${V3_ROOT}/freeze.json`, 'gradle/libs.versions.toml'];
const check = (value, message) => { if (!value) throw new Error(message); };
const json = value => JSON.stringify(value, null, 2) + '\n';
const bytes = value => Buffer.byteLength(value, 'utf8');
const b64 = value => Buffer.from(value, 'utf8').toString('base64');
const states = ['S', 'C', 'M', 'U'];
const keys = (value, names) => check(value && isDeepStrictEqual(Object.keys(value).sort(), [...names].sort()), 'Unexpected fixture keys');
const aggregate = values => values.includes('C') ? 'C' : values.every(s => s === 'S') ? 'S' : values.some(s => s !== 'U') ? 'M' : 'U';
function text(value, maximum) {
  check(typeof value === 'string' && value.trim().length > 0 && value.length <= maximum && bytes(value) <= maximum, 'Text size/type limit');
  for (let i = 0; i < value.length; i++) {
    const c = value.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff) {
      const next = value.charCodeAt(++i);
      check(next >= 0xdc00 && next <= 0xdfff, 'Malformed Unicode');
    } else check(c < 0xdc00 || c > 0xdfff, 'Malformed Unicode');
  }
  check(!['<|', '<start_of_', '<turn|>'].some(marker => value.includes(marker)), 'Reserved Gemma marker');
}

export function prepareControl(control, instructions) {
  keys(control, ['id', 'sourceFamily', 'question', 'details', 'passages', 'judgments', 'expected', 'witnesses']);
  check(/^oa0[1-6]$/.test(control.id), 'Unknown control'); text(control.sourceFamily, 80); text(control.question, 180);
  check(Array.isArray(control.details) && control.details.length >= 1 && control.details.length <= 2, 'Detail limit');
  check(Array.isArray(control.passages) && control.passages.length >= 1 && control.passages.length <= 3, 'Passage limit');
  const details = control.details.map(d => {
    keys(d, ['i', 'e', 'a', 'c', 'u']); for (const value of Object.values(d)) text(value, 80);
    return { i: d.i, e: d.e, a: d.a, c: d.c, u: d.u };
  });
  check(new Set(details.map(d => d.i)).size === details.length, 'Duplicate detail');
  const passages = control.passages.map(p => {
    keys(p, ['id', 'revision', 'text']); text(p.id, 40); text(p.revision, 20); text(p.text, 160);
    return { p: p.id, r: p.revision, b: 0, t: p.text };
  });
  check(new Set(passages.map(p => p.p)).size === passages.length, 'Duplicate passage');
  check(Array.isArray(control.judgments) && control.judgments.length === passages.length, 'Incomplete judgments');
  for (const [i, judgment] of control.judgments.entries()) {
    keys(judgment, ['p', 'details', 'reason']); text(judgment.reason, 300);
    check(judgment.p === passages[i].p && Array.isArray(judgment.details) && judgment.details.length === details.length &&
      judgment.details.every(s => states.includes(s)), 'Incomplete passage/detail judgments');
  }
  const expected = control.expected;
  keys(expected, ['state', 'details', 'reason']); text(expected.reason, 300);
  check(Array.isArray(expected.details) && expected.details.length === details.length, 'Incomplete expected shape');
  for (const [i, d] of expected.details.entries()) {
    keys(d, ['i', 'state', 'passageIds']);
    check(d.i === details[i].i && states.includes(d.state) && Array.isArray(d.passageIds) &&
      new Set(d.passageIds).size === d.passageIds.length && d.passageIds.every(id => passages.some(p => p.p === id)) &&
      isDeepStrictEqual(d.passageIds, [...d.passageIds].sort()) &&
      (d.state === 'C' ? d.passageIds.length === 2 : d.state === 'S' ? d.passageIds.length >= 1 && d.passageIds.length <= 2 : d.passageIds.length === 0),
    'Invalid expected control shape');
  }
  check(expected.state === aggregate(expected.details.map(d => d.state)), 'Inconsistent aggregate shape');
  check(Array.isArray(control.witnesses), 'Missing witnesses');
  const witnesses = control.witnesses.map(w => {
    keys(w, ['detail', 'p', 'q']); text(w.q, 96);
    const passage = passages.find(p => p.p === w.p), detail = expected.details.find(d => d.i === w.detail);
    check(passage && detail?.passageIds.includes(w.p), 'Unknown witness binding');
    const b = passage.t.indexOf(w.q);
    check(b >= 0 && b === passage.t.lastIndexOf(w.q), 'Missing/ambiguous witness');
    return { detail: w.detail, p: w.p, r: passage.r, b, e: b + w.q.length, q: w.q };
  });
  for (const d of expected.details) check(isDeepStrictEqual(
    witnesses.filter(w => w.detail === d.i).map(w => w.p).sort(), d.passageIds), 'Incomplete/duplicate witnesses');
  const payload = instructions + '\nDATA\n' + JSON.stringify({ q: control.question, d: details, p: passages }) + '\nEND DATA';
  text(payload, 1312);
  const inputBound = 128 + 96 + bytes(payload);
  check(inputBound + 512 <= 2048, 'Gemma input budget overflow');
  // Only whitelisted question-derived metadata and delivered text enter input. Gold remains review-only.
  return { id: control.id, input: { question: control.question, role: 'USER', payload,
    payloadSha256: sha256(payload), encoderInputSha256: sha256(control.question), inputBound,
    detailIds: details.map(d => d.i), passages: passages.map(p => ({ p: p.p, r: p.r, start: 0, end: p.t.length, original: p.t })) },
  review: { sourceFamily: control.sourceFamily, judgments: control.judgments, expected, witnesses } };
}

function validatorRows(controls) {
  const lines = ['# Synthetic host fixtures only; VALID is not a model result or semantic judgment.'];
  const output = (c, name, expectation, value, tokens = 40) =>
    lines.push(['O', c.id, name, expectation, tokens, b64(value)].join('\t'));
  for (const c of controls) {
    const input = c.input, expected = c.review.expected;
    lines.push(['Q', c.id, b64(input.question), b64(input.payload), input.detailIds.join(','), expected.state,
      expected.details.map(d => d.state).join(',')].join('\t'));
    for (const p of input.passages) lines.push(['P', c.id, p.p, p.r, p.start, p.end, b64(p.original)].join('\t'));
    const value = { o: expected.state, d: expected.details.map(d => ({ i: d.i, s: d.state,
      e: c.review.witnesses.filter(w => w.detail === d.i).map(({ detail, ...span }) => span) })) };
    const valid = JSON.stringify(value);
    output(c, 'synthetic-valid', 'VALID', valid);
    output(c, 'fenced', 'JSON', '```json\n' + valid + '\n```');
    output(c, 'trailing-object', 'JSON', valid + '{}');
    output(c, 'duplicate-key', 'SCHEMA', valid.replace('{"o":', '{"o":"U","o":'));
    output(c, 'zero-tokens', 'TOKEN_LIMIT', valid, 0);
    output(c, 'token-overflow', 'TOKEN_LIMIT', valid, 513);
    output(c, 'byte-overflow', 'SIZE', ' '.repeat(1537));
    const abstain = JSON.stringify({ o: 'U', d: input.detailIds.map(i => ({ i, s: 'U', e: [] })) });
    output(c, 'synthetic-abstain', 'VALID', abstain);
    if (value.d[0].e.length) {
      const edited = (name, reason, edit) => { const copy = structuredClone(value); edit(copy.d[0].e); output(c, name, reason, JSON.stringify(copy)); };
      edited('input-key-echo', 'SCHEMA', spans => { const s = spans[0]; spans[0] = { p: s.p, r: s.r, b: s.b, t: s.q }; });
      edited('missing-quotation', 'SCHEMA', spans => { delete spans[0].q; });
      edited('third-citation', 'SCHEMA', spans => { while (spans.length < 3) spans.push({ ...spans[0] }); });
      edited('unknown-reference', 'REFERENCE', spans => { spans[0].p = 'missing'; });
      edited('wrong-revision', 'REFERENCE', spans => { spans[0].r = '2'; });
      edited('invented-quotation', 'QUOTATION', spans => { spans[0].q += '!'; });
      edited('fractional-boundary', 'SPAN', spans => { spans[0].b += 0.5; });
      edited('outside-interval', 'SPAN', spans => { spans[0].e = 1000; });
    }
    if (c.id === 'oa06') {
      const original = input.passages[0].original;
      const whole = structuredClone(value); Object.assign(whole.d[0].e[0], { b: 0, e: original.length, q: original });
      output(c, 'unicode-exact', 'VALID', JSON.stringify(whole));
      whole.d[0].e[0].q = original.normalize('NFC');
      output(c, 'unicode-normalized', 'QUOTATION', JSON.stringify(whole));
      Object.assign(whole.d[0].e[0], { b: 0, e: 1, q: '\ud83e' });
      output(c, 'split-surrogate', 'UNICODE', JSON.stringify(whole));
    }
  }
  return lines.join('\n') + '\n';
}

export function buildPreparation(base = '.') {
  const read = path => readFileSync(join(base, path));
  check(sha256(read(OA_VALIDATOR)) === OA_VALIDATOR_SHA, 'Strict validator changed');
  const data = JSON.parse(read(`${OA_ROOT}/controls.json`));
  keys(data, ['schema', 'protocolId', 'scope', 'controls']);
  check(data.schema === 1 && data.protocolId === OA_ID && data.scope === 'synthetic-calibration-controls-awaiting-independent-review' &&
    Array.isArray(data.controls) && isDeepStrictEqual(data.controls.map(c => c.id), ['oa01', 'oa02', 'oa03', 'oa04', 'oa05', 'oa06']), 'Wrong control order/scope');
  const instructions = read(`${OA_ROOT}/verifier-instructions.txt`).toString('utf8').replace(/\n$/, '');
  text(instructions, 1000); check(!instructions.includes('\n'), 'Instruction line changed');
  const protocol = read(OA_PROTOCOL).toString('utf8');
  check(protocol.includes('```text\n' + instructions + '\n```'), 'Protocol/instruction mismatch');
  const controls = data.controls.map(c => prepareControl(c, instructions));
  check(isDeepStrictEqual(controls.map(c => c.review.expected.details.map(d => d.state)),
    [['S'], ['C'], ['M'], ['U'], ['S', 'M'], ['S']]), 'Control specifications changed');
  check(new Set(controls.map(c => c.review.sourceFamily)).size === 6 &&
    new Set(controls.flatMap(c => c.input.passages.map(p => p.p))).size === controls.reduce((n, c) => n + c.input.passages.length, 0),
  'Cross-control family/passage collision');
  const priorFreeze = read(`${V3_ROOT}/freeze.json`);
  check(sha256(priorFreeze) === V3_FREEZE, 'Historical V3 freeze changed');
  const priorBytes = read(`${V3_ROOT}/protocol.json`);
  check(sha256(priorBytes) === JSON.parse(priorFreeze).files.find(f => f.path === `${V3_ROOT}/protocol.json`)?.sha256,
    'Historical V3 protocol changed');
  const prior = JSON.parse(priorBytes);
  const prepared = { schema: 1, protocolId: OA_ID, checkpoint: '57ebf3653578c48cc5670b9c95f8f11b7594b2aa',
    status: 'awaiting-independent-judgments', hostPreparationOnly: true, nativeCollectionAuthorized: false,
    semanticEnablement: false, devicePlan: false, proposedDeadlineMs: 30000, latencyApproved: false,
    resourceProfileApproved: false, encoder: prior.encoder, verifier: { ...prior.verifier,
      runtime: 'com.google.ai.edge.litertlm:litertlm-android:0.17.1', historicalRuntimeLabel: prior.verifier.runtime },
    strictValidatorSha256: OA_VALIDATOR_SHA, controls };
  return { prepared, tsv: validatorRows(controls) };
}

/** Review lock only: never an execution freeze, judgment attestation or device plan. */
export function writePreparation(base = '.') {
  const { prepared, tsv } = buildPreparation(base);
  const generated = { [`${OA_ROOT}/prepared.json`]: json(prepared), [`${OA_ROOT}/validator-cases.tsv`]: tsv,
    [`${OA_ROOT}/validator-cases.sha256`]: sha256(tsv) + '\n' };
  const lockPath = `${OA_ROOT}/preparation-lock.json`;
  for (const path of [...Object.keys(generated), lockPath]) check(!existsSync(join(base, path)), 'Refusing to replace preparation artifacts');
  const files = Object.fromEntries(OA_INPUTS.map(path => [path, sha256(readFileSync(join(base, path)))]));
  for (const [path, content] of Object.entries(generated)) files[path] = sha256(content);
  const lock = { schema: 1, protocolId: OA_ID, scope: 'host-preparation-not-execution',
    judgmentApproval: false, executionReady: false, semanticEnablement: false, files };
  mkdirSync(join(base, OA_ROOT), { recursive: true });
  for (const [path, content] of Object.entries({ ...generated, [lockPath]: json(lock) }))
    writeFileSync(join(base, path), content, { flag: 'wx' });
  return lock;
}

export function verifyPreparation(base = '.') {
  const lock = JSON.parse(readFileSync(join(base, OA_ROOT, 'preparation-lock.json')));
  check(lock.schema === 1 && lock.protocolId === OA_ID && lock.scope === 'host-preparation-not-execution' &&
    lock.judgmentApproval === false && lock.executionReady === false && lock.semanticEnablement === false &&
    isDeepStrictEqual(Object.keys(lock.files).sort(), [...OA_INPUTS, `${OA_ROOT}/prepared.json`,
      `${OA_ROOT}/validator-cases.tsv`, `${OA_ROOT}/validator-cases.sha256`].sort()), 'Invalid preparation lock');
  for (const [path, hash] of Object.entries(lock.files)) check(sha256(readFileSync(join(base, path))) === hash, `Stale preparation: ${path}`);
  const { prepared, tsv } = buildPreparation(base);
  check(readFileSync(join(base, OA_ROOT, 'prepared.json'), 'utf8') === json(prepared) &&
    readFileSync(join(base, OA_ROOT, 'validator-cases.tsv'), 'utf8') === tsv &&
    readFileSync(join(base, OA_ROOT, 'validator-cases.sha256'), 'utf8') === sha256(tsv) + '\n', 'Prepared content does not reproduce');
  return { status: 'prepared-awaiting-independent-review', controls: 6,
    payloadBytes: prepared.controls.map(c => bytes(c.input.payload)), executionReady: false, semanticEnablement: false };
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    check(process.argv.length === 3 && ['write', 'check'].includes(process.argv[2]), 'Usage: node scripts/output-adherence-preparation.mjs write|check (HOST ONLY)');
    if (process.argv[2] === 'write') writePreparation();
    console.log(JSON.stringify(verifyPreparation(), null, 2));
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
