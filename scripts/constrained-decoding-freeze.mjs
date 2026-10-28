import { readFileSync, mkdirSync, copyFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { CD_ID, CD_ROOT, CD_LOCK, CD_POLICY, CD_PROOF, cdCheck as check, loadConstrainedPlan } from './constrained-decoding-gates.mjs';
import { v4SourceSnapshot, V4_APKS } from './v4-execution-freeze.mjs';
import { v4Profile } from './v4-feasibility.mjs';
import { sha256 } from './v3-feasibility.mjs';

const read = path => JSON.parse(readFileSync(path));
const hash = path => sha256(readFileSync(path));
const reviewScope = 'constrained-decoding-v2-integration-and-host-qa';
export function constrainedSnapshot(base = '.') {
  loadConstrainedPlan(base);
  const lock = read(join(base, CD_ROOT, 'preparation-lock.json'));
  const proof = read(join(base, CD_PROOF));
  const paths = new Set([...Object.keys(v4SourceSnapshot(base)), ...Object.keys(lock.files), ...Object.keys(proof.files), CD_PROOF,
    `${CD_ROOT}/preparation-lock.json`, `${CD_ROOT}/integration-authorization.json`, 'docs/P2_CONSTRAINED_DECODING_PROTOCOL_V2_REVIEW.md']);
  return Object.fromEntries([...paths].sort().map(path => [path, hash(join(base, path))]));
}

/** An implementation-review PASS permits freeze preparation, never device execution. */
export function createConstrainedFreeze(output, reviewPath, base = '.') {
  const approved = loadConstrainedPlan(base), sources = constrainedSnapshot(base), review = read(reviewPath);
  const snapshotSha256 = sha256(JSON.stringify(sources)), profile = v4Profile(base);
  check(review.verdict === 'PASS' && review.scope === reviewScope && review.snapshotSha256 === snapshotSha256 &&
    review.deviceExecutionAuthorized === false, 'Missing/stale constrained implementation review');
  check(profile.id === CD_POLICY && !profile.enforceAvailableMemoryFloor && profile.contextTokens === 2048 && profile.outputTokens === 512,
    'Wrong constrained resource profile');
  const apks = Object.fromEntries(V4_APKS.map(path => { const bytes = readFileSync(join(base, path));
    check(bytes.length > 100000, 'Missing QA APK'); return [path, sha256(bytes)]; }));
  const frozen = { schema: 2, protocolId: CD_ID, status: 'awaiting-independent-freeze-review-and-separate-device-authorization',
    deviceExecutionAuthorized: false, semanticEnablement: false, requests: 9, deadlineMs: 30000, cleanupWithinDeadline: true,
    negativeDecodeTokenCap: 1, negativeCase: 'cd07', reservedOutputTokens: 512, createdAtMs: Date.now(),
    ...approved, sources, snapshotSha256, review, reviewSha256: hash(reviewPath), apks,
    profile, profileSha256: sha256(JSON.stringify(profile)), build: { variant: 'secureQa', testBuildType: 'secureQa', androidPressureMemoryTest: true } };
  mkdirSync(output, { recursive: false });
  for (const path of [...Object.keys(sources), ...V4_APKS]) {
    const target = join(output, 'snapshot', path); mkdirSync(dirname(target), { recursive: true }); copyFileSync(join(base, path), target);
    check(hash(target) === (sources[path] || apks[path]), `Frozen copy changed: ${path}`);
  }
  check(isDeepStrictEqual(constrainedSnapshot(base), sources) && V4_APKS.every(path => hash(join(base, path)) === apks[path]), 'Inputs changed while freezing');
  writeFileSync(join(output, 'execution-freeze.json'), JSON.stringify(frozen, null, 2) + '\n', { flag: 'wx' });
  return frozen;
}

/** No caller may touch ADB, install APKs or reserve an attempt before this succeeds. */
export function admitConstrainedExecution(freezePath, authorizationPath, serial, base = '.') {
  check(typeof freezePath === 'string' && typeof authorizationPath === 'string', 'Constrained decoding requires a reviewed freeze and separate S24 authorization');
  const frozen = read(freezePath), authorization = read(authorizationPath), approved = loadConstrainedPlan(base);
  const sources = constrainedSnapshot(base), freezeSha256 = hash(freezePath);
  check(frozen.schema === 2 && frozen.protocolId === CD_ID && frozen.requests === 9 && frozen.deadlineMs === 30000 &&
    frozen.cleanupWithinDeadline === true && frozen.deviceExecutionAuthorized === false && frozen.semanticEnablement === false &&
    frozen.negativeCase === 'cd07' && frozen.negativeDecodeTokenCap === 1 && frozen.reservedOutputTokens === 512 &&
    frozen.review?.verdict === 'PASS' && frozen.review.scope === reviewScope && frozen.review.deviceExecutionAuthorized === false &&
    frozen.snapshotSha256 === sha256(JSON.stringify(sources)) && frozen.review.snapshotSha256 === frozen.snapshotSha256 &&
    isDeepStrictEqual(frozen.sources, sources) && isDeepStrictEqual(frozen.plan, approved.plan) && isDeepStrictEqual(frozen.oracles, approved.oracles) &&
    isDeepStrictEqual(frozen.sentinel, approved.sentinel) &&
    frozen.prerequisiteSha256 === approved.prerequisiteSha256 && frozen.authorizationSha256 === approved.authorizationSha256 &&
    frozen.planSha256 === approved.planSha256 && isDeepStrictEqual(frozen.profile, v4Profile(base)) && frozen.profile.id === CD_POLICY &&
    frozen.profileSha256 === sha256(JSON.stringify(frozen.profile)) &&
    isDeepStrictEqual(frozen.build, { variant: 'secureQa', testBuildType: 'secureQa', androidPressureMemoryTest: true }) &&
    isDeepStrictEqual(frozen.apks, Object.fromEntries(V4_APKS.map(path => [path, hash(join(base, path))]))), 'Stale/invalid constrained execution freeze');
  // The independently authored freeze attestation is separate from both implementation and human permission.
  const correspondence = read(join(dirname(freezePath), 'independent-freeze-review.json'));
  check(correspondence.verdict === 'PASS' && correspondence.scope === 'constrained-decoding-v2-execution-freeze' &&
    correspondence.freezeSha256 === freezeSha256 && correspondence.deviceExecutionAuthorized === false, 'Missing independent freeze correspondence review');
  check(authorization.schema === 2 && authorization.protocolId === CD_ID && authorization.approved === true &&
    authorization.scope === 'single-supervised-constrained-decoding-nine-request' && authorization.freezeSha256 === freezeSha256 &&
    authorization.profileSha256 === frozen.profileSha256 && authorization.serial === serial && authorization.device === 'SM-S928B' &&
    typeof serial === 'string' && /^[A-Za-z0-9._:-]+$/.test(serial) && authorization.requests === 9 && authorization.deadlineMs === 30000 &&
    authorization.negativeCase === 'cd07' && authorization.negativeDecodeTokenCap === 1 && authorization.reservedOutputTokens === 512,
    'Missing/mismatched separate constrained S24 authorization');
  return { frozen, freezeSha256 };
}

export function reserveConstrainedAttempt(admission, runId, folder, base = '.') {
  check(/^[a-f0-9]{64}$/.test(admission.freezeSha256), 'Invalid constrained ledger identity');
  const root = join(base, 'benchmarks/results/constrained-decoding-ledger'); mkdirSync(root, { recursive: true });
  const path = join(root, `${admission.freezeSha256}.json`);
  writeFileSync(path, JSON.stringify({ runId, folder: resolve(folder), freezeSha256: admission.freezeSha256, startedAtMs: Date.now() }, null, 2) + '\n', { flag: 'wx' });
  return path;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    check(process.argv.length === 4, 'Usage: node scripts/constrained-decoding-freeze.mjs NEW_FOLDER INDEPENDENT_CODE_REVIEW_JSON');
    createConstrainedFreeze(process.argv[2], process.argv[3]);
    console.log('Constrained freeze prepared; independent correspondence review and separate S24 authorization still required.');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
