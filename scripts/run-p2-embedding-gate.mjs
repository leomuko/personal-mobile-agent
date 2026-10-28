import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { setTimeout as wait } from 'node:timers/promises';
import { readMemoryPolicy } from './memory-policy.mjs';
import { parseResources, readFreshAndroidPressure, validatePreflightPolicy, waitForResources } from './chat-resource-preflight.mjs';
import { stopSupervisedQa, supervisionDirectory } from './qa-supervision.mjs';
import { validSupervision } from './verify-qa-supervision.mjs';
import { validateEncoderEvidence, validateForegroundEvidence } from './p2-encoder-evidence.mjs';
import { loadSemanticFixture, validateQualityCollection, evaluateSemantic } from './semantic-quality.mjs';
import { loadLock, loadReview, plansFor } from './calibration-investigation.mjs';
import { readJson, jsonHash, candidate } from './calibration-data.mjs';
import { reserveBatch, acceptBatch, releaseBatch } from './calibration-collection.mjs';
import { loadFeasibilityPlan, classifyFeasibility } from './v3-feasibility.mjs';
import { classifyV4 } from './v4-feasibility.mjs';
import { admitV4Execution, prepareV4Apks, installV4Apks, reserveV4Attempt } from './v4-execution-freeze.mjs';
import { admitAdherenceExecution, reserveAdherenceAttempt } from './output-adherence-freeze.mjs';
import { classifyAdherence } from './output-adherence-evidence.mjs';
import { admitConstrainedExecution, reserveConstrainedAttempt } from './constrained-decoding-freeze.mjs';
import { classifyConstrained, validateConstrainedDeadline } from './constrained-decoding-evidence.mjs';

// Focused prerequisite only; not the deferred standalone Gemma smoke or P2 acceptance campaign.
const pressure = process.argv.includes('--android-pressure');
const foreground = process.argv.includes('--foreground');
const semantic = process.argv.includes('--semantic-qualification');
const v3 = process.argv.includes('--v3-feasibility');
const v4 = process.argv.includes('--v4-feasibility');
const adherence = process.argv.includes('--output-adherence');
const constrained = process.argv.includes('--constrained-decoding');
const options = process.argv.slice(2);
let calibrationRoot; let calibrationPath;
let v4FreezePath; let v4AuthorizationPath;
let adherenceFreezePath; let adherenceAuthorizationPath;
let constrainedFreezePath; let constrainedAuthorizationPath;
for (let index = 0; index < options.length; index++) {
  const arg = options[index];
  if (arg === '--constrained-freeze' || arg === '--constrained-authorization') {
    const value = options[++index];
    if (!value || value.startsWith('--')) throw new Error('Missing constrained argument');
    if (arg === '--constrained-freeze') { if (constrainedFreezePath) throw new Error('Duplicate constrained freeze'); constrainedFreezePath = value; }
    else { if (constrainedAuthorizationPath) throw new Error('Duplicate constrained authorization'); constrainedAuthorizationPath = value; }
  } else if (arg === '--adherence-freeze' || arg === '--adherence-authorization') {
    const value = options[++index];
    if (!value || value.startsWith('--')) throw new Error('Missing output-adherence argument');
    if (arg === '--adherence-freeze') { if (adherenceFreezePath) throw new Error('Duplicate adherence freeze'); adherenceFreezePath = value; }
    else { if (adherenceAuthorizationPath) throw new Error('Duplicate adherence authorization'); adherenceAuthorizationPath = value; }
  } else if (arg === '--v4-freeze' || arg === '--v4-authorization') {
    const value = options[++index];
    if (!value || value.startsWith('--')) throw new Error('Missing V4 argument');
    if (arg === '--v4-freeze') { if (v4FreezePath) throw new Error('Duplicate V4 freeze'); v4FreezePath = value; }
    else { if (v4AuthorizationPath) throw new Error('Duplicate V4 authorization'); v4AuthorizationPath = value; }
  } else if (arg === '--calibration-root' || arg === '--calibration-batch') {
    const value = options[++index];
    if (!value || value.startsWith('--')) throw new Error('Missing calibration argument');
    if (arg === '--calibration-root') { if (calibrationRoot) throw new Error('Duplicate root'); calibrationRoot = value; }
    else { if (calibrationPath) throw new Error('Duplicate batch'); calibrationPath = value; }
  } else if (!['--android-pressure', '--foreground', '--semantic-qualification', '--v3-feasibility', '--v4-feasibility', '--output-adherence', '--constrained-decoding'].includes(arg)) throw new Error('Unknown argument');
}
const calibration = Boolean(calibrationPath);
if (Boolean(calibrationRoot) !== calibration || [foreground, semantic, calibration, v3, v4, adherence, constrained].filter(Boolean).length > 1 ||
    (!v4 && (v4FreezePath || v4AuthorizationPath)) || (!adherence && (adherenceFreezePath || adherenceAuthorizationPath)) ||
    (!constrained && (constrainedFreezePath || constrainedAuthorizationPath)))
  throw new Error('Choose one prerequisite workload and its frozen profile');
