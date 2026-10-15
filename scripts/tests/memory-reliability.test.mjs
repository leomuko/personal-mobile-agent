import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, writeFileSync, existsSync, rmSync, mkdirSync, copyFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { summarizeMemoryRun } from '../analyze-memory-reliability.mjs';

const profile = { id: 'unit-test-only', loadingFloorBytes: 100 };
const runId = 'synthetic-run';
const exits = { runId, phase: 'after', capturedAtMs: 201, entries: [], lowMemoryKillReportSupported: true };
const run = { runId, finishedAtMs: 200, status: 'passed', policy: profile, backend: 'GPU', automaticFallback: false,
  requestedCycles: 3, completedCycles: 3, requestedSustainedMinutes: 20, completedSustainedMinutes: 20, startedAtMs: 100,
  observations: [100, 110, 105].map(postReleasePssBytes => ({ postReleasePssBytes })),
  telemetry: { failedSamples: 0, droppedSamples: 0, samples: ['BASELINE', 'LOADING', 'LOADED_IDLE', 'PREFILL', 'DECODING', 'CANCELLING', 'POST_RELEASE'].map(stage =>
    ({ stage, appPssBytes: 100, appRssBytes: 200, pressureThresholdBytes: 10, policyId: profile.id, backend: 'GPU' })) } };
