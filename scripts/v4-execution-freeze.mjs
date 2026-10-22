import { readFileSync, readdirSync, mkdirSync, writeFileSync, copyFileSync, constants,
  mkdtempSync, chmodSync, lstatSync, realpathSync, rmSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { pathToFileURL } from 'node:url';
import { isDeepStrictEqual } from 'node:util';
import { loadV4Plan, readV4, requireV4, v4Profile, V4_ID } from './v4-feasibility.mjs';
import { sha256 } from './v3-feasibility.mjs';
import { validateMemoryPolicy } from './memory-policy.mjs';

export const V4_APKS = ['androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk',
  'androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk'];
const approvalPath = 'planning/fixtures/serial-verification-v4/implementation-authorization.json';
const profilePath = 'build/v4-resource-profile.json';
const pressureApprovalPath = 'planning/fixtures/serial-verification-v4/pressure-authorization.json';
const adrPath = 'docs/decisions/0006-evidence-verification-latency.md';
const protocolPath = 'planning/P2_V4_SERIAL_VERIFICATION_FEASIBILITY.md';
const roots = ['shared/src', 'ui/src', 'androidApp/src', 'scripts', 'gradle'];
const fixed = ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat',
  'shared/build.gradle.kts', 'ui/build.gradle.kts', 'androidApp/build.gradle.kts',
  adrPath, protocolPath, approvalPath, pressureApprovalPath, 'planning/fixtures/semantic-retrieval-v1.json',
  'planning/fixtures/semantic-retrieval-v1.lock.json'];
const hashFile = path => sha256(readFileSync(path));
const jsonBytes = object => JSON.stringify(object, null, 2) + '\n';

export function v4SourceSnapshot(base = '.') {
  const paths = new Set(fixed);
  const walk = path => {
    for (const entry of readdirSync(join(base, path), { withFileTypes: true })) {
      if (entry.name === '__pycache__') continue;
      const child = `${path}/${entry.name}`;
      requireV4(!entry.isSymbolicLink(), 'Symlink in V4 executable snapshot');
      if (entry.isDirectory()) walk(child); else paths.add(child);
    }
  };
  for (const root of roots) walk(root);
  for (const path of ['planning/fixtures/evidence-sufficiency-v3/freeze.json',
    'planning/fixtures/evidence-sufficiency-v3/independent-review.json', 'build/encoder-counting/counting-proof.json',
    'build/encoder-counting/reference.tsv', profilePath]) paths.add(path);
  const freeze = readV4(join(base, 'planning/fixtures/evidence-sufficiency-v3/freeze.json'));
  for (const file of freeze.files) paths.add(file.path);
  return Object.fromEntries([...paths].sort().map(path => [path, hashFile(join(base, path))]));
}

