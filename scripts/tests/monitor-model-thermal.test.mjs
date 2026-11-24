import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { existsSync, mkdtempSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { setTimeout as wait } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';

const monitor = fileURLToPath(new URL('../monitor-model-thermal.mjs', import.meta.url));
const runId = 'synthetic-thermal';
const policy = {
  schema: 1, id: 'synthetic', calibrated: false, enforceAvailableMemoryFloor: false,
  modelSha256: '0'.repeat(64), runtimeVersion: 'synthetic', loadingFloorBytes: 1,
  generationFloorBytes: 1, minimumTotalBytes: 1, availableMemorySamples: 1,
  sampleIntervalMs: 1, severeThermalStatus: 3, maxSkinCelsius: 45, contextTokens: 1,
  outputTokens: 1, supervisedLoadReserveBytes: 0,
};

function startMonitor(t, mode) {
  const dir = mkdtempSync(join(tmpdir(), 'monitor-model-thermal-'));
  const adb = join(dir, 'synthetic-adb');
  const policyPath = join(dir, 'policy.json');
  const reports = join(dir, 'thermal');
  writeFileSync(policyPath, JSON.stringify(policy));
  writeFileSync(adb, `#!/usr/bin/env node
const { existsSync, readFileSync, renameSync, rmSync, writeFileSync } = require('node:fs');
const { join } = require('node:path');
const [selector, serial, command, ...args] = process.argv.slice(2);
if (selector !== '-s' || serial !== 'SYNTHETIC' || command !== 'shell') process.exit(64);
const mode = process.env.SYNTHETIC_MODE;
const dir = process.env.SYNTHETIC_DIR;
const reports = process.env.THERMAL_REPORT_DIR;
if (args.join(' ') === 'dumpsys thermalservice') {
  if (mode === 'intent-error') {
    renameSync(reports, join(dir, 'saved-thermal'));
    writeFileSync(reports, 'synthetic file in place of the report directory');
  }
  console.log('Thermal Status: 3');
  if (mode !== 'unavailable') {
    console.log('Current temperatures from HAL:');
    console.log('Temperature{mValue=45.1, mType=3, mName=SKIN, mStatus=3}');
  }
} else if (args.length === 1 && args[0].includes('/stop.tmp')) {
  if (mode === 'intent-error') {
    rmSync(reports);
    renameSync(join(dir, 'saved-thermal'), reports);
  }
  const marker = join(reports, 'safety-stop.json');
  writeFileSync(join(dir, 'at-dispatch.tmp'), existsSync(marker) ? readFileSync(marker) : 'null');
  renameSync(join(dir, 'at-dispatch.tmp'), join(dir, 'at-dispatch.json'));
  if (mode === 'blocked' || mode === 'intent-error') {
    const monitorPid = process.ppid;
    const timer = setInterval(() => {
      if (process.ppid !== monitorPid) process.exit(1);
      if (existsSync(join(dir, 'release'))) clearInterval(timer);
    }, 10);
  }
  if (mode === 'delivery-write-error') {
    renameSync(reports, join(dir, 'saved-thermal'));
    writeFileSync(reports, 'synthetic file in place of the report directory');
  }
} else process.exit(64);
`, { mode: 0o700 });

  const child = spawn(process.execPath, [monitor], { env: {
    ...process.env, ADB: adb, ANDROID_SERIAL: 'SYNTHETIC', QA_SUPERVISION_RUN_ID: runId,
    QA_SUPERVISOR_PID: String(process.pid), MEMORY_POLICY_REPORT: policyPath,
    THERMAL_REPORT_DIR: reports, SYNTHETIC_DIR: dir, SYNTHETIC_MODE: mode,
  }, stdio: ['ignore', 'ignore', 'pipe'] });
  let stderr = '';
  child.stderr.on('data', data => { stderr += data; });
  const finished = once(child, 'close');
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    try { await finished; } finally { rmSync(dir, { recursive: true, force: true }); }
  });
  const json = file => JSON.parse(readFileSync(join(dir, file), 'utf8'));
  return { dir, child, finished, json, stderr: () => stderr };
}

function assertFirstEvent(record, sample) {
  assert.equal(new Date(record.recordedAt).toISOString(), record.recordedAt);
  assert.deepEqual(record, { runId, recordedAt: record.recordedAt, ...sample, delivery: 'pending' });
}

const severe = { reason: 'thermal_limit', status: 3, skin: 45.1 };

async function waitForDispatch(dir, child) {
  const dispatch = join(dir, 'at-dispatch.json');
  const deadline = Date.now() + 15000;
  while (!existsSync(dispatch) && child.exitCode === null && Date.now() < deadline) await wait(20);
  assert.ok(existsSync(dispatch), 'stop request reached synthetic adb');
}

test('monitor retains the severe intent when sent TERM during a blocked stop request', async t => {
  const { dir, child, finished, json } = startMonitor(t, 'blocked');
  await waitForDispatch(dir, child);
  assert.ok(child.kill('SIGTERM'), 'TERM sent while the synchronous request was blocked');
  const recorded = json('thermal/safety-stop.json');
  assertFirstEvent(recorded, severe);
  assert.deepEqual(json('at-dispatch.json'), recorded);
  writeFileSync(join(dir, 'release'), '');
  assert.deepEqual(await finished, [2, null]);
  assert.deepEqual(json('thermal/safety-stop.json'), { ...recorded, delivery: 'requested', stopRequested: true });
});

test('monitor reports intent write failure and attempts the stop even with TERM during delivery', async t => {
  const { dir, child, finished, json, stderr } = startMonitor(t, 'intent-error');
  await waitForDispatch(dir, child);
  assert.ok(child.kill('SIGTERM'));
  writeFileSync(join(dir, 'release'), '');
  assert.deepEqual(await finished, [3, null]);
  assert.equal(json('at-dispatch.json'), null);
  assert.ok(!existsSync(join(dir, 'thermal/safety-stop.json')));
  assert.match(stderr(), /thermal-stop-intent-write-failed/);
});

test('monitor records unavailable SKIN before requesting the stop', async t => {
  const { finished, json } = startMonitor(t, 'unavailable');
  assert.deepEqual(await finished, [2, null]);
  const recorded = json('at-dispatch.json');
  assertFirstEvent(recorded, { reason: 'thermal_signal_unavailable', error: 'Error' });
  assert.deepEqual(json('thermal/safety-stop.json'), { ...recorded, delivery: 'requested', stopRequested: true });
});

test('monitor preserves the pending first event if writing the delivery outcome fails', async t => {
  const { dir, finished, json, stderr } = startMonitor(t, 'delivery-write-error');
  assert.deepEqual(await finished, [2, null]);
  assert.match(stderr(), /thermal-stop-delivery-write-failed/);
  rmSync(join(dir, 'thermal'));
  renameSync(join(dir, 'saved-thermal'), join(dir, 'thermal'));
  const recorded = json('thermal/safety-stop.json');
  assertFirstEvent(recorded, severe);
  assert.deepEqual(json('at-dispatch.json'), recorded);
});