// This preflight is deliberately before all ADB/model access. No authorization file is created here.
const v4Admission = v4 ? admitV4Execution(v4FreezePath, v4AuthorizationPath, process.env.ANDROID_SERIAL) : null;
const adherenceAdmission = adherence ? admitAdherenceExecution(adherenceFreezePath, adherenceAuthorizationPath, process.env.ANDROID_SERIAL) : null;
const constrainedAdmission = constrained ? admitConstrainedExecution(constrainedFreezePath, constrainedAuthorizationPath, process.env.ANDROID_SERIAL) : null;
const frozenAdmission = v4Admission || adherenceAdmission || constrainedAdmission;
const frozenPath = constrained ? constrainedFreezePath : adherence ? adherenceFreezePath : v4FreezePath;
const authorizationPath = constrained ? constrainedAuthorizationPath : adherence ? adherenceAuthorizationPath : v4AuthorizationPath;
const constrainedPlan = constrainedAdmission?.frozen.plan;
const constrainedPlanSha = constrainedAdmission?.frozen.planSha256;
if (frozenAdmission && pressure !== !frozenAdmission.frozen.profile.enforceAvailableMemoryFloor) throw new Error('Command must select exactly the frozen resource profile');
const adherencePlan = adherenceAdmission?.frozen.plan;
const adherencePlanBytes = adherence ? JSON.stringify(adherencePlan) : null;
const adherencePlanSha = adherenceAdmission?.frozen.bindings.planSha256;
const v4Plan = v4Admission?.frozen.plan;
const v4PlanBytes = v4 ? JSON.stringify(v4Plan) : null;
const v4PlanSha = v4Admission?.frozen.planSha256;
const v3Plan = v3 ? loadFeasibilityPlan() : null;
const v3PlanBytes = v3 ? JSON.stringify(v3Plan) : null;
const v3PlanSha = v3 ? createHash('sha256').update(v3PlanBytes).digest('hex') : null;
// Fail before ADB/model access if review, code, frozen data or batch identity is absent.
let calibrationBatch; let calibrationReview;
if (calibration) {
  const lock = loadLock(calibrationRoot); calibrationReview = loadReview(calibrationRoot, lock);
  const file = readJson(calibrationPath);
  const plan = plansFor(calibrationRoot, file.value.split)[file.value.index];
  if (!plan || jsonHash(plan) !== jsonHash(file.value)) throw new Error('Batch differs from reviewed plan');
  calibrationBatch = { plan, sha256: jsonHash(plan) };
}
const frozen = semantic ? loadSemanticFixture() : null;
const serial = process.env.ANDROID_SERIAL;
if (!/^[A-Za-z0-9._:-]+$/.test(serial || '')) throw new Error('Set ANDROID_SERIAL to the designated S24');
const model = process.env.P2_ENCODER_MODEL;
const sha = '89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749';
const adb = process.env.ADB || join(process.env.ANDROID_HOME || join(process.env.HOME, 'Library/Android/sdk'), 'platform-tools/adb');
const pkg = 'dev.edgecompanion.app.secureqa';
const runId = randomUUID();
const mode = pressure ? 'android-pressure' : 'baseline';
mkdirSync('benchmarks/results', { recursive: true });
const folder = mkdtempSync(`benchmarks/results/p2-${constrained ? 'constrained-decoding' : adherence ? 'output-adherence' : v4 ? 'v4-feasibility' : v3 ? 'v3-feasibility' : calibration ? 'calibration' : semantic ? 'semantic' : foreground ? 'foreground' : 'encoder'}-gate-`);
const run = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 30000 });
const report = { schema: 1, runId, mode, startedAtMs: Date.now(), passed: false, status: 'failed',
  nativeEncoder: true, gemmaWorkload: foreground || v3 || v4 || adherence || constrained, fullRetrievalVerified: false, sustainedWorkload: false, modelSha256: sha };
