#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { appendFileSync, readFileSync } from 'node:fs';
import { readMemoryPolicy } from './memory-policy.mjs';
import { resolve, dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as wait } from 'node:timers/promises';

export function parseResources(memory, thermal) {
  const kib = name => {
    const match = new RegExp(`^${name}:\\s+(\\d+)\\s+kB$`, 'm').exec(memory);
    return match ? Number(match[1]) * 1024 : null;
  };
  const status = /Thermal Status:\s*(\d+)/.exec(thermal);
  const hal = thermal.split('Current temperatures from HAL:')[1] || '';
  const skin = /Temperature\{mValue=([\d.]+), mType=3, mName=SKIN/.exec(hal);
  return { totalMemoryBytes: kib('MemTotal'), availableMemoryBytes: kib('MemAvailable'),
    thermalStatus: status ? Number(status[1]) : null, skinC: skin ? Number(skin[1]) : null };
}

export function blockedReason(sample, policy, mode = 'baseline') {
  if (!['baseline', 'android-pressure'].includes(mode)) throw new Error('Unknown memory preflight mode');
  if (['totalMemoryBytes', 'availableMemoryBytes', 'thermalStatus', 'skinC'].some(key =>
    sample[key] == null || !Number.isFinite(sample[key]))) return 'RESOURCE_UNKNOWN';
  if (sample.totalMemoryBytes <= 0 || sample.availableMemoryBytes < 0 || sample.thermalStatus < 0 || sample.skinC < 0)
    return 'RESOURCE_UNKNOWN';
  if (sample.thermalStatus >= policy.severeThermalStatus || sample.skinC >= policy.maxSkinCelsius) return 'THERMAL';
  if (sample.totalMemoryBytes < policy.minimumTotalBytes) return 'UNSUPPORTED_DEVICE';
  if (mode === 'android-pressure') {
    if (typeof sample.lowMemory !== 'boolean') return 'RESOURCE_UNKNOWN';
    return sample.lowMemory ? 'LOW_MEMORY' : null;
  }
  if (sample.lowMemory === true || sample.availableMemoryBytes < policy.loadingFloorBytes) return 'LOW_MEMORY';
  if (sample.availableMemoryBytes < policy.loadingFloorBytes + policy.supervisedLoadReserveBytes) return 'LOAD_HEADROOM';
  return null;
}

export function validatePreflightPolicy(policy, mode) {
  if (!['baseline', 'android-pressure'].includes(mode) ||
      policy.enforceAvailableMemoryFloor !== (mode === 'baseline'))
    throw new Error('Installed app memory policy does not match the requested preflight mode');
}

export async function waitForResources(snapshot, record, { seconds = 60, intervalMs = 5000,
  now = Date.now, sleep = wait, policy, mode = 'baseline' } = {}) {
  if (!policy) throw new Error('Exported memory policy required');
  const deadline = now() + seconds * 1000;
  let stable = 0;
  while (true) {
    const sample = snapshot();
    const reason = blockedReason(sample, policy, mode);
    stable = reason == null ? stable + 1 : 0;
    record({ recordedAt: new Date(now()).toISOString(), ...sample, reason, stableSamples: stable,
      policyId: policy.id, preflightMode: mode,
      requiredAvailableMemoryBytes: mode === 'baseline' ? policy.loadingFloorBytes + policy.supervisedLoadReserveBytes : null,
      baselineRequiredAvailableMemoryBytes: policy.loadingFloorBytes + policy.supervisedLoadReserveBytes });
    if (sample.lowMemory === true) return reason;
    if (reason && !['LOW_MEMORY', 'LOAD_HEADROOM'].includes(reason)) return reason;
    if (stable >= 3) return null;
    if (now() >= deadline) return reason || 'RESOURCE_UNSTABLE';
    await sleep(Math.min(intervalMs, deadline - now()));
  }
}

export function readFreshAndroidPressure(run, policy, previousRecordedAtMs) {
  if (!Number.isSafeInteger(previousRecordedAtMs) || previousRecordedAtMs <= 0)
    throw new Error('Previous Android pressure snapshot has no valid timestamp');
  const output = run('shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
    'dev.edgecompanion.app.MemoryPolicyProbeTest',
    'dev.edgecompanion.app.secureqa.test/androidx.test.runner.AndroidJUnitRunner');
  if (/FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_ABORTED|INSTRUMENTATION_STATUS_CODE: -[1-4]/.test(output) ||
      !/^OK \(1 test\)[\t ]*\r?$/m.test(output)) throw new Error('Fresh Android pressure probe did not pass');
  const snapshot = JSON.parse(run('exec-out', 'run-as', 'dev.edgecompanion.app.secureqa', 'cat', 'no_backup/memory-baseline.json'));
  if (snapshot.policyId !== policy.id || typeof snapshot.lowMemory !== 'boolean' ||
      !Number.isSafeInteger(snapshot.pressureThresholdBytes) || snapshot.pressureThresholdBytes <= 0 ||
      !Number.isSafeInteger(snapshot.recordedAtMs) || snapshot.recordedAtMs <= previousRecordedAtMs ||
      !Number.isSafeInteger(snapshot.availableMemoryBytes) || snapshot.availableMemoryBytes < 0)
    throw new Error('Missing, stale, or mismatched Android pressure snapshot');
  return snapshot;
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const serial = process.env.ANDROID_SERIAL;
    if (!serial || !/^[a-zA-Z0-9._:-]+$/.test(serial)) throw new Error('Invalid ANDROID_SERIAL');
    const seconds = Number(process.env.CHAT_PREFLIGHT_SECONDS || 60);
    if (!Number.isInteger(seconds) || seconds < 10 || seconds > 300) throw new Error('CHAT_PREFLIGHT_SECONDS must be 10..300');
    const adb = process.env.ADB;
    const mode = process.env.MEMORY_PREFLIGHT_MODE || 'baseline';
    if (!['baseline', 'android-pressure'].includes(mode)) throw new Error('Unknown memory preflight mode');
    if (mode === 'android-pressure' && process.env.MEMORY_RELIABILITY !== 'true')
      throw new Error('Android-pressure preflight requires the memory-reliability workload');
    const policy = readMemoryPolicy();
    validatePreflightPolicy(policy, mode);
    const baseline = JSON.parse(readFileSync(join(dirname(process.env.MEMORY_POLICY_REPORT), 'memory-baseline.json'), 'utf8'));
    if (baseline.policyId !== policy.id || typeof baseline.lowMemory !== 'boolean' ||
        !Number.isSafeInteger(baseline.pressureThresholdBytes) || baseline.pressureThresholdBytes <= 0 ||
        !Number.isSafeInteger(baseline.recordedAtMs) || baseline.recordedAtMs <= 0)
      throw new Error('Missing or mismatched Android pressure baseline');
    const run = (...args) => execFileSync(adb, ['-s', serial, ...args],
      { encoding: 'utf8', timeout: args.includes('instrument') ? 30000 : 10000 });
    const shell = (...args) => run('shell', ...args);
    let pressure = baseline;
    const reason = await waitForResources(() => {
      if (mode === 'android-pressure') pressure = readFreshAndroidPressure(run, policy, pressure.recordedAtMs);
      return { ...parseResources(shell('cat', '/proc/meminfo'), shell('dumpsys', 'thermalservice')),
        ...(mode === 'android-pressure' ? { availableMemoryBytes: pressure.availableMemoryBytes } : {}),
        lowMemory: pressure.lowMemory, pressureThresholdBytes: pressure.pressureThresholdBytes,
        androidBaselineRecordedAtMs: pressure.recordedAtMs,
        pressureSource: mode === 'android-pressure' ? 'fresh-Android-probe' : 'initial-Android-probe' };
    }, sample => appendFileSync(process.env.RESOURCE_REPORT, JSON.stringify(sample) + '\n'), { seconds, policy, mode });
    if (reason) { console.error(`Preflight blocked: ${reason}`); process.exitCode = 2; }
    else console.log('Preflight passed: three stable resource samples; app admission remains authoritative.');
  } catch (error) { console.error(`Preflight failed: ${error.message}`); process.exitCode = 2; }
}