export function createV4Freeze(output, reviewPath, base = '.') {
  const authorization = readV4(join(base, approvalPath));
  requireV4(authorization.protocolId === V4_ID && authorization.deadlineMs === 30000 && authorization.requests === 8 &&
    authorization.implementationApproved === true && authorization.deviceExecutionAuthorized === false &&
    authorization.productionLatencyTarget === false &&
    authorization.reviewedAdrSha256 === hashFile(join(base, adrPath)) &&
    authorization.reviewedProtocolSha256 === hashFile(join(base, protocolPath)), 'Missing limited V4 implementation approval');
  const plan = loadV4Plan(base), sources = v4SourceSnapshot(base), review = readV4(reviewPath);
  const snapshotSha256 = sha256(JSON.stringify(sources));
  requireV4(review.verdict === 'PASS' && review.scope === 'v4-implementation-and-host-qa' &&
    review.snapshotSha256 === snapshotSha256 && review.deviceExecutionAuthorized === false,
  'Missing/stale independent V4 review');
  const profile = v4Profile(base);
  if (!profile.enforceAvailableMemoryFloor) {
    const approved = readV4(join(base, pressureApprovalPath));
    requireV4(approved.profileId === profile.id && approved.implementationApproved === true &&
      approved.ordinaryAppPolicyUnchanged === true && approved.thermalSignalsOwnershipCleanupRetained === true,
    'Missing explicit V4 pressure-profile approval');
  }
  requireV4(profile.contextTokens === 2048 && profile.outputTokens === 512, 'V4 profile budget changed');
  const apks = Object.fromEntries(V4_APKS.map(path => {
    const bytes = readFileSync(join(base, path));
    requireV4(bytes.length > 100000, 'Missing assembled V4 APK');
    return [path, sha256(bytes)];
  }));
  const value = { schema: 4, protocolId: V4_ID, status: 'awaiting-device-authorization',
    deviceExecutionAuthorized: false, semanticEnablement: false, largeCampaignAllowed: false,
    deadlineMs: 30000, requests: 8, cleanupWithinDeadline: true,
    createdAtMs: Date.now(), sources, snapshotSha256, review, reviewSha256: hashFile(reviewPath),
    apks, profile, profileSha256: sha256(JSON.stringify(profile)), plan, planSha256: sha256(JSON.stringify(plan)),
    build: { variant: 'secureQa', testBuildType: 'secureQa', androidPressureMemoryTest: !profile.enforceAvailableMemoryFloor },
    acceptance: ['all-eight-in-order', 'decision-and-confirmed-cleanup-under-30000ms', 'first-block-stops',
      'serial-allocation', 'current-counting', 'strict-output-validator', 'healthy-telemetry', 'closed-supervision',
      'no-quality-or-production-claim'] };
  mkdirSync(output, { recursive: false });
  for (const path of [...Object.keys(sources), ...V4_APKS]) {
    const target = join(output, 'snapshot', path); mkdirSync(dirname(target), { recursive: true });
    copyFileSync(join(base, path), target);
  }
  // Recheck after copying: a build/editor change must not publish mixed-source evidence.
  requireV4(JSON.stringify(sources) === JSON.stringify(v4SourceSnapshot(base)) &&
    V4_APKS.every(path => hashFile(join(base, path)) === apks[path]), 'V4 snapshot changed during freeze');
  for (const [path, hash] of Object.entries({ ...sources, ...apks }))
    requireV4(hashFile(join(output, 'snapshot', path)) === hash, `Frozen copy mismatch: ${path}`);
  writeFileSync(join(output, 'execution-freeze.json'), jsonBytes(value), { flag: 'wx' });
  return value;
}

/** Called before ADB, model access or creation of a device workload. No authorization is manufactured. */
export function admitV4Execution(freezePath, authorizationPath, serial, base = '.') {
  requireV4(typeof freezePath === 'string' && typeof authorizationPath === 'string',
    'V4 requires execution freeze and separate later S24 authorization');
  const frozen = readV4(freezePath), auth = readV4(authorizationPath), freezeSha256 = hashFile(freezePath);
  requireV4(frozen.schema === 4 && frozen.protocolId === V4_ID && frozen.status === 'awaiting-device-authorization' &&
    frozen.deviceExecutionAuthorized === false && frozen.deadlineMs === 30000 && frozen.requests === 8 &&
    frozen.cleanupWithinDeadline === true && frozen.semanticEnablement === false && frozen.largeCampaignAllowed === false &&
    frozen.review?.verdict === 'PASS' && frozen.review.snapshotSha256 === frozen.snapshotSha256 &&
    JSON.stringify(frozen.sources) === JSON.stringify(v4SourceSnapshot(base)) &&
    frozen.snapshotSha256 === sha256(JSON.stringify(frozen.sources)) &&
    frozen.planSha256 === sha256(JSON.stringify(loadV4Plan(base))) &&
    frozen.planSha256 === sha256(JSON.stringify(frozen.plan)) &&
    isDeepStrictEqual(frozen.profile, v4Profile(base)) &&
    isDeepStrictEqual(frozen.build, { variant: 'secureQa', testBuildType: 'secureQa',
      androidPressureMemoryTest: !frozen.profile.enforceAvailableMemoryFloor }) &&
    frozen.profileSha256 === sha256(JSON.stringify(validateMemoryPolicy(frozen.profile))) &&
    JSON.stringify(frozen.apks) === JSON.stringify(Object.fromEntries(V4_APKS.map(p => [p, hashFile(join(base, p))]))),
  'Invalid/stale V4 execution freeze');
  requireV4(auth.schema === 4 && auth.scope === 'single-supervised-v4-eight-request' && auth.approved === true &&
    auth.freezeSha256 === freezeSha256 && auth.profileSha256 === frozen.profileSha256 &&
    typeof serial === 'string' && /^[A-Za-z0-9._:-]+$/.test(serial) && auth.serial === serial &&
    auth.requests === 8 && auth.deadlineMs === 30000 && auth.device === 'SM-S928B',
  'Missing/mismatched later S24 execution authorization');
  return { frozen, freezeSha256 };
}

