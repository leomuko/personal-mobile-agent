import { readFileSync, mkdirSync, copyFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { OA_ID, OA_ROOT } from './output-adherence-preparation.mjs';
import { loadApprovedAdherence, OA_POLICY, requireAdherence as requireThat } from './output-adherence-gates.mjs';
import { v4SourceSnapshot, V4_APKS } from './v4-execution-freeze.mjs';
import { v4Profile } from './v4-feasibility.mjs';
import { sha256, loadFeasibilityPlan } from './v3-feasibility.mjs';

const hash = path => sha256(readFileSync(path));
const read = path => JSON.parse(readFileSync(path));
const reviewScope = 'output-adherence-native-integration-and-host-qa';
export function adherenceSnapshot(base = '.') {
  loadApprovedAdherence(base);
  // Reuse the proven prerequisite verifier, not its old prompts or execution authorization.
  loadFeasibilityPlan(base);
  const lock = read(join(base, OA_ROOT, 'preparation-lock.json'));
  const files = new Set([...Object.keys(v4SourceSnapshot(base)), ...Object.keys(lock.files),
    `${OA_ROOT}/preparation-lock.json`, `${OA_ROOT}/integration-authorization.json`, `${OA_ROOT}/independent-review.json`]);
  return Object.fromEntries([...files].sort().map(path => [path, hash(join(base, path))]));
}

/** Preparation only; a code-review PASS cannot authorize device execution. */
export function createAdherenceFreeze(output, reviewPath, base = '.') {
  const approved = loadApprovedAdherence(base), sources = adherenceSnapshot(base);
  const snapshotSha256 = sha256(JSON.stringify(sources)), review = read(reviewPath);
  requireThat(review.verdict === 'PASS' && review.scope === reviewScope && review.snapshotSha256 === snapshotSha256 &&
    review.deviceExecutionAuthorized === false, 'Missing/stale independent output-adherence implementation review');
  const profile = v4Profile(base);
  requireThat(profile.id === OA_POLICY && profile.enforceAvailableMemoryFloor === false &&
    profile.contextTokens === 2048 && profile.outputTokens === 512, 'Wrong output-adherence resource profile');
  const apks = Object.fromEntries(V4_APKS.map(path => {
    const bytes = readFileSync(join(base, path)); requireThat(bytes.length > 100000, 'Missing assembled QA APK');
    return [path, sha256(bytes)];
  }));
  const frozen = { schema: 1, protocolId: OA_ID, status: 'awaiting-separate-device-authorization',
    deviceExecutionAuthorized: false, semanticEnablement: false, largeCampaignAllowed: false,
    requests: 6, deadlineMs: 30000, cleanupWithinDeadline: true, createdAtMs: Date.now(),
    sources, snapshotSha256, review, reviewSha256: hash(reviewPath), apks, profile,
    profileSha256: sha256(JSON.stringify(profile)), build: { variant: 'secureQa', testBuildType: 'secureQa', androidPressureMemoryTest: true },
    ...approved, acceptance: ['six-in-order', 'stop-at-first-block', 'strict-unmodified-validator', 'control-shape-not-semantic-quality',
      'serial-allocation', 'current-counting', 'whole-clock-under-30000ms-including-cleanup', 'healthy-telemetry', 'closed-supervision'] };
  mkdirSync(output, { recursive: false });
  for (const path of [...Object.keys(sources), ...V4_APKS]) {
    const target = join(output, 'snapshot', path); mkdirSync(dirname(target), { recursive: true }); copyFileSync(join(base, path), target);
  }
  requireThat(isDeepStrictEqual(adherenceSnapshot(base), sources) && V4_APKS.every(p => hash(join(base, p)) === apks[p]),
    'Output-adherence inputs changed during freeze');
  for (const [path, expected] of Object.entries({ ...sources, ...apks }))
    requireThat(hash(join(output, 'snapshot', path)) === expected, `Frozen copy mismatch: ${path}`);
  writeFileSync(join(output, 'execution-freeze.json'), JSON.stringify(frozen, null, 2) + '\n', { flag: 'wx' });
  return frozen;
}

/** Must run before ADB, model access, private staging or ledger reservation. */
export function admitAdherenceExecution(freezePath, authorizationPath, serial, base = '.') {
  requireThat(typeof freezePath === 'string' && typeof authorizationPath === 'string',
    'Output adherence requires a reviewed freeze and separate S24 execution authorization');
  const frozen = read(freezePath), authorization = read(authorizationPath), approved = loadApprovedAdherence(base);
  const freezeSha256 = hash(freezePath), sources = adherenceSnapshot(base);
  requireThat(frozen.schema === 1 && frozen.protocolId === OA_ID && frozen.status === 'awaiting-separate-device-authorization' &&
    frozen.deviceExecutionAuthorized === false && frozen.semanticEnablement === false && frozen.largeCampaignAllowed === false &&
    frozen.requests === 6 && frozen.deadlineMs === 30000 && frozen.cleanupWithinDeadline === true &&
    frozen.review?.verdict === 'PASS' && frozen.review.scope === reviewScope && frozen.review.deviceExecutionAuthorized === false &&
    frozen.review.snapshotSha256 === frozen.snapshotSha256 && frozen.snapshotSha256 === sha256(JSON.stringify(sources)) &&
    isDeepStrictEqual(frozen.sources, sources) && isDeepStrictEqual(frozen.plan, approved.plan) &&
    isDeepStrictEqual(frozen.oracles, approved.oracles) && isDeepStrictEqual(frozen.bindings, approved.bindings) &&
    isDeepStrictEqual(frozen.profile, v4Profile(base)) && frozen.profile.id === OA_POLICY &&
    frozen.profileSha256 === sha256(JSON.stringify(frozen.profile)) &&
    isDeepStrictEqual(frozen.build, { variant: 'secureQa', testBuildType: 'secureQa', androidPressureMemoryTest: true }) &&
    isDeepStrictEqual(frozen.apks, Object.fromEntries(V4_APKS.map(p => [p, hash(join(base, p))]))),
  'Invalid/stale output-adherence execution freeze');
  requireThat(authorization.schema === 1 && authorization.protocolId === OA_ID && authorization.approved === true &&
    authorization.scope === 'single-supervised-output-adherence-six-request' && authorization.freezeSha256 === freezeSha256 &&
    authorization.profileSha256 === frozen.profileSha256 && authorization.serial === serial &&
    typeof serial === 'string' && /^[A-Za-z0-9._:-]+$/.test(serial) && authorization.device === 'SM-S928B' &&
    authorization.requests === 6 && authorization.deadlineMs === 30000, 'Missing/mismatched separate output-adherence S24 authorization');
  return { frozen, freezeSha256 };
}

export function reserveAdherenceAttempt(admission, runId, folder, base = '.') {
  requireThat(/^[a-f0-9]{64}$/.test(admission.freezeSha256), 'Invalid output-adherence attempt identity');
  const root = join(base, 'benchmarks/results/output-adherence-ledger'); mkdirSync(root, { recursive: true });
  const path = join(root, `${admission.freezeSha256}.json`);
  writeFileSync(path, JSON.stringify({ runId, folder: resolve(folder), freezeSha256: admission.freezeSha256,
    startedAtMs: Date.now() }, null, 2) + '\n', { flag: 'wx' });
  return path;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    requireThat(process.argv.length === 4, 'Usage: node scripts/output-adherence-freeze.mjs NEW_FOLDER INDEPENDENT_CODE_REVIEW_JSON');
    createAdherenceFreeze(process.argv[2], process.argv[3]);
    console.log('Output-adherence freeze prepared; S24 execution NOT authorized.');
  } catch (error) { console.error(error.message); process.exitCode = 1; }
}
