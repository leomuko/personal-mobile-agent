import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { parseResources, blockedReason as evaluate, waitForResources, readFreshAndroidPressure, validatePreflightPolicy } from '../chat-resource-preflight.mjs';
import { validateMemoryPolicy } from '../memory-policy.mjs';

const policy = validateMemoryPolicy({ schema: 1, id: 'memory-baseline-v1', calibrated: false,
  enforceAvailableMemoryFloor: true,
  loadingFloorBytes: 3 * 1024 ** 3, generationFloorBytes: 3 * 1024 ** 3,
  supervisedLoadReserveBytes: 2.5 * 1024 ** 3, minimumTotalBytes: 7 * 1024 ** 3,
  availableMemorySamples: 3, sampleIntervalMs: 1000, severeThermalStatus: 3, maxSkinCelsius: 45,
  contextTokens: 2048, outputTokens: 512, modelSha256: 'a'.repeat(64), runtimeVersion: 'test-fixture' });
const MEMORY_FLOOR_BYTES = policy.loadingFloorBytes;
const LOAD_RESERVE_BYTES = policy.supervisedLoadReserveBytes;
const blockedReason = sample => evaluate(sample, policy);

const healthy = { totalMemoryBytes: 12 * 1024 ** 3, availableMemoryBytes: 6 * 1024 ** 3, thermalStatus: 0, skinC: 37 };
test('resource parsing fails closed and preserves the 3 GiB floor', () => {
  const sample = parseResources('MemTotal: 12582912 kB\nMemAvailable: 6291456 kB\n',
    'Thermal Status: 0\nCurrent temperatures from HAL:\nTemperature{mValue=37, mType=3, mName=SKIN}');
  assert.deepEqual(sample, healthy);
  assert.equal(blockedReason(sample), null);
  assert.equal(blockedReason({ ...sample, availableMemoryBytes: 3 * 1024 ** 3 - 1 }), 'LOW_MEMORY');
  assert.equal(blockedReason({ ...sample, availableMemoryBytes: MEMORY_FLOOR_BYTES }), 'LOAD_HEADROOM');
  assert.equal(blockedReason({ ...sample, availableMemoryBytes: MEMORY_FLOOR_BYTES + LOAD_RESERVE_BYTES }), null);
  assert.equal(blockedReason(parseResources('', '')), 'RESOURCE_UNKNOWN');
  assert.equal(blockedReason({ ...sample, thermalStatus: 3 }), 'THERMAL');
  assert.equal(blockedReason({ ...sample, skinC: 45 }), 'THERMAL');
  assert.equal(blockedReason({ ...sample, totalMemoryBytes: 6 * 1024 ** 3 }), 'UNSUPPORTED_DEVICE');
});
test('bounded waiting requires three consecutive samples without rerunning inference', async () => {
  let time = 0;
  let count = 0;
  const records = [];
  const reason = await waitForResources(() => ++count === 2 ? { ...healthy, availableMemoryBytes: 1 } : healthy,
    sample => records.push(sample), { policy, seconds: 30, now: () => time, sleep: async ms => { time += ms; } });
  assert.equal(reason, null);
  assert.equal(count, 5);
  assert.equal(records[1].reason, 'LOW_MEMORY');
  assert.equal(records.at(-1).stableSamples, 3);
});
test('low memory times out; unknown or severe thermal blocks immediately', async () => {
  for (const [sample, expected, calls] of [
    [{ ...healthy, availableMemoryBytes: 1 }, 'LOW_MEMORY', 3],
    [{ ...healthy, availableMemoryBytes: 4 * 1024 ** 3 }, 'LOAD_HEADROOM', 3],
    [{ ...healthy, thermalStatus: null }, 'RESOURCE_UNKNOWN', 1],
    [{ ...healthy, thermalStatus: 3 }, 'THERMAL', 1],
    [{ ...healthy, lowMemory: true }, 'LOW_MEMORY', 1],
  ]) {
    let time = 0; let count = 0;
    const reason = await waitForResources(() => { count++; return sample; }, () => {},
      { policy, seconds: 10, now: () => time, sleep: async ms => { time += ms; } });
    assert.equal(reason, expected); assert.equal(count, calls);
  }
});
test('profile identity and loading rules come from the exported profile, not host constants', () => {
  assert.throws(() => validateMemoryPolicy({ ...policy, loadingFloorBytes: undefined }));
  assert.throws(() => validateMemoryPolicy({ ...policy, schema: 2 }));
  const changed = validateMemoryPolicy({ ...policy, id: 'unit-test-only', supervisedLoadReserveBytes: 0 });
  assert.equal(evaluate({ ...healthy, availableMemoryBytes: MEMORY_FLOOR_BYTES }, changed), null);
  assert.equal(blockedReason({ ...healthy, availableMemoryBytes: MEMORY_FLOOR_BYTES }), 'LOAD_HEADROOM');
});
test('explicit Android-pressure mode skips only host RAM floors and requires a boolean OS signal', () => {
  const lowAvailable = { ...healthy, availableMemoryBytes: 2 * 1024 ** 3, lowMemory: false };
  assert.equal(evaluate(lowAvailable, policy), 'LOW_MEMORY');
  assert.equal(evaluate(lowAvailable, policy, 'android-pressure'), null);
  assert.equal(evaluate({ ...lowAvailable, availableMemoryBytes: 4 * 1024 ** 3 }, policy, 'android-pressure'), null);
  assert.equal(evaluate({ ...lowAvailable, lowMemory: true }, policy, 'android-pressure'), 'LOW_MEMORY');
  for (const lowMemory of [undefined, null, 'false'])
    assert.equal(evaluate({ ...lowAvailable, lowMemory }, policy, 'android-pressure'), 'RESOURCE_UNKNOWN');
  assert.equal(evaluate({ ...lowAvailable, thermalStatus: 3 }, policy, 'android-pressure'), 'THERMAL');
  assert.equal(evaluate({ ...lowAvailable, skinC: 45 }, policy, 'android-pressure'), 'THERMAL');
  assert.equal(evaluate({ ...lowAvailable, thermalStatus: null }, policy, 'android-pressure'), 'RESOURCE_UNKNOWN');
  assert.equal(evaluate({ ...lowAvailable, totalMemoryBytes: 6 * 1024 ** 3 }, policy, 'android-pressure'), 'UNSUPPORTED_DEVICE');
  assert.throws(() => evaluate(lowAvailable, policy, 'typo'));
});
test('preflight refuses a different installed app admission mode', () => {
  validatePreflightPolicy(policy, 'baseline');
  const experimental = validateMemoryPolicy({ ...policy, id: 'memory-android-pressure-test-v1', enforceAvailableMemoryFloor: false });
  validatePreflightPolicy(experimental, 'android-pressure');
  assert.throws(() => validatePreflightPolicy(policy, 'android-pressure'), /does not match/);
  assert.throws(() => validatePreflightPolicy(experimental, 'baseline'), /does not match/);
  assert.throws(() => validatePreflightPolicy(policy, 'typo'), /does not match/);
  assert.throws(() => validateMemoryPolicy({ ...policy, enforceAvailableMemoryFloor: undefined }));
});
test('Android-pressure mode still needs three healthy samples and stops on OS pressure immediately', async () => {
  for (const pressureAt of [Infinity, 2]) {
    let time = 0; let calls = 0;
    const records = [];
    const reason = await waitForResources(() => ({ ...healthy, availableMemoryBytes: 2 * 1024 ** 3,
      lowMemory: ++calls >= pressureAt }), sample => records.push(sample), {
      policy, mode: 'android-pressure', now: () => time, sleep: async ms => { time += ms; },
    });
    assert.equal(reason, pressureAt === Infinity ? null : 'LOW_MEMORY');
    assert.equal(calls, pressureAt === Infinity ? 3 : 2);
    assert.equal(records[0].preflightMode, 'android-pressure');
    assert.equal(records[0].requiredAvailableMemoryBytes, null);
    assert.equal(records[0].baselineRequiredAvailableMemoryBytes, MEMORY_FLOOR_BYTES + LOAD_RESERVE_BYTES);
  }
});
test('pressure override refreshes Android evidence and rejects failed, stale, or mismatched probes', () => {
  const baseline = { policyId: policy.id, lowMemory: false, pressureThresholdBytes: 500,
    availableMemoryBytes: 2 * 1024 ** 3, recordedAtMs: 101 };
  const calls = [];
  const run = (...args) => {
    calls.push(args);
    return args.includes('instrument') ? 'OK (1 test)\n' : JSON.stringify(baseline);
  };
  assert.deepEqual(readFreshAndroidPressure(run, policy, 100), baseline);
  assert.ok(calls[0].includes('dev.edgecompanion.app.MemoryPolicyProbeTest'));
  assert.equal(calls[1][0], 'exec-out');
  assert.throws(() => readFreshAndroidPressure(run, policy, 101), /stale/);
  assert.throws(() => readFreshAndroidPressure(run, policy, undefined), /timestamp/);
  for (const mutation of [{ policyId: 'old-policy' }, { lowMemory: 'false' }, { pressureThresholdBytes: null }, { recordedAtMs: null }]) {
    assert.throws(() => readFreshAndroidPressure((...args) => args.includes('instrument') ?
      'OK (1 test)\n' : JSON.stringify({ ...baseline, ...mutation }), policy, 100));
  }
  for (const output of ['FAILURES!!!\n', 'OK (0 tests)\n', 'OK (1 test)\nINSTRUMENTATION_FAILED']) {
    let count = 0;
    assert.throws(() => readFreshAndroidPressure(() => { count++; return output; }, policy, 100), /did not pass/);
    assert.equal(count, 1);
  }
  assert.throws(() => readFreshAndroidPressure(() => { throw new Error('ADB unavailable'); }, policy, 100), /ADB unavailable/);
});
test('override is explicit, scoped to the memory workload and recorded even on build failure', () => {
  const temp = mkdtempSync(join(tmpdir(), 'memory-pressure-mode-'));
  try {
    const gradle = join(temp, 'gradle');
    const gradleArgs = join(temp, 'gradle-args');
    writeFileSync(gradle, '#!/bin/sh\nprintf "%s\\n" "$@" > "$GRADLE_ARGS"\nexit 42\n', { mode: 0o700 });
    const env = { ...process.env, ANDROID_SERIAL: 'synthetic-no-device', GRADLE: gradle, GRADLE_ARGS: gradleArgs };
    const result = spawnSync('bash', ['scripts/verify-memory-reliability.sh', '--android-pressure'], { env, encoding: 'utf8' });
    assert.equal(result.status, 42, result.stderr);
    assert.match(readFileSync(gradleArgs, 'utf8'), /-PandroidPressureMemoryTest=true/);
    const folder = /Native conversation reports: (.+)/.exec(result.stdout)?.[1];
    assert.ok(folder);
    assert.equal(JSON.parse(readFileSync(join(folder, 'result.json'), 'utf8')).preflightMode, 'android-pressure');
    assert.equal(JSON.parse(readFileSync(join(folder, 'run-context.json'), 'utf8')).preflightMode, 'android-pressure');
    const baseline = spawnSync('bash', ['scripts/verify-memory-reliability.sh'], {
      env: { ...env, MEMORY_PREFLIGHT_MODE: 'baseline' }, encoding: 'utf8',
    });
    assert.equal(baseline.status, 42, baseline.stderr);
    assert.match(readFileSync(gradleArgs, 'utf8'), /-PandroidPressureMemoryTest=false/);
    const chat = spawnSync('bash', ['scripts/verify-conversation-native.sh'], {
      env: { ...env, MEMORY_RELIABILITY: 'false', MEMORY_PREFLIGHT_MODE: 'android-pressure' }, encoding: 'utf8',
    });
    assert.equal(chat.status, 2);
    assert.match(chat.stderr, /restricted/);
    const typo = spawnSync('bash', ['scripts/verify-memory-reliability.sh', '--typo'], { env, encoding: 'utf8' });
    assert.equal(typo.status, 2);
    assert.match(typo.stderr, /Usage:/);
  } finally { rmSync(temp, { recursive: true, force: true }); }
});
test('wrapper retains build failure logs and final stage even before instrumentation', () => {
  const temp = mkdtempSync(join(tmpdir(), 'chat-wrapper-test-'));
  try {
    const gradle = join(temp, 'gradle');
    writeFileSync(gradle, '#!/bin/sh\nprintf "Synthetic build failure\\n" >&2\nexit 42\n', { mode: 0o700 });
    const result = spawnSync('bash', ['scripts/verify-conversation-native.sh'], {
      env: { ...process.env, ANDROID_SERIAL: 'synthetic-no-device', GRADLE: gradle }, encoding: 'utf8', timeout: 10000 });
    assert.equal(result.status, 42);
    const folder = /Native conversation reports: (.+)/.exec(result.stdout)?.[1];
    assert.ok(folder);
    const report = JSON.parse(readFileSync(join(folder, 'result.json')));
    assert.equal(report.status, 'failed'); assert.equal(report.stage, 'build'); assert.equal(report.exitCode, 42);
    assert.match(readFileSync(join(folder, 'build.txt'), 'utf8'), /Synthetic build failure/);
  } finally { rmSync(temp, { recursive: true, force: true }); }
});