/** Stage both bundle artifacts before ADB. Never install or hash mutable Gradle outputs after admission. */
export function prepareV4Apks(admission, freezePath) {
  requireV4(hashFile(freezePath) === admission.freezeSha256 &&
    isDeepStrictEqual(Object.keys(admission.frozen.apks).sort(), [...V4_APKS].sort()),
  'V4 APK bundle differs from admission');
  const bundle = realpathSync(dirname(freezePath));
  const directory = mkdtempSync(join(tmpdir(), 'pma-v4-apks-'));
  const close = () => rmSync(directory, { recursive: true, force: true });
  try {
    chmodSync(directory, 0o700);
    const files = V4_APKS.map((source, index) => {
      const frozenPath = join(bundle, 'snapshot', source);
      requireV4(lstatSync(frozenPath).isFile() && realpathSync(frozenPath) === frozenPath,
        `Invalid frozen APK file: ${source}`);
      const path = join(directory, `${index}.apk`);
      copyFileSync(frozenPath, path, constants.COPYFILE_EXCL);
      chmodSync(path, 0o400);
      const sha256 = hashFile(path);
      requireV4(sha256 === admission.frozen.apks[source], `Frozen APK hash mismatch: ${source}`);
      return Object.freeze({ source, path, sha256 });
    });
    return Object.freeze({ files: Object.freeze(files), close });
  } catch (error) { close(); throw error; }
}

export function installV4Apks(prepared, run) {
  // Check the complete pair before the first install; corrupt test artifacts must not partly update the app.
  for (const file of prepared.files)
    requireV4(hashFile(file.path) === file.sha256, `Private V4 APK hash mismatch: ${file.source}`);
  for (const file of prepared.files) run('install', '-r', file.path);
}

/** One attempt per freeze across authorization reformatting or copied freeze files. */
export function reserveV4Attempt(admission, runId, folder, base = '.') {
  requireV4(/^[a-f0-9]{64}$/.test(admission.freezeSha256), 'Invalid V4 attempt identity');
  const ledger = join(base, 'benchmarks/results/v4-execution-ledger');
  mkdirSync(ledger, { recursive: true });
  const path = join(ledger, `${admission.freezeSha256}.json`);
  writeFileSync(path, jsonBytes({ runId, folder: resolve(folder), startedAtMs: Date.now(),
    freezeSha256: admission.freezeSha256 }), { flag: 'wx' });
  return path;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    requireV4(process.argv.length === 4, 'Usage: node scripts/v4-execution-freeze.mjs NEW_OUTPUT_FOLDER INDEPENDENT_REVIEW_JSON');
    createV4Freeze(process.argv[2], process.argv[3]);
    console.log(`V4 freeze created at ${relative('.', process.argv[2])}; device execution NOT authorized.`);
  } catch (e) { console.error(e.message); process.exitCode = 1; }
}
