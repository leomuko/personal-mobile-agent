import { execFileSync, spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID } from 'node:crypto';
import { renewSupervision, stopSupervisedQa, supervisionDirectory } from './qa-supervision.mjs';
import { validSupervision } from './verify-qa-supervision.mjs';
import { setTimeout as wait } from 'node:timers/promises';

const serial = process.env.ANDROID_SERIAL;
if (!/^[A-Za-z0-9._:-]+$/.test(serial || '')) throw new Error('Set ANDROID_SERIAL to the authorized test device');
const adb = process.env.ADB || join(process.env.ANDROID_HOME || join(process.env.HOME, 'Library/Android/sdk'), 'platform-tools/adb');
mkdirSync('benchmarks/results', { recursive: true });
const folder = mkdtempSync('benchmarks/results/supervision-');
const summary = { schema: 1, nativeWorkload: false, cases: [], passed: false };
const execute = args => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8', timeout: 10000 });
console.log(`Synthetic supervision proof: ${folder}`);
let activeRunId;
try {
  for (const mode of ['healthy', 'handoff', 'stop-request', 'loss', 'hung-cleanup', 'recovery']) {
    const runId = randomUUID(); let sequence = 0;
    const priorRunId = randomUUID();
    activeRunId = runId;
    const renew = () => renewSupervision({ adb, serial, runId, parent: process.ppid, sequence: ++sequence });
    renew();
    if (mode === 'handoff') renewSupervision({ adb, serial, runId: priorRunId, parent: process.ppid, sequence: 1 });
    const started = Date.now();
    const child = spawn(adb, ['-s', serial, 'shell', 'am', 'instrument', '-w', '-e', 'class',
      'dev.edgecompanion.app.SupervisionProbeTest', '-e', 'supervisionProbe', mode,
      '-e', 'supervisionPriorRunId', priorRunId,
      '-e', 'supervisionRunId', runId, 'dev.edgecompanion.app.secureqa.test/androidx.test.runner.AndroidJUnitRunner']);
    let output = ''; let heartbeatFailure;
    child.stdout.on('data', bytes => { output += bytes; });
    child.stderr.on('data', bytes => { output += bytes; });
    // Loss cases intentionally stop renewing; neither USB nor device-wide settings are changed.
    const timer = ['healthy', 'handoff', 'stop-request', 'recovery'].includes(mode) ? setInterval(() => {
      try { renew(); } catch (error) { heartbeatFailure = error; }
    }, 3000) : null;
    let timeout;
    try {
      const completion = new Promise((resolve, reject) => {
        child.once('error', reject);
        child.once('exit', resolve);
        timeout = setTimeout(() => { child.kill(); reject(new Error('Synthetic probe did not terminate within 65 seconds')); }, 65000);
      });
      const intervention = (async () => {
        if (!['handoff', 'stop-request'].includes(mode)) return;
        const deadline = Date.now() + 15_000;
        while (true) {
          try {
            const state = JSON.parse(execute(['exec-out', 'run-as', 'dev.edgecompanion.app.secureqa', 'cat', `${supervisionDirectory(runId)}/status.json`]));
            if (state.runId === runId && state.status === 'active') break;
          } catch { }
          if (Date.now() >= deadline) throw new Error('No active run for the ownership intervention');
          await wait(200);
        }
        if (mode === 'handoff') renewSupervision({ adb, serial, runId: priorRunId, parent: process.ppid, sequence: 2 });
        const stop = stopSupervisedQa({ adb, serial, runId: mode === 'handoff' ? priorRunId : runId });
        if (!stop.stopRequested) throw new Error('Could not publish the scoped stop');
      })();
      await Promise.all([completion, intervention]);
      if (heartbeatFailure) throw heartbeatFailure;
      const report = JSON.parse(execute(['exec-out', 'run-as', 'dev.edgecompanion.app.secureqa', 'cat', `${supervisionDirectory(runId)}/status.json`]));
      writeFileSync(join(folder, `${mode}.json`), JSON.stringify(report, null, 2));
      if (['healthy', 'handoff', 'recovery'].includes(mode)) {
        if (!/^OK \(1 test\)$/m.test(output) || !validSupervision(report, runId)) throw new Error('Healthy supervision did not complete cleanly');
      } else {
        if (/^OK \(1 test\)$/m.test(output) || report.runId !== runId || report.reason !== (mode === 'stop-request' ? 'STOP_REQUESTED' : 'EXPIRED') ||
            !['lost', 'terminated'].includes(report.status) || (mode !== 'hung-cleanup' && report.cleanupConfirmed !== true))
          throw new Error(`No correlated stop evidence for ${mode}`);
        try {
          if (execute(['shell', 'pidof', 'dev.edgecompanion.app.secureqa']).trim()) throw new Error('QA process survived lease loss');
        } catch (error) { if (error.status !== 1) throw error; }
      }
      summary.cases.push({ mode, runId, passed: true, observedMs: Date.now() - started, status: report.status });
      console.log(`${mode}: passed`);
    } finally {
      if (timer) clearInterval(timer);
      clearTimeout(timeout);
      writeFileSync(join(folder, `${mode}.txt`), output);
    }
  }
  summary.passed = true;
} catch (error) {
  summary.error = error.message;
  // Never kill a package that could have changed owners while this host was disconnected.
  if (activeRunId) stopSupervisedQa({ adb, serial, runId: activeRunId });
  console.error(error.message);
  process.exitCode = 1;
} finally { writeFileSync(join(folder, 'summary.json'), JSON.stringify(summary, null, 2)); }
