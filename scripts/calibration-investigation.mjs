import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { digest } from './semantic-quality.mjs';
import { protocol, protocolId, splitSizes, check, jsonHash, readJson, validateDataset,
  validateSeparation, validateReview, batchPlans, validateBatches } from './calibration-data.mjs';
import { rankings, selectPolicy, measure, intervals, policyIds, threshold } from './calibration-policy.mjs';
import { requireAccepted } from './calibration-collection.mjs';

export const implementationPaths = [
  'scripts/calibration-policy.mjs', 'scripts/calibration-data.mjs', 'scripts/calibration-investigation.mjs', 'scripts/calibration-collection.mjs',
  'scripts/semantic-quality.mjs', 'scripts/run-p2-embedding-gate.mjs',
  'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/CalibrationCollectionTest.kt',
  'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt',
  'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/MemoryPolicy.kt',
  'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncodingCall.kt',
  'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/ForegroundEncoder.kt',
  'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncoderAdmission.kt',
  'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/UseTokenizer.kt',
  'shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/embedding/AndroidUseAdmission.kt',
  'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/AndroidForegroundEncoder.kt',
  'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/EmbeddingGateService.kt',
  'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/SdkPacketTransport.kt',
  'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/DeviceSupervision.kt',
  'scripts/qa-supervision.mjs', 'scripts/monitor-model-thermal.mjs',
];
export const codeIdentity = () => Object.fromEntries(implementationPaths.map(path => [path, digest(readFileSync(path))]));
export function writeOnce(path, value) {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
}

export function freeze(root) {
  const datasets = Object.fromEntries(Object.keys(splitSizes).map(split => [split, readJson(join(root, `${split}.json`))]));
  validateSeparation(Object.fromEntries(Object.entries(datasets).map(([key, entry]) => [key, entry.value])));
  const frozenProtocol = readJson(join(root, 'protocol.json'));
  check(jsonHash(frozenProtocol.value) === jsonHash(protocol), 'Protocol differs from implemented definitions');
  const lock = { schema: 2, protocolId, protocolSha256: frozenProtocol.sha256,
    datasets: Object.fromEntries(Object.entries(datasets).map(([key, entry]) => [key, entry.sha256])),
    code: codeIdentity(), frozenBeforeScores: true, independentReviewRequired: true };
  writeOnce(join(root, 'lock.json'), lock);
  return lock;
}

export function loadLock(root) {
  const lock = readJson(join(root, 'lock.json'));
  const frozenProtocol = readJson(join(root, 'protocol.json'));
  check(lock.value.schema === 2 && lock.value.protocolId === protocolId && lock.value.frozenBeforeScores === true &&
    lock.value.independentReviewRequired === true && lock.value.protocolSha256 === frozenProtocol.sha256 &&
    jsonHash(frozenProtocol.value) === jsonHash(protocol) && jsonHash(lock.value.code) === jsonHash(codeIdentity()) &&
    Object.keys(splitSizes).every(split => /^[a-f0-9]{64}$/.test(lock.value.datasets?.[split])), 'Stale/unfrozen investigation');
  return lock;
}

export function loadReview(root, lock) {
  const review = readJson(join(root, 'independent-review.json'));
  validateReview(review.value, lock.sha256, lock.value);
  return review;
}

function loadDataset(root, split, lock) {
  const data = readJson(join(root, `${split}.json`));
  check(data.sha256 === lock.value.datasets[split], 'Frozen dataset changed');
  validateDataset(data.value, split);
  return data.value;
}

