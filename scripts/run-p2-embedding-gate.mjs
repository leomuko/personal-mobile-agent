import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { setTimeout as wait } from 'node:timers/promises';
import { readMemoryPolicy } from './memory-policy.mjs';
import { parseResources, readFreshAndroidPressure, validatePreflightPolicy, waitForResources } from './chat-resource-preflight.mjs';
import { stopSupervisedQa, supervisionDirectory } from './qa-supervision.mjs';
import { validSupervision } from './verify-qa-supervision.mjs';
import { validateEncoderEvidence, validateForegroundEvidence } from './p2-encoder-evidence.mjs';
import { loadSemanticFixture, validateQualityCollection, evaluateSemantic } from './semantic-quality.mjs';

// Focused prerequisite only; not the deferred standalone Gemma smoke or P2 acceptance campaign.
const pressure = process.argv.includes('--android-pressure');
const foreground = process.argv.includes('--foreground');
const semantic = process.argv.includes('--semantic-qualification');
if (foreground && semantic) throw new Error('Choose one prerequisite workload');
if (process.argv.slice(2).some(arg => !['--android-pressure', '--foreground', '--semantic-qualification'].includes(arg))) throw new Error('Unknown argument');
const frozen = semantic ? loadSemanticFixture() : null;
const serial = process.env.ANDROID_SERIAL;
if (!/^[A-Za-z0-9._:-]+$/.test(serial || '')) throw new Error('Set ANDROID_SERIAL to the designated S24');
const model = process.env.P2_ENCODER_MODEL;
if (!model || !existsSync(model)) throw new Error('Set P2_ENCODER_MODEL to the public pinned encoder candidate');
const bytes = readFileSync(model);
const sha = '89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749';
if (bytes.length !== 6120274 || createHash('sha256').update(bytes).digest('hex') !== sha)
  throw new Error('Encoder candidate size/hash mismatch');
const adb = process.env.ADB || join(process.env.ANDROID_HOME || join(process.env.HOME, 'Library/Android/sdk'), 'platform-tools/adb');
const pkg = 'dev.edgecompanion.app.secureqa';
const runId = randomUUID();
const mode = pressure ? 'android-pressure' : 'baseline';
mkdirSync('benchmarks/results', { recursive: true });
const folder = mkdtempSync(`benchmarks/results/p2-${semantic ? 'semantic' : foreground ? 'foreground' : 'encoder'}-gate-`);
const run = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 30000 });
const report = { schema: 1, runId, mode, startedAtMs: Date.now(), passed: false, status: 'failed',
  nativeEncoder: true, gemmaWorkload: foreground, fullRetrievalVerified: false, sustainedWorkload: false, modelSha256: sha };
if (semantic) { report.fixtureSha256 = frozen.sha256; report.semanticQualification = true; }
let monitor; let instrument; let instrumentOutput = ''; let instrumentEnded = false;
const environment = { ...process.env, ADB: adb, MEMORY_RUN_ID: runId,
  MEMORY_POLICY_REPORT: join(folder, 'memory-policy.json'), QA_SUPERVISION_RUN_ID: runId,
  QA_SUPERVISOR_PID: String(process.pid), THERMAL_REPORT_DIR: join(folder, 'thermal') };
const save = () => writeFileSync(join(folder, 'result.json'), JSON.stringify(report, null, 2));
save();
console.log(`P2 ${semantic ? 'frozen semantic qualification' : foreground ? 'encoder-to-Gemma handoff' : 'encoder-only'} prerequisite: ${folder} (${mode})`);
try {
  for (const path of ['androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk',
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
  if (foreground || semantic) sources.push(
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
  writeFileSync(join(folder, 'source-sha256.json'), JSON.stringify(Object.fromEntries(sources.map(path =>
    [path, createHash('sha256').update(readFileSync(path)).digest('hex')])), null, 2));
  execFileSync('bash', ['scripts/export-memory-policy.sh'], { env: environment, stdio: 'pipe', timeout: 60000 });
  const policy = readMemoryPolicy(environment.MEMORY_POLICY_REPORT);
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
    '-e', 'class', `dev.edgecompanion.app.${semantic ? 'SemanticQualificationTest' : foreground ? 'ForegroundHandoffNativeTest' : 'EmbeddingPrerequisiteTest'}`,
    '-e', semantic ? 'p2Semantic' : foreground ? 'p2Foreground' : 'p2Embedding', 'true',
    '-e', 'supervisionRunId', runId, `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`]);
  instrument.stdout.on('data', data => { instrumentOutput += data; });
  instrument.stderr.on('data', data => { instrumentOutput += data; });
  const completion = new Promise((resolve, reject) => {
    instrument.once('error', reject);
    instrument.once('close', code => { instrumentEnded = true; resolve(code); });
  });
  const timer = setTimeout(() => stopSupervisedQa({ adb, serial, runId }), foreground || semantic ? 180000 : 30000);
  const timeout = setTimeout(() => { if (!instrumentEnded) instrument.kill(); }, foreground || semantic ? 195000 : 40000);
  let exit;
  try { exit = await completion; } finally { clearTimeout(timer); clearTimeout(timeout); }
  writeFileSync(join(folder, 'instrumentation.txt'), instrumentOutput);
  const evidence = JSON.parse(run('exec-out', 'run-as', pkg, 'cat', `no_backup/p2-${semantic ? 'semantic' : foreground ? 'foreground' : 'embedding'}-gate.json`));
  writeFileSync(join(folder, 'encoder.json'), JSON.stringify(evidence, null, 2));
  const supervision = JSON.parse(run('exec-out', 'run-as', pkg, 'cat', `${supervisionDirectory(runId)}/status.json`));
  writeFileSync(join(folder, 'supervision.json'), JSON.stringify(supervision, null, 2));
  if (exit !== 0 || !/^OK \(1 test\)\s*$/m.test(instrumentOutput) || !validSupervision(supervision, runId) ||
      (!semantic && !(foreground ? validateForegroundEvidence : validateEncoderEvidence)(evidence,
        { runId, startedAtMs: report.startedAtMs, modelSha256: sha, policyId: policy.id })) ||
      monitor.exitCode !== null ||
      existsSync(join(environment.THERMAL_REPORT_DIR, 'safety-stop.json'))) throw new Error('No fresh supervised encoder pass');
  if (semantic) {
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
  if (instrument && !instrumentEnded) { stopSupervisedQa({ adb, serial, runId }); await wait(8000); instrument.kill(); }
  if (monitor && monitor.exitCode === null && monitor.signalCode === null) {
    const closed = new Promise(resolve => monitor.once('close', resolve));
    monitor.kill(); await closed;
  }
  writeFileSync(join(folder, 'instrumentation.txt'), instrumentOutput);
  report.finishedAtMs = Date.now(); save();
}
console.log(`P2 encoder prerequisite ${report.status}: ${folder}`);