test('workload completion does not claim calibration or diagnose leaks from memory noise', () => {
  const summary = summarizeMemoryRun(run, exits, { loadingFloorBytes: 100, id: 'unit-test-only' }, runId);
  assert.equal(summary.workloadPassed, true);
  assert.equal(summary.acceptanceComplete, false);
  assert.equal(summary.strictlyIncreasingPostReleasePss, false);
  assert.equal(summarizeMemoryRun(run, {}, profile, runId).workloadPassed, false);
});
test('incomplete samples, mismatched profiles and unknown SIGKILL prevent a clean result', () => {
  const incomplete = summarizeMemoryRun({ ...run, completedCycles: 1, telemetry: { ...run.telemetry, failedSamples: 1 } },
    { ...exits, entries: [{ timestampMs: 101, classification: 'sigkill-unclassified' }] }, { id: 'wrong' }, runId);
  assert.equal(incomplete.workloadPassed, false);
  assert.ok(incomplete.issues.includes('policy-mismatch'));
  assert.ok(incomplete.issues.includes('process-exits-require-review'));
  assert.ok(incomplete.issues.includes('incomplete-telemetry'));
});
test('historical exits before this run are not attributed to the current workload', () => {
  assert.equal(summarizeMemoryRun(run, { ...exits, entries: [{ timestampMs: 99, classification: 'confirmed-low-memory' }] }, profile, runId).workloadPassed, true);
});
test('exit evidence must be captured after completion and correlated to this supervised run', () => {
  for (const invalid of [
    { ...exits, capturedAtMs: 99 }, { ...exits, capturedAtMs: 150 },
    { ...exits, capturedAtMs: undefined }, { ...exits, runId: 'older-run' },
    { ...exits, phase: 'before' }, { ...exits, runId: undefined },
  ]) assert.equal(summarizeMemoryRun(run, invalid, profile, runId).workloadPassed, false);
  assert.equal(summarizeMemoryRun({ ...run, finishedAtMs: undefined }, exits, profile, runId).workloadPassed, false);
  assert.equal(summarizeMemoryRun({ ...run, finishedAtMs: 99 }, exits, profile, runId).workloadPassed, false);
  assert.equal(summarizeMemoryRun({ ...run, runId: 'older-run' }, exits, profile, runId).workloadPassed, false);
  assert.equal(summarizeMemoryRun(run, exits, profile).workloadPassed, false);
});
test('final probe rejects old files on command failure, JUnit failure, or stale success', () => {
  const folder = mkdtempSync(join(tmpdir(), 'memory-exit-probe-'));
  try {
    const adb = join(folder, 'adb');
    const calls = join(folder, 'calls');
    const old = JSON.stringify({ ...exits, runId: 'older-run', capturedAtMs: 50 });
    writeFileSync(adb, `#!${process.execPath}
const fs = require('node:fs');
const args = process.argv.slice(2);
fs.appendFileSync(process.env.CALLS, JSON.stringify(args) + '\\n');
if (args.includes('instrument')) {
  if (process.env.MODE === 'command-failure') process.exit(1);
  console.log(process.env.MODE === 'junit-failure' ? 'FAILURES!!!' : 'OK (1 test)');
} else if (args.includes('exec-out')) {
  process.stdout.write(process.env.REMOTE_EXITS);
} else process.exit(2);
`, { mode: 0o700 });
    for (const mode of ['command-failure', 'junit-failure', 'stale-success', 'success']) {
      writeFileSync(calls, '');
      writeFileSync(join(folder, 'exits-after.json'), old);
      const result = spawnSync('bash', ['scripts/collect-memory-exits.sh', folder], {
        encoding: 'utf8', env: { ...process.env, ADB: adb, ANDROID_SERIAL: 'synthetic-device',
          MEMORY_RUN_ID: runId, CALLS: calls, MODE: mode,
          REMOTE_EXITS: mode === 'success' ? JSON.stringify(exits) : old },
      });
      if (mode === 'success') {
        assert.equal(result.status, 0, result.stderr);
        assert.deepEqual(JSON.parse(readFileSync(join(folder, 'exits-after.json'), 'utf8')), exits);
      } else {
        assert.notEqual(result.status, 0);
        assert.equal(existsSync(join(folder, 'exits-after.json')), false, mode);
        if (mode !== 'stale-success') assert.equal(readFileSync(calls, 'utf8').includes('exec-out'), false);
      }
    }
  } finally { rmSync(folder, { recursive: true, force: true }); }
});
test('supervised wrapper fails a passed workload when final probe fails with preflight evidence still present', () => {
  const root = mkdtempSync(join(tmpdir(), 'memory-wrapper-'));
  try {
    mkdirSync(join(root, 'scripts'));
    mkdirSync(join(root, 'bin'));
    for (const script of ['verify-conversation-native.sh', 'export-memory-policy.sh', 'collect-memory-exits.sh',
      'analyze-memory-reliability.mjs', 'memory-policy.mjs', 'chat-resource-preflight.mjs', 'monitor-model-thermal.mjs'])
      copyFileSync(new URL(`../${script}`, import.meta.url), join(root, 'scripts', script));
    const policy = { schema: 1, id: 'synthetic-wrapper', calibrated: false,
      enforceAvailableMemoryFloor: true,
      loadingFloorBytes: 3 * 1024 ** 3, generationFloorBytes: 3 * 1024 ** 3,
      supervisedLoadReserveBytes: 2.5 * 1024 ** 3, minimumTotalBytes: 7 * 1024 ** 3,
      availableMemorySamples: 3, sampleIntervalMs: 1000, severeThermalStatus: 3, maxSkinCelsius: 45,
      contextTokens: 2048, outputTokens: 512, modelSha256: 'a'.repeat(64), runtimeVersion: 'synthetic' };
    const remote = join(root, 'remote.json');
    writeFileSync(join(root, 'gradle'), `#!/bin/sh
mkdir -p androidApp/build/outputs/apk/secureQa
printf 'synthetic apk' > androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk
`, { mode: 0o700 });
    writeFileSync(join(root, 'bin', 'git'), '#!/bin/sh\nif [ "$1" = rev-parse ]; then printf "synthetic-head\\n"; fi\n', { mode: 0o700 });
    writeFileSync(join(root, 'adb'), `#!${process.execPath}
const fs = require('node:fs');
const args = process.argv.slice(2);
const policy = JSON.parse(process.env.SYNTHETIC_POLICY);
const value = key => args[args.indexOf(key) + 1];
if (args.includes('install')) process.exit(0);
if (args.includes('instrument')) {
  const name = value('class');
  if (name.includes('MemoryPolicyProbeTest')) {
    fs.writeFileSync(process.env.REMOTE, JSON.stringify({ runId: value('memoryRunId'), phase: 'before',
      capturedAtMs: Date.now(), entries: [], lowMemoryKillReportSupported: true }));
    console.log('OK (2 tests)');
  } else if (name.includes('MemoryReliabilityTest')) {
    const report = JSON.parse(process.env.SYNTHETIC_RUN);
    report.runId = value('memoryRunId'); report.policy = policy;
    fs.writeFileSync(process.env.NATIVE_REPORT, JSON.stringify(report));
    console.log('OK (1 test)');
  } else console.log('FAILURES!!!');
} else if (args.includes('exec-out')) {
  const file = args.at(-1);
  if (file.endsWith('memory-policy.json')) console.log(JSON.stringify(policy));
  else if (file.endsWith('memory-baseline.json')) console.log(JSON.stringify({ policyId: policy.id,
    lowMemory: false, pressureThresholdBytes: 500, recordedAtMs: Date.now() }));
  else if (file.endsWith('memory-exits.json')) process.stdout.write(fs.readFileSync(process.env.REMOTE));
  else if (file.endsWith('memory-reliability.json')) process.stdout.write(fs.readFileSync(process.env.NATIVE_REPORT));
  else console.log('{}');
} else if (args.includes('/proc/meminfo')) console.log('MemTotal: 12582912 kB\\nMemAvailable: 6291456 kB');
else if (args.includes('thermalservice')) console.log('Thermal Status: 0\\nCurrent temperatures from HAL:\\nTemperature{mValue=37, mType=3, mName=SKIN}');
else if (!args.includes('rm')) process.exit(2);
`, { mode: 0o700 });
    const result = spawnSync('bash', ['scripts/verify-conversation-native.sh'], {
      cwd: root, encoding: 'utf8', timeout: 25000,
      env: { ...process.env, PATH: `${join(root, 'bin')}:${process.env.PATH}`, ADB: join(root, 'adb'),
        GRADLE: join(root, 'gradle'), ANDROID_SERIAL: 'synthetic-device', MEMORY_RELIABILITY: 'true',
        SYNTHETIC_POLICY: JSON.stringify(policy), SYNTHETIC_RUN: JSON.stringify(run),
        NATIVE_REPORT: join(root, 'native.json'), REMOTE: remote },
    });
    assert.equal(result.status, 1, `${result.stdout}\n${result.stderr}`);
    const folder = /Native conversation reports: (.+)/.exec(result.stdout)?.[1];
    assert.ok(folder);
    const status = JSON.parse(readFileSync(join(folder, 'result.json'), 'utf8'));
    assert.equal(status.status, 'failed'); assert.equal(status.stage, 'complete');
    assert.equal(JSON.parse(readFileSync(join(folder, 'memory-reliability.json'), 'utf8')).status, 'passed');
    assert.equal(JSON.parse(readFileSync(remote, 'utf8')).phase, 'before');
    assert.equal(existsSync(join(folder, 'exits-after.json')), false);
    assert.match(readFileSync(join(folder, 'exit-probe.txt'), 'utf8'), /FAILURES!!!/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