function loadSealedSelection(root, lock, review, expectedState) {
  check(existsSync(join(root, 'execution.json')) && existsSync(join(root, 'selection-seal.json')), 'Missing sealed selection');
  const execution = readJson(join(root, 'execution.json')).value;
  check(execution.schema === 2 && execution.lockSha256 === lock.sha256 && typeof execution.state === 'string' &&
    resolve(execution.state) === execution.state && (!expectedState || execution.state === resolve(expectedState)),
  'Selection execution does not match frozen campaign');
  const state = execution.state;
  check(existsSync(join(state, 'selection.json')), 'Missing selected policy manifest');
  const selection = readJson(join(state, 'selection.json')); const chosen = selection.value;
  check(chosen.schema === 2 && chosen.protocolId === protocolId && chosen.status === 'selected' &&
    policyIds.includes(chosen.selected?.policyId) && chosen.lockSha256 === lock.sha256 && chosen.reviewSha256 === review.sha256 &&
    jsonHash(chosen.code) === jsonHash(lock.value.code) && jsonHash(chosen.datasets) === jsonHash(lock.value.datasets),
  'Missing/incompatible selected policy');
  const seal = readJson(join(root, 'selection-seal.json')).value;
  check(seal.schema === 2 && seal.selectionSha256 === selection.sha256 && seal.lockSha256 === lock.sha256,
    'Selection manifest has no matching seal');
  threshold(chosen.selected.cutoff);
  return { state, selection, chosen };
}

function planBinding(lock, review, split, selection) {
  return { lockSha256: lock.sha256, reviewSha256: review.sha256,
    datasetSha256: lock.value.datasets[split], protocolSha256: lock.value.protocolSha256,
    ...(selection ? { selectionSha256: selection.sha256 } : {}) };
}

export function plansFor(root, split) {
  const lock = loadLock(root); const review = loadReview(root, lock);
  const selected = split === 'heldout' ? loadSealedSelection(root, lock, review) : null;
  if (selected) check(!existsSync(join(root, 'exposed.json')), 'Held-out selection is already exposed');
  const data = loadDataset(root, split, lock);
  return batchPlans(data, planBinding(lock, review, split, selected?.selection));
}

export function assembleCollection(root, split, paths, output) {
  const lock = loadLock(root); const review = loadReview(root, lock);
  const plans = plansFor(root, split);
  const dataset = loadDataset(root, split, lock);
  const attempts = paths.map(path => readJson(path).value);
  for (const attempt of attempts) requireAccepted(root, attempt);
  validateBatches(dataset, plans, attempts, review.value.reviewedAtMs);
  const result = { schema: 2, split, lockSha256: lock.sha256, attempts,
    provenance: paths.map(path => ({ path: resolve(path), sha256: readJson(path).sha256 })) };
  writeOnce(output, result);
  return result;
}

function collection(root, split, lock, review, path) {
  const selected = split === 'heldout' ? loadSealedSelection(root, lock, review) : null;
  const dataset = loadDataset(root, split, lock);
  const file = readJson(path);
  check(file.value.schema === 2 && file.value.split === split && file.value.lockSha256 === lock.sha256 &&
    Array.isArray(file.value.attempts), 'Invalid split collection');
  const plans = batchPlans(dataset, planBinding(lock, review, split, selected?.selection));
  for (const attempt of file.value.attempts) { check(attempt.split === split, 'Foreign split attempt'); requireAccepted(root, attempt); }
  return { dataset, sha256: file.sha256,
    ...validateBatches(dataset, plans, file.value.attempts, review.value.reviewedAtMs) };
}

// These paths are fixed, so fitting has no argument through which to load held-out data.
export function fitAndSelect(root, state, calibrationPath, developmentPath) {
  check(!existsSync(join(root, 'exposed.json')) && !existsSync(join(root, 'execution.json')) &&
    !existsSync(join(state, 'selection.json')), 'Selection is already sealed/exposed');
  const lock = loadLock(root); const review = loadReview(root, lock);
  const calibration = collection(root, 'calibration', lock, review, calibrationPath);
  const development = collection(root, 'development', lock, review, developmentPath);
  check(jsonHash(calibration.snapshot) === jsonHash(development.snapshot), 'Mixed calibration/development runtime');
  writeOnce(join(root, 'execution.json'), { schema: 2, state: resolve(state), lockSha256: lock.sha256 });
  const result = selectPolicy(rankings(calibration.dataset, calibration.vectors), rankings(development.dataset, development.vectors));
  const manifest = { schema: 2, protocolId, lockSha256: lock.sha256, reviewSha256: review.sha256,
    code: lock.value.code, datasets: lock.value.datasets, snapshot: calibration.snapshot,
    collections: { calibration: calibration.sha256, development: development.sha256 }, ...result };
  writeOnce(join(state, 'selection.json'), manifest);
  sealSelection(root, state);
  return manifest;
}

