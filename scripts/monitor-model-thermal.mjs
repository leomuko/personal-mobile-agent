#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { appendFileSync, closeSync, fsyncSync, mkdirSync, openSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { setTimeout as wait } from 'node:timers/promises';
import { readMemoryPolicy } from './memory-policy.mjs';
import { renewSupervision, stopSupervisedQa, supervisionIdentity } from './qa-supervision.mjs';

const policy = readMemoryPolicy();
const identity = supervisionIdentity();
let sequence = 0;

const serial = process.env.ANDROID_SERIAL;
if (!serial || !/^[a-zA-Z0-9._:-]+$/.test(serial)) {
  throw new Error('Set ANDROID_SERIAL to the authorized test device');
}
const adb = process.env.ADB || join(process.env.ANDROID_HOME || join(process.env.HOME, 'Library/Android/sdk'), 'platform-tools/adb');
const folder = resolve(process.env.THERMAL_REPORT_DIR || 'benchmarks/results/s24-thermal');
mkdirSync(folder, { recursive: true });
const samplePath = join(folder, 'samples.csv');
const stopPath = join(folder, 'safety-stop.json');
appendFileSync(samplePath, 'recorded_at,thermal_status,skin_c\n');

function snapshot() {
  const output = execFileSync(adb, ['-s', serial, 'shell', 'dumpsys', 'thermalservice'],
    { encoding: 'utf8', timeout: 10000 });
  const status = Number(/Thermal Status:\s*(\d+)/.exec(output)?.[1]);
  const hal = output.split('Current temperatures from HAL:')[1] || '';
  const skin = Number(/Temperature\{mValue=([\d.]+), mType=3, mName=SKIN/.exec(hal)?.[1]);
  if (!Number.isFinite(status) || !Number.isFinite(skin) || !hal.includes('mName=SKIN')) {
    throw new Error('Thermal status or SKIN sensor unavailable');
  }
  return { status, skin };
}

function syncIfSupported(fd) {
  try {
    fsyncSync(fd);
  } catch (error) {
    if (!['EINVAL', 'ENOSYS', 'ENOTSUP'].includes(error.code)) throw error;
  }
}

function writeStopRecord(record) {
  const temporary = join(folder, `.safety-stop-${randomUUID()}.tmp`);
  const fd = openSync(temporary, 'wx', 0o600);
  try {
    try {
      writeFileSync(fd, JSON.stringify(record, null, 2));
      syncIfSupported(fd);
    } finally {
      closeSync(fd);
    }
    renameSync(temporary, stopPath);
  } catch (error) {
    try { rmSync(temporary, { force: true }); } catch { /* Preserve the write error. */ }
    throw error;
  }
  const directory = openSync(folder, 'r');
  try {
    syncIfSupported(directory);
  } finally {
    closeSync(directory);
  }
}

function stopQa(reason, sample) {
  // Once a stop is latched, TERM must leave a failure status even if intent recording fails.
  // The synchronous stop request retains its existing timeout.
  process.on('SIGTERM', () => {});
  const event = { runId: identity.runId, reason, ...sample };
  let intentWritten = false;
  try {
    writeStopRecord({ ...event, delivery: 'pending' });
    intentWritten = true;
  } catch (error) {
    console.error(`thermal-stop-intent-write-failed: ${error.message}`);
  }

  const outcome = stopSupervisedQa({ adb, serial, runId: identity.runId });
  if (intentWritten) {
    try {
      writeStopRecord({ ...event, ...outcome, delivery: outcome.stopRequested ? 'requested' : 'failed' });
    } catch (error) {
      console.error(`thermal-stop-delivery-write-failed: ${error.message}`);
    }
  }
  process.exit(intentWritten ? 2 : 3);
}

function sampleOrStop(recordedAt) {
  try {
    return snapshot();
  } catch (error) {
    stopQa('thermal_signal_unavailable', { recordedAt, error: error.name });
  }
}

while (true) {
  const recordedAt = new Date().toISOString();
  const { status, skin } = sampleOrStop(recordedAt);
  if (status >= policy.severeThermalStatus || skin >= policy.maxSkinCelsius) {
    stopQa('thermal_limit', { recordedAt, status, skin });
  }
  try {
    const heartbeat = renewSupervision({ adb, serial, ...identity, sequence: ++sequence });
    appendFileSync(join(folder, 'supervision-heartbeats.jsonl'), JSON.stringify(heartbeat) + '\n');
    appendFileSync(samplePath, `${recordedAt},${status},${skin}\n`);
  } catch (error) {
    stopQa('thermal_signal_unavailable', { recordedAt, error: error.name });
  }
  await wait(5000);
}
