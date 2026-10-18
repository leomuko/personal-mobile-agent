import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { createHash, randomUUID } from 'node:crypto';
import { setTimeout as wait } from 'node:timers/promises';
import { renewSupervision, stopSupervisedQa, supervisionDirectory } from './qa-supervision.mjs';
import { validSupervision } from './verify-qa-supervision.mjs';
import { readMemoryPolicy } from './memory-policy.mjs';
import { parseResources, readFreshAndroidPressure, validatePreflightPolicy, waitForResources } from './chat-resource-preflight.mjs';

// A coarse manager state or an old phase file must never count as native loss evidence.
export function validateNativeLoss({ phase, loss, supervision, drop, stop, runId, policy, pidGone }) {
  if (!['INITIALIZING', 'PREFILL', 'DECODING'].includes(phase) || !pidGone ||
      loss?.schema !== 1 || loss.runId !== runId || loss.target !== phase || loss.phase !== phase ||
      loss.lossObserved !== true || loss.backend !== 'GPU' || loss.automaticFallback !== false ||
      loss.runtimeStatus !== (phase === 'INITIALIZING' ? 'LOADING' : 'GENERATING') || loss.runtimeError !== null ||
      loss.lowMemory !== false || !Number.isSafeInteger(loss.thermalStatus) || loss.thermalStatus < 0 ||
      loss.thermalStatus >= policy.severeThermalStatus ||
      loss.policyId !== policy.id || loss.modelSha256 !== policy.modelSha256 || loss.runtimeVersion !== policy.runtimeVersion ||
      (phase === 'PREFILL' && (loss.promptSha256 !== 'e769e953b92ff0677b861257131627d3558d06de671c645fbfe08bee7bf13f5c' ||
        loss.contextTokens !== 2048 || loss.maxOutputTokens !== 512)) ||
      supervision?.schema !== 2 || supervision.runId !== runId || supervision.reason !== 'UNAVAILABLE' ||
      !['lost', 'terminated'].includes(supervision.status) || supervision.leaseMs !== 20000 ||
      supervision.pollMs !== 500 || supervision.cleanupMs !== 5000 ||
      drop?.runId !== runId || drop.phase !== phase || !Number.isSafeInteger(drop.elapsedMs) ||
      stop?.runId !== runId || stop.phase !== phase || !Number.isFinite(stop.elapsedSinceLossMs) ||
      stop.elapsedSinceLossMs < 0 || stop.elapsedSinceLossMs > 9000 ||
      !Number.isSafeInteger(loss.elapsedMs) || !Number.isSafeInteger(supervision.elapsedMs) ||
      loss.elapsedMs < drop.elapsedMs || supervision.elapsedMs < drop.elapsedMs ||
      loss.elapsedMs - drop.elapsedMs > 8000 || !Array.isArray(loss.timeline)) return false;
  const entries = loss.timeline;
  if (!entries.length || entries.some((item, i) => !Number.isSafeInteger(item.elapsedMs) || item.elapsedMs < 0 ||
      (i > 0 && item.elapsedMs < entries[i - 1].elapsedMs))) return false;
  const last = entries.at(-1);
  if (last.phase !== phase || last.elapsedMs > drop.elapsedMs) return false;
  if (phase !== 'INITIALIZING' && !entries.some(item => item.phase === 'LOADED')) return false;
  if (phase === 'DECODING' && !entries.some(item => item.phase === 'PREFILL')) return false;
  return true;
}

