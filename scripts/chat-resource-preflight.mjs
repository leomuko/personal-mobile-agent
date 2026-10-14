#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { appendFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { setTimeout as wait } from 'node:timers/promises';

export const MEMORY_FLOOR_BYTES = 3 * 1024 ** 3;
// Reserve the existing 2.5 GiB PSS target before loading; this is advisory, not a memory guarantee.
export const LOAD_RESERVE_BYTES = 2.5 * 1024 ** 3;

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

export function blockedReason(sample) {
  if (Object.values(sample).some(value => value == null || !Number.isFinite(value))) return 'RESOURCE_UNKNOWN';
  if (sample.thermalStatus >= 3 || sample.skinC >= 45) return 'THERMAL';
  if (sample.totalMemoryBytes < 7 * 1024 ** 3) return 'UNSUPPORTED_DEVICE';
  if (sample.availableMemoryBytes < MEMORY_FLOOR_BYTES) return 'LOW_MEMORY';
  if (sample.availableMemoryBytes < MEMORY_FLOOR_BYTES + LOAD_RESERVE_BYTES) return 'LOAD_HEADROOM';
  return null;
}

export async function waitForResources(snapshot, record, { seconds = 60, intervalMs = 5000,
  now = Date.now, sleep = wait } = {}) {
  const deadline = now() + seconds * 1000;
  let stable = 0;
  while (true) {
    const sample = snapshot();
    const reason = blockedReason(sample);
    stable = reason == null ? stable + 1 : 0;
    record({ recordedAt: new Date(now()).toISOString(), ...sample, reason, stableSamples: stable,
      requiredAvailableMemoryBytes: MEMORY_FLOOR_BYTES + LOAD_RESERVE_BYTES });
    if (reason && !['LOW_MEMORY', 'LOAD_HEADROOM'].includes(reason)) return reason;
    if (stable >= 3) return null;
    if (now() >= deadline) return reason || 'RESOURCE_UNSTABLE';
    await sleep(Math.min(intervalMs, deadline - now()));
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    const serial = process.env.ANDROID_SERIAL;
    if (!serial || !/^[a-zA-Z0-9._:-]+$/.test(serial)) throw new Error('Invalid ANDROID_SERIAL');
    const seconds = Number(process.env.CHAT_PREFLIGHT_SECONDS || 60);
    if (!Number.isInteger(seconds) || seconds < 10 || seconds > 300) throw new Error('CHAT_PREFLIGHT_SECONDS must be 10..300');
    const adb = process.env.ADB;
    const shell = (...args) => execFileSync(adb, ['-s', serial, 'shell', ...args], { encoding: 'utf8', timeout: 10000 });
    const reason = await waitForResources(() => parseResources(shell('cat', '/proc/meminfo'), shell('dumpsys', 'thermalservice')),
      sample => appendFileSync(process.env.RESOURCE_REPORT, JSON.stringify(sample) + '\n'), { seconds });
    if (reason) { console.error(`Preflight blocked: ${reason}`); process.exitCode = 2; }
    else console.log('Preflight passed: three stable resource samples; app admission remains authoritative.');
  } catch (error) { console.error(`Preflight failed: ${error.message}`); process.exitCode = 2; }
}