export function evaluateOnce(root, state, heldoutPath) {
  const lock = loadLock(root); const review = loadReview(root, lock);
  const { selection, chosen } = loadSealedSelection(root, lock, review, state);
  check(!existsSync(join(state, 'result.json')), 'Held-out result already exists');
  writeOnce(join(root, 'exposed.json'), { schema: 2, protocolId, lockSha256: lock.sha256,
    selectionSha256: selection.sha256, heldoutCollectionSha256: readJson(heldoutPath).sha256, exposedAtMs: Date.now() });
  try {
    const heldout = collection(root, 'heldout', lock, review, heldoutPath);
    check(jsonHash(heldout.snapshot) === jsonHash(chosen.snapshot), 'Held-out runtime differs from selected configuration');
    const ranked = rankings(heldout.dataset, heldout.vectors);
    const metrics = measure(ranked, chosen.selected.cutoff);
    const result = { schema: 2, protocolId, selectionSha256: selection.sha256, lockSha256: lock.sha256,
      heldoutCollectionSha256: heldout.sha256, policy: chosen.selected, metrics,
      uncertainty: intervals(metrics.queries), status: metrics.passed ? 'quality-passed' : 'quality-failed',
      unfilteredDiagnostic: { ...measure(ranked, { kind: 'all' }), use: 'ranking-only-not-qualification' },
      productionEnablement: false, fullRetrievalVerified: false };
    writeOnce(join(state, 'result.json'), result);
    return result;
  } catch (error) {
    writeOnce(join(state, 'evaluation-failure.json'), { schema: 2, status: 'infrastructure-failed-after-exposure',
      selectionSha256: selection.sha256, error: error.message, productionEnablement: false });
    throw error;
  }
}

function sealSelection(root, state) {
  const selection = readJson(join(state, 'selection.json'));
  writeOnce(join(root, 'selection-seal.json'), { schema: 2, selectionSha256: selection.sha256, lockSha256: selection.value.lockSha256 });
}

function main(args) {
  const [command, root, state, ...rest] = args;
  check(root && ['freeze', 'check', 'plan', 'assemble', 'select', 'evaluate'].includes(command),
    'Usage: calibration-investigation.mjs freeze|check ROOT; plan ROOT OUT SPLIT; assemble ROOT OUT SPLIT ATTEMPT...; select ROOT STATE CAL DEV; evaluate ROOT STATE HELDOUT');
  if (command === 'freeze' || command === 'check') {
    check(!state && !rest.length, 'Unexpected arguments');
    if (command === 'freeze') freeze(root);
    else { const lock = loadLock(root); loadReview(root, lock); }
    console.log(command === 'freeze' ? 'Frozen; independent review remains required' : 'Reviewed frozen inputs verified');
  } else if (command === 'plan') {
    check(state && rest.length === 1 && Object.hasOwn(splitSizes, rest[0]), 'Invalid plan arguments');
    for (const plan of plansFor(root, rest[0])) writeOnce(join(state, `${rest[0]}-${plan.index}.json`), plan);
    console.log('Review-gated batch plans written; no native work performed');
  } else if (command === 'select') {
    check(state && rest.length === 2, 'Only calibration and development collection files are accepted');
    const result = fitAndSelect(root, state, ...rest);
    console.log(`Development selection: ${result.status}`); if (!result.selected) process.exitCode = 2;
  } else if (command === 'assemble') {
    check(state && rest.length > 1 && Object.hasOwn(splitSizes, rest[0]), 'Expected split and all successful attempt paths');
    assembleCollection(root, rest[0], rest.slice(1), state);
    console.log('Complete compatible split collection assembled; no policy evaluation performed');
  } else {
    check(state && rest.length === 1, 'Expected exactly one held-out collection');
    const result = evaluateOnce(root, state, rest[0]);
    console.log(`Held-out qualification: ${result.status}`); if (!result.metrics.passed) process.exitCode = 2;
  }
}
if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(process.argv.slice(2)); } catch (error) { console.error(error.message); process.exitCode = 1; }
}