async function main() {
  const pressure = process.argv.includes('--android-pressure');
  if (process.argv.slice(2).some(arg => arg !== '--android-pressure')) throw new Error('Unknown argument');
  const mode = pressure ? 'android-pressure' : 'baseline';
  const serial = process.env.ANDROID_SERIAL;
  if (!/^[A-Za-z0-9._:-]+$/.test(serial || '')) throw new Error('Set ANDROID_SERIAL to the authorized S24');
  const adb = process.env.ADB || join(process.env.ANDROID_HOME || join(process.env.HOME, 'Library/Android/sdk'), 'platform-tools/adb');
  const pkg = 'dev.edgecompanion.app.secureqa';
  const run = (...args) => execFileSync(adb, ['-s', serial, ...args], {
    encoding: 'utf8', timeout: args.includes('instrument') ? 30000 : 5000 });
  mkdirSync('benchmarks/results', { recursive: true });
  const folder = mkdtempSync('benchmarks/results/native-supervision-');
  const summary = { schema: 1, nativeWorkload: true, sustainedWorkload: false, preflightMode: mode, cases: [], passed: false };
  const persist = () => writeFileSync(join(folder, 'summary.json'), JSON.stringify(summary, null, 2));
  writeFileSync(join(folder, 'app-head.txt'), execFileSync('git', ['rev-parse', 'HEAD']));
  writeFileSync(join(folder, 'working-tree-stat.txt'), execFileSync('git', ['diff', '--stat']));
  const hashes = Object.fromEntries([
    'androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk',
    'androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk',
    'shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/LiteRtLlmEngine.kt',
    'shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelAssets.kt',
    'scripts/verify-chat-budget.py',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/NativeSupervisionTest.kt',
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/DeviceSupervision.kt',
    'androidApp/src/testSupport/kotlin/dev/edgecompanion/app/support/SupervisionOwnership.kt',
    'scripts/probe-native-supervision.mjs',
  ].map(path => [path, createHash('sha256').update(readFileSync(path)).digest('hex')]));
  writeFileSync(join(folder, 'source-and-apk-sha256.json'), JSON.stringify(hashes, null, 2));
  console.log(`Bounded native supervision evidence: ${folder} (${mode})`);
  let activeRunId;
  let currentCase;
  try {
    const budget = JSON.parse(execFileSync(process.env.CHAT_BUDGET_PYTHON || 'build/token-verification/bin/python',
      ['scripts/verify-chat-budget.py'], { encoding: 'utf8', timeout: 60000 }));
    const fixture = budget.supervisionPrefill;
    if (budget.status !== 'passed' || fixture?.sha256 !== 'e769e953b92ff0677b861257131627d3558d06de671c645fbfe08bee7bf13f5c' ||
        fixture.contextTokens !== 2048 || fixture.outputReserve !== 512 || fixture.templateAllowance !== 64 ||
        !Number.isSafeInteger(fixture.inputTokens) || fixture.inputTokens + 64 + 512 > 2048)
      throw new Error('No verified bounded synthetic prefill fixture');
    writeFileSync(join(folder, 'budget.json'), JSON.stringify(budget, null, 2));
    for (const phase of ['INITIALIZING', 'PREFILL', 'DECODING', 'RECOVERY']) {
      const runId = randomUUID(); activeRunId = runId;
      currentCase = { phase, runId, status: 'preflight' };
      summary.cases.push(currentCase); persist();
      const directory = join(folder, phase.toLowerCase()); mkdirSync(directory);
      const policyPath = join(directory, 'memory-policy.json');
      execFileSync('bash', ['scripts/export-memory-policy.sh'], { encoding: 'utf8', timeout: 60000,
        env: { ...process.env, ADB: adb, MEMORY_POLICY_REPORT: policyPath, MEMORY_RUN_ID: runId } });
      const policy = readMemoryPolicy(policyPath); validatePreflightPolicy(policy, mode);
      if (budget.modelSha256 !== policy.modelSha256) throw new Error('Budget proof belongs to a different model');
      let baseline = JSON.parse(readFileSync(join(directory, 'memory-baseline.json'), 'utf8'));
      const samples = [];
      const snapshot = () => {
        if (pressure) baseline = readFreshAndroidPressure(run, policy, baseline.recordedAtMs);
        return { ...parseResources(run('shell', 'cat', '/proc/meminfo'), run('shell', 'dumpsys', 'thermalservice')),
          lowMemory: baseline.lowMemory, pressureThresholdBytes: baseline.pressureThresholdBytes,
          androidBaselineRecordedAtMs: baseline.recordedAtMs,
          ...(pressure ? { availableMemoryBytes: baseline.availableMemoryBytes } : {}) };
      };
      const blocked = await waitForResources(snapshot, sample => samples.push(sample), {
        policy, mode, seconds: 15, intervalMs: 1000 });
      writeFileSync(join(directory, 'preflight.json'), JSON.stringify(samples, null, 2));
      if (blocked) {
        Object.assign(currentCase, { status: 'blocked', reason: blocked });
        throw new Error(`Native ${phase} blocked: ${blocked}`);
      }
      let sequence = 0;
      const renew = () => renewSupervision({ adb, serial, runId, parent: process.ppid, sequence: ++sequence });
      renew();
      currentCase.status = 'running'; persist();
      const started = Date.now();
      const child = spawn(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
        'dev.edgecompanion.app.NativeSupervisionTest', '-e', 'nativeSupervision', 'true', '-e', 'nativePhase', phase,
        '-e', 'supervisedThermal', 'true', '-e', 'supervisionRunId', runId,
        `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`]);
      let output = ''; let ended = false; let childError;
      child.stdout.on('data', bytes => { output += bytes; });
      child.stderr.on('data', bytes => { output += bytes; });
      const completion = new Promise(resolve => {
        child.once('exit', () => { ended = true; resolve(); });
        child.once('error', error => { childError = error; ended = true; resolve(); });
      });
      const read = name => {
        try { return JSON.parse(run('exec-out', 'run-as', pkg, 'cat', `${supervisionDirectory(runId)}/${name}.json`)); }
        catch { throw new Error(`No valid current-run ${name} evidence; inspect instrumentation.txt`); }
      };
      let dropped; let lastRenew = Date.now(); let lastThermal = 0;
      let lossMonotonicMs;
      const thermals = [];
      try {
        while (!ended) {
          if (Date.now() - started > 180_000) throw new Error('Native probe exceeded 180-second host deadline');
          if (Date.now() - lastThermal >= 500) {
            const sample = parseResources(run('shell', 'cat', '/proc/meminfo'), run('shell', 'dumpsys', 'thermalservice'));
            lastThermal = Date.now(); thermals.push({ recordedAtMs: lastThermal, ...sample });
            if (sample.thermalStatus == null || sample.skinC == null || sample.thermalStatus >= policy.severeThermalStatus ||
                sample.skinC >= policy.maxSkinCelsius) throw new Error('Native thermal safety stop');
          }
          if (!dropped && Date.now() - lastRenew >= 1000) { renew(); lastRenew = Date.now(); }
          let observed;
          try { observed = read('native-phase'); } catch { }
          if (!dropped && phase !== 'RECOVERY' && observed?.runId === runId && observed.phase === phase) {
            const clock = Number(run('shell', 'cat', '/proc/uptime').trim().split(/\s+/)[0]);
            if (!Number.isFinite(clock)) throw new Error('Device clock unavailable');
            // Simulate unavailable supervision without disconnecting USB or affecting other runs.
            run('shell', 'run-as', pkg, 'rm', '-f', `${supervisionDirectory(runId)}/heartbeat.json`);
            lossMonotonicMs = performance.now();
            dropped = { runId, phase, elapsedMs: Math.floor(clock * 1000), hostAtMs: Date.now() };
            writeFileSync(join(directory, 'heartbeat-loss.json'), JSON.stringify(dropped, null, 2));
          }
          if (dropped && Date.now() - dropped.hostAtMs > 9000) throw new Error('QA did not terminate within the bounded stop window');
          await wait(25);
        }
        if (childError) throw childError;
        const supervision = read('status');
        writeFileSync(join(directory, 'supervision.json'), JSON.stringify(supervision, null, 2));
        if (phase === 'RECOVERY') {
          const recovery = read('native-recovery');
          writeFileSync(join(directory, 'native-recovery.json'), JSON.stringify(recovery, null, 2));
          if (!/^OK \(1 test\)\s*$/m.test(output) || !validSupervision(supervision, runId) || recovery.runId !== runId ||
              recovery.passed !== true || recovery.runtimeStatus !== 'UNLOADED') throw new Error('Fresh native recovery failed');
        } else {
          const loss = read('native-loss');
          writeFileSync(join(directory, 'native-loss.json'), JSON.stringify(loss, null, 2));
          let pidGone = false;
          try { pidGone = !run('shell', 'pidof', pkg).trim(); } catch (error) { if (error.status === 1) pidGone = true; else throw error; }
          const stop = { runId, phase, elapsedSinceLossMs: performance.now() - lossMonotonicMs, pidGone };
          writeFileSync(join(directory, 'stop-observation.json'), JSON.stringify(stop, null, 2));
          if (!validateNativeLoss({ phase, loss, supervision, drop: dropped, stop, runId, policy, pidGone }))
            throw new Error(`No correlated loss during actual ${phase}; a missed phase is not a pass`);
        }
        Object.assign(currentCase, { status: 'passed', elapsedMs: Date.now() - started,
          cleanupConfirmed: supervision.cleanupConfirmed });
        persist(); console.log(`${phase}: passed`);
      } finally {
        if (!ended) {
          stopSupervisedQa({ adb, serial, runId });
          // The device-side watchdog retains ownership and is the only process-stop authority.
          await Promise.race([completion, wait(8000)]);
          if (!ended) child.kill(); // Only the ADB client; not a package-wide stop.
          await Promise.race([completion, wait(2000)]);
        }
        writeFileSync(join(directory, 'instrumentation.txt'), output);
        writeFileSync(join(directory, 'thermal.json'), JSON.stringify(thermals, null, 2));
        for (const name of ['native-phase', 'native-loss', 'status']) {
          try { writeFileSync(join(directory, `${name}-final.json`), JSON.stringify(read(name), null, 2)); } catch { }
        }
      }
    }
    summary.passed = true;
  } catch (error) {
    summary.error = error.message; process.exitCode = 1;
    if (currentCase && currentCase.status !== 'blocked') Object.assign(currentCase, { status: 'failed', reason: error.message });
    if (activeRunId) stopSupervisedQa({ adb, serial, runId: activeRunId });
    console.error(error.message);
  } finally { persist(); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