if (constrained) Object.assign(report, { planSha256: constrainedPlanSha, executionFreezeSha256: constrainedAdmission.freezeSha256,
  protocolId: constrainedPlan.protocolId, plannedRequests: 9, largeCampaignAllowed: false, semanticEnablement: false });
if (adherence) Object.assign(report, { planSha256: adherencePlanSha, executionFreezeSha256: adherenceAdmission.freezeSha256,
  protocolId: adherencePlan.protocolId, plannedRequests: 6, largeCampaignAllowed: false, semanticEnablement: false });
if (v4) { report.planSha256 = v4PlanSha; report.executionFreezeSha256 = v4Admission.freezeSha256; report.largeCampaignAllowed = false; report.semanticEnablement = false; }
if (v3) { report.planSha256 = v3PlanSha; report.freezeSha256 = v3Plan.freezeSha256; report.largeCampaignAllowed = false; }
if (semantic) { report.fixtureSha256 = frozen.sha256; report.semanticQualification = true; }
if (calibration) { report.batchSha256 = calibrationBatch.sha256; report.calibrationCollection = true; }
let monitor; let instrument; let instrumentOutput = ''; let instrumentEnded = false;
let calibrationLease; let frozenApks; let bytes;
const environment = { ...process.env, ADB: adb, MEMORY_RUN_ID: runId,
  MEMORY_POLICY_REPORT: join(folder, 'memory-policy.json'), QA_SUPERVISION_RUN_ID: runId,
  QA_SUPERVISOR_PID: String(process.pid), THERMAL_REPORT_DIR: join(folder, 'thermal') };
