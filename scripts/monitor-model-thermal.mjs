#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { appendFileSync, mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { setTimeout as wait } from 'node:timers/promises';
import { readMemoryPolicy } from './memory-policy.mjs';

const policy = readMemoryPolicy();

const serial = process.env.ANDROID_SERIAL;
if (!serial || !/^[a-zA-Z0-9._:-]+$/.test(serial)) {
  throw new Error('Set ANDROID_SERIAL to the authorized test device');
}
const adb = process.env.ADB || join(process.env.ANDROID_HOME || join(process.env.HOME, 'Library/Android/sdk'), 'platform-tools/adb');
const folder = resolve(process.env.THERMAL_REPORT_DIR || 'benchmarks/results/s24-thermal');
mkdirSync(folder, { recursive: true });
const samplePath = join(folder, 'samples.csv');
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

function stopQa(reason, sample) {
  writeFileSync(join(folder, 'safety-stop.json'), JSON.stringify({ reason, ...sample }, null, 2));
  execFileSync(adb, ['-s', serial, 'shell', 'am', 'force-stop', 'dev.edgecompanion.app.secureqa'],
    { timeout: 10000 });
}

while (true) {
  const recordedAt = new Date().toISOString();
  try {
    const { status, skin } = snapshot();
    appendFileSync(samplePath, `${recordedAt},${status},${skin}\n`);
    if (status >= policy.severeThermalStatus || skin >= policy.maxSkinCelsius) {
      stopQa('thermal_limit', { recordedAt, status, skin });
      process.exit(2);
    }
  } catch (error) {
    stopQa('thermal_signal_unavailable', { recordedAt, error: error.message });
    process.exit(2);
  }
  await wait(5000);
}
