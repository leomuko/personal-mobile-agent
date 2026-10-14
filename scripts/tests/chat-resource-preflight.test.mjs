import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { spawnSync } from 'node:child_process';
import { parseResources, blockedReason, waitForResources, MEMORY_FLOOR_BYTES, LOAD_RESERVE_BYTES } from '../chat-resource-preflight.mjs';

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
    sample => records.push(sample), { seconds: 30, now: () => time, sleep: async ms => { time += ms; } });
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
  ]) {
    let time = 0; let count = 0;
    const reason = await waitForResources(() => { count++; return sample; }, () => {},
      { seconds: 10, now: () => time, sleep: async ms => { time += ms; } });
    assert.equal(reason, expected); assert.equal(count, calls);
  }
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