const save = () => writeFileSync(join(folder, 'result.json'), JSON.stringify(report, null, 2));
save();
// Outside the device try/catch: corrupt bundles and duplicate attempts must not reach even a scoped ADB stop.
try {
  if (frozenAdmission) frozenApks = prepareV4Apks(frozenAdmission, frozenPath);
  if (!model || !existsSync(model)) throw new Error('Set P2_ENCODER_MODEL to the public pinned encoder candidate');
  bytes = readFileSync(model);
  if (bytes.length !== 6120274 || createHash('sha256').update(bytes).digest('hex') !== sha)
    throw new Error('Encoder candidate size/hash mismatch');
  if (v4) reserveV4Attempt(v4Admission, runId, folder);
  if (adherence) reserveAdherenceAttempt(adherenceAdmission, runId, folder);
  if (constrained) reserveConstrainedAttempt(constrainedAdmission, runId, folder);
} catch (error) { frozenApks?.close(); throw error; }
console.log(`P2 ${constrained ? 'constrained-decoding guard checks' : adherence ? 'output-adherence feasibility' : v4 ? 'V4 explicit verifier feasibility' : v3 ? 'V3 bounded verifier feasibility' : semantic ? 'frozen semantic qualification' : foreground ? 'encoder-to-Gemma handoff' : 'encoder-only'} prerequisite: ${folder} (${mode})`);
try {
  if (frozenAdmission) {
    if (run('shell', 'getprop', 'ro.product.model').trim() !== 'SM-S928B') throw new Error('Verification requires the authorized S24 Ultra');
    installV4Apks(frozenApks, run);
    writeFileSync(join(folder, 'execution-freeze.json'), readFileSync(frozenPath));
    writeFileSync(join(folder, 'execution-authorization.json'), readFileSync(authorizationPath));
  }
  if (calibration) calibrationLease = reserveBatch(calibrationRoot, calibrationBatch.plan, runId, folder);
  if (frozenAdmission) {
    for (const file of frozenApks.files)
      writeFileSync(join(folder, file.source.includes('androidTest') ? 'tests.sha256' : 'app.sha256'), file.sha256 + '\n');
  } else for (const path of ['androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk',
    'androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk']) {
    const apk = readFileSync(path);
    writeFileSync(join(folder, path.includes('androidTest') ? 'tests.sha256' : 'app.sha256'),
      createHash('sha256').update(apk).digest('hex') + '\n');
  }
  writeFileSync(join(folder, 'git-head.txt'), execFileSync('git', ['rev-parse', 'HEAD']));
  writeFileSync(join(folder, 'working-tree-stat.txt'), execFileSync('git', ['diff', '--stat']));
  const sources = [
    'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/EmbeddingGateService.kt',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/EmbeddingPrerequisiteTest.kt',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/DeviceSupervision.kt',
    'androidApp/src/secureQa/AndroidManifest.xml', 'androidApp/src/main/kotlin/dev/edgecompanion/app/CompanionApplication.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/MemoryPolicy.kt',
    'scripts/qa-supervision.mjs', 'scripts/monitor-model-thermal.mjs',
    'scripts/p2-encoder-evidence.mjs', 'scripts/run-p2-embedding-gate.mjs',
  ];
  if (foreground || semantic || calibration || v3 || v4 || adherence) sources.push(
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/UseTokenizer.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncoderAdmission.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/ForegroundEncoder.kt',
    'shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/embedding/AndroidUseAdmission.kt',
    'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/AndroidForegroundEncoder.kt',
    'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/SdkPacketTransport.kt',
    'androidApp/src/main/kotlin/dev/edgecompanion/app/inference/MemoryTelemetryRecorder.kt',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ForegroundHandoffNativeTest.kt',
  );
  if (semantic) sources.push('shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncodingCall.kt',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/SemanticQualificationTest.kt',
    'scripts/semantic-quality.mjs', 'planning/fixtures/semantic-retrieval-v1.json', 'planning/fixtures/semantic-retrieval-v1.lock.json');
  if (calibration) sources.push('shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/embedding/EncodingCall.kt',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/CalibrationCollectionTest.kt',
    'scripts/calibration-policy.mjs', 'scripts/calibration-data.mjs', 'scripts/calibration-investigation.mjs',
    'scripts/calibration-collection.mjs', 'scripts/semantic-quality.mjs');
  if (v3 || v4 || adherence) sources.push('scripts/v3-feasibility.mjs',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/V3FeasibilityTest.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/VerificationCall.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ChatGeneration.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/Inference.kt',
    'shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/LiteRtLlmEngine.kt',
    'shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/assets/PrivateModelAssets.kt',
    'planning/fixtures/evidence-sufficiency-v3/freeze.json',
    'planning/fixtures/evidence-sufficiency-v3/independent-review.json');
  if (v4 || adherence) sources.push('scripts/v4-feasibility.mjs', 'scripts/v4-execution-freeze.mjs',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/V4FeasibilityTest.kt');
  if (adherence) sources.push('scripts/output-adherence-gates.mjs', 'scripts/output-adherence-freeze.mjs',
    'scripts/output-adherence-evidence.mjs', 'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/OutputAdherenceNativeTest.kt',
    'androidApp/src/testSupport/kotlin/dev/edgecompanion/app/support/OutputAdherenceShape.kt');
  if (constrained) sources.push(...Object.keys(constrainedAdmission.frozen.sources));
  writeFileSync(join(folder, 'source-sha256.json'), JSON.stringify(Object.fromEntries(sources.map(path =>
    [path, createHash('sha256').update(readFileSync(path)).digest('hex')])), null, 2));
  execFileSync('bash', ['scripts/export-memory-policy.sh'], { env: environment, stdio: 'pipe', timeout: 60000 });
  const policy = readMemoryPolicy(environment.MEMORY_POLICY_REPORT);
  if (frozenAdmission && !isDeepStrictEqual(policy, frozenAdmission.frozen.profile)) throw new Error('Installed profile differs from execution freeze');
  validatePreflightPolicy(policy, mode);
  let baseline = JSON.parse(readFileSync(join(folder, 'memory-baseline.json')));
  const samples = [];
  const blocked = await waitForResources(() => {
    if (pressure) baseline = readFreshAndroidPressure(run, policy, baseline.recordedAtMs);
    return { ...parseResources(run('shell', 'cat', '/proc/meminfo'), run('shell', 'dumpsys', 'thermalservice')),
      lowMemory: baseline.lowMemory, pressureThresholdBytes: baseline.pressureThresholdBytes,
      androidBaselineRecordedAtMs: baseline.recordedAtMs,
      ...(pressure ? { availableMemoryBytes: baseline.availableMemoryBytes } : {}) };
  }, sample => samples.push(sample), { policy, mode, seconds: 15, intervalMs: 1000 });
  writeFileSync(join(folder, 'preflight.json'), JSON.stringify(samples, null, 2));
  if (blocked) { report.status = 'blocked'; throw new Error(`Preflight blocked: ${blocked}`); }
  const destination = `no_backup/qa-embedding/${sha}.tflite`;
  run('shell', 'run-as', pkg, 'mkdir', '-p', 'no_backup/qa-embedding');
  let installedHash;
  try { installedHash = run('shell', 'run-as', pkg, 'sha256sum', destination).trim().split(/\s+/)[0]; } catch { }
  if (installedHash !== sha) {
    const staging = `no_backup/qa-embedding/staging-${runId}`;
    try {
      execFileSync(adb, ['-s', serial, 'exec-in', `run-as ${pkg} sh -c 'cat > ${staging}'`],
        { input: bytes, timeout: 30000 });
      if (run('shell', 'run-as', pkg, 'sha256sum', staging).trim().split(/\s+/)[0] !== sha) throw new Error('Staged encoder SHA mismatch');
      if (installedHash) throw new Error('Existing immutable encoder is corrupt; repair must be explicit');
      run('shell', 'run-as', pkg, 'mv', '-n', staging, destination);
    } finally { run('shell', 'run-as', pkg, 'rm', '-f', staging); }
  }
  if (semantic) {
    const temporary = `/data/local/tmp/pma-semantic-${runId}.json`;
    try {
      run('push', 'planning/fixtures/semantic-retrieval-v1.json', temporary);
      run('shell', 'run-as', pkg, 'cp', temporary, 'no_backup/qa-embedding/semantic-fixture.json');
    } finally { run('shell', 'rm', '-f', temporary); }
    writeFileSync(join(folder, 'fixture.lock.json'), readFileSync('planning/fixtures/semantic-retrieval-v1.lock.json'));
  }
  if (calibration) {
    const local = join(folder, 'batch.json');
    writeFileSync(local, JSON.stringify(calibrationBatch.plan));
    const temporary = `/data/local/tmp/pma-calibration-${runId}.json`;
    try {
      run('push', local, temporary);
      run('shell', 'run-as', pkg, 'cp', temporary, 'no_backup/qa-embedding/calibration-batch.json');
    } finally { run('shell', 'rm', '-f', temporary); }
  }
  if (v3) {
    const local = join(folder, 'plan.json'); writeFileSync(local, v3PlanBytes);
    const temporary = `/data/local/tmp/pma-v3-${runId}.json`;
    try {
      run('push', local, temporary);
      run('shell', 'run-as', pkg, 'cp', temporary, 'no_backup/qa-embedding/v3-feasibility.json');
    } finally { run('shell', 'rm', '-f', temporary); }
  }
  if (v4) {
    const local = join(folder, 'plan.json'); writeFileSync(local, v4PlanBytes);
    const temporary = `/data/local/tmp/pma-v4-${runId}.json`;
    try {
      run('push', local, temporary);
      run('shell', 'run-as', pkg, 'cp', temporary, 'no_backup/qa-embedding/v4-feasibility.json');
    } finally { run('shell', 'rm', '-f', temporary); }
  }
  if (adherence) {
    for (const [name, bytes] of [['output-adherence.json', adherencePlanBytes],
      ['output-adherence-oracles.json', JSON.stringify(adherenceAdmission.frozen.oracles)]]) {
      const local = join(folder, name); writeFileSync(local, bytes);
      const temporary = `/data/local/tmp/pma-${runId}-${name}`;
      try {
        run('push', local, temporary);
        run('shell', 'run-as', pkg, 'cp', temporary, `no_backup/qa-embedding/${name}`);
      } finally { run('shell', 'rm', '-f', temporary); }
    }
  }
  if (constrained) {
    for (const [name, content] of [['constrained-decoding.json', JSON.stringify(constrainedPlan)],
      ['constrained-decoding-oracles.json', JSON.stringify(constrainedAdmission.frozen.oracles)]]) {
      const local = join(folder, name); writeFileSync(local, content);
      const temporary = `/data/local/tmp/pma-${runId}-${name}`;
      try {
        run('push', local, temporary);
        run('shell', 'run-as', pkg, 'cp', temporary, `no_backup/qa-embedding/${name}`);
      } finally { run('shell', 'rm', '-f', temporary); }
    }
  }
  monitor = spawn(process.execPath, ['scripts/monitor-model-thermal.mjs'], { env: environment });
  let monitorOutput = '';
  monitor.stdout.on('data', data => { monitorOutput += data; });
  monitor.stderr.on('data', data => { monitorOutput += data; });
  monitor.once('close', () => writeFileSync(join(folder, 'monitor.txt'), monitorOutput));
  const ready = join(environment.THERMAL_REPORT_DIR, 'supervision-heartbeats.jsonl');
  for (let count = 0; !existsSync(ready) && count < 50; count++) {
    if (monitor.exitCode !== null) throw new Error('Supervisor failed before admission');
    await wait(100);
  }
  if (!existsSync(ready)) throw new Error('Supervisor did not admit the run');
  instrument = spawn(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
    '-e', 'class', `dev.edgecompanion.app.${constrained ? 'ConstrainedDecodingNativeTest' : adherence ? 'OutputAdherenceNativeTest' : v4 ? 'V4FeasibilityTest' : v3 ? 'V3FeasibilityTest' : calibration ? 'CalibrationCollectionTest' : semantic ? 'SemanticQualificationTest' : foreground ? 'ForegroundHandoffNativeTest' : 'EmbeddingPrerequisiteTest'}`,
    '-e', constrained ? 'p2ConstrainedDecoding' : adherence ? 'p2OutputAdherence' : v4 ? 'p2V4' : v3 ? 'p2V3' : calibration ? 'p2Calibration' : semantic ? 'p2Semantic' : foreground ? 'p2Foreground' : 'p2Embedding', 'true',
    ...(calibration ? ['-e', 'calibrationBatchSha256', calibrationBatch.sha256] : []),
    ...(v3 ? ['-e', 'v3PlanSha256', v3PlanSha] : []),
    ...(v4 ? ['-e', 'v4PlanSha256', v4PlanSha, '-e', 'v4DeviceAuthorized', 'true',
      '-e', 'v4ExecutionFreezeSha256', v4Admission.freezeSha256] : []),
    ...(adherence ? ['-e', 'oaPlanSha256', adherencePlanSha, '-e', 'oaDeviceAuthorized', 'true',
      '-e', 'oaExecutionFreezeSha256', adherenceAdmission.freezeSha256] : []),
    ...(constrained ? ['-e', 'cdPlanSha256', constrainedPlanSha, '-e', 'cdDeviceAuthorized', 'true',
      '-e', 'cdExecutionFreezeSha256', constrainedAdmission.freezeSha256] : []),
    '-e', 'supervisionRunId', runId, `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`]);
  instrument.stdout.on('data', data => { instrumentOutput += data; });
  instrument.stderr.on('data', data => { instrumentOutput += data; });
  const completion = new Promise((resolve, reject) => {
    instrument.once('error', reject);
    instrument.once('close', code => { instrumentEnded = true; resolve(code); });
  });
  const overallMs = constrained ? 360000 : adherence ? 300000 : v4 ? 360000 : v3 ? 120000 : foreground || semantic || calibration ? 180000 : 30000;
  const timer = setTimeout(() => stopSupervisedQa({ adb, serial, runId }), overallMs);
  const timeout = setTimeout(() => { if (!instrumentEnded) instrument.kill(); }, overallMs + 15000);
  let exit;
  try { exit = await completion; } finally { clearTimeout(timer); clearTimeout(timeout); }
  writeFileSync(join(folder, 'instrumentation.txt'), instrumentOutput);
  const evidence = JSON.parse(run('exec-out', 'run-as', pkg, 'cat', constrained ? 'no_backup/p2-constrained-decoding.json' : adherence ? 'no_backup/p2-output-adherence.json' : v4 ? 'no_backup/p2-v4-feasibility.json' : v3 ? 'no_backup/p2-v3-feasibility.json' : `no_backup/p2-${calibration ? 'calibration' : semantic ? 'semantic' : foreground ? 'foreground' : 'embedding'}-gate.json`));
  writeFileSync(join(folder, 'encoder.json'), JSON.stringify(evidence, null, 2));
  const supervision = JSON.parse(run('exec-out', 'run-as', pkg, 'cat', `${supervisionDirectory(runId)}/status.json`));
  writeFileSync(join(folder, 'supervision.json'), JSON.stringify(supervision, null, 2));
  if (exit !== 0 || !/^OK \(1 test\)\s*$/m.test(instrumentOutput) || !validSupervision(supervision, runId) ||
      (!semantic && !calibration && !v3 && !v4 && !adherence && !constrained && !(foreground ? validateForegroundEvidence : validateEncoderEvidence)(evidence,
        { runId, startedAtMs: report.startedAtMs, modelSha256: sha, policyId: policy.id })) ||
      monitor.exitCode !== null ||
      existsSync(join(environment.THERMAL_REPORT_DIR, 'safety-stop.json'))) throw new Error('No fresh supervised encoder pass');
  if (constrained) {
    if (!validSupervision(supervision, runId, report.startedAtMs)) throw new Error('Stale constrained supervision');
    const result = classifyConstrained(evidence, { runId, startedAtMs: report.startedAtMs, planSha256: constrainedPlanSha,
      freezeSha256: constrainedAdmission.freezeSha256, plan: constrainedPlan, oracles: constrainedAdmission.frozen.oracles,
      sentinel: constrainedAdmission.frozen.sentinel });
    Object.assign(report, result, { passed: result.status === 'passed' });
    if (result.status === 'blocked') { report.blockingGate = 'CONSTRAINED_DECODING'; process.exitCode = 2; }
  } else if (adherence) {
    if (!validSupervision(supervision, runId, report.startedAtMs)) throw new Error('Stale output-adherence supervision');
    const result = classifyAdherence(evidence, { runId, startedAtMs: report.startedAtMs, planSha256: adherencePlanSha,
      freezeSha256: adherenceAdmission.freezeSha256, policyId: policy.id, plan: adherencePlan, oracles: adherenceAdmission.frozen.oracles });
    Object.assign(report, result, { passed: result.status === 'passed' });
    if (result.status === 'blocked') { report.blockingGate = 'OUTPUT_ADHERENCE'; process.exitCode = 2; }
  } else if (v4) {
    if (!validSupervision(supervision, runId, report.startedAtMs)) throw new Error('Stale V4 supervision');
    const result = classifyV4(evidence, { runId, startedAtMs: report.startedAtMs, planSha256: v4PlanSha,
      freezeSha256: v4Admission.freezeSha256, policyId: policy.id, plan: v4Plan });
    Object.assign(report, result, { passed: result.status === 'passed' });
    if (result.status === 'blocked') { report.blockingGate = 'V4_FEASIBILITY'; process.exitCode = 2; }
  } else if (v3) {
    if (!validSupervision(supervision, runId, report.startedAtMs)) throw new Error('Stale V3 supervision');
    const result = classifyFeasibility(evidence, { runId, startedAtMs: report.startedAtMs,
      planSha256: v3PlanSha, policyId: policy.id, plan: v3Plan });
    Object.assign(report, result, { passed: result.status === 'passed' });
    if (result.status === 'blocked') { report.blockingGate = 'V3_FEASIBILITY'; process.exitCode = 2; }
  } else if (calibration) {
    const plan = calibrationBatch.plan;
    validateQualityCollection(evidence, { runId, startedAtMs: report.startedAtMs,
      fixtureSha256: calibrationBatch.sha256, policyId: policy.id }, { candidate,
      passages: plan.entries.filter(row => row.kind === 'passage'), queries: plan.entries.filter(row => row.kind === 'query') });
    if (report.startedAtMs < calibrationReview.value.reviewedAtMs) throw new Error('Collection predates review');
    writeFileSync(join(folder, 'attempt.json'), JSON.stringify({ schema: 2, status: 'collected', runId,
      split: plan.split, index: plan.index, batchSha256: calibrationBatch.sha256, startedAtMs: report.startedAtMs, memoryPolicyId: policy.id,
      snapshot: { appSha256: readFileSync(join(folder, 'app.sha256'), 'utf8').trim(),
        testSha256: readFileSync(join(folder, 'tests.sha256'), 'utf8').trim(),
        sourcesSha256: createHash('sha256').update(readFileSync(join(folder, 'source-sha256.json'))).digest('hex') }, evidence }, null, 2));
    acceptBatch(calibrationLease, join(folder, 'attempt.json'));
    report.collectionPassed = true; report.passed = true; report.status = 'collected';
  } else if (semantic) {
    const vectors = validateQualityCollection(evidence, { runId, startedAtMs: report.startedAtMs,
      fixtureSha256: frozen.sha256, policyId: policy.id }, frozen.fixture);
    const quality = evaluateSemantic(frozen.fixture, vectors);
    writeFileSync(join(folder, 'quality.json'), JSON.stringify({ ...quality, runId, fixtureSha256: frozen.sha256 }, null, 2));
    report.collectionPassed = true;
    report.passed = quality.passed;
    report.status = quality.passed ? 'passed' : 'blocked';
    if (!quality.passed) { report.blockingGate = 'SEMANTIC_QUALITY'; process.exitCode = 2; }
  } else { report.passed = true; report.status = 'passed'; }
} catch (error) {
  report.error = error.message; process.exitCode = report.status === 'blocked' ? 2 : 1;
  stopSupervisedQa({ adb, serial, runId });
} finally {
  try {
    if (instrument && !instrumentEnded) { stopSupervisedQa({ adb, serial, runId }); await wait(8000); instrument.kill(); }
    if (monitor && monitor.exitCode === null && monitor.signalCode === null) {
      const closed = new Promise(resolve => monitor.once('close', resolve));
      monitor.kill(); await closed;
    }
    writeFileSync(join(folder, 'instrumentation.txt'), instrumentOutput);
    if (constrained && instrument) {
      // Separate immutable diagnostic survives a later generic scoped stop message.
      try {
        const path = `${supervisionDirectory(runId)}/constrained-deadline.json`;
        const value = JSON.parse(run('shell', `run-as ${pkg} sh -c 'if [ -f ${path} ]; then cat ${path}; else printf null; fi'`));
        const emergency = validateConstrainedDeadline(value, runId);
        if (emergency) {
          writeFileSync(join(folder, 'device-emergency.json'), JSON.stringify(emergency, null, 2));
          report.deviceEmergency = emergency; report.passed = false; report.status = 'failed';
          report.blockingGate = 'EMERGENCY_CLEANUP'; process.exitCode = 1;
        }
      } catch (error) { if (report.passed) { report.status = 'failed'; report.passed = false; report.error = error.message; process.exitCode = 1; } }
    }
    report.finishedAtMs = Date.now(); save();
    if (calibrationLease) releaseBatch(calibrationLease);
  } finally { frozenApks?.close(); }
}
console.log(`P2 encoder prerequisite ${report.status}: ${folder}`);
