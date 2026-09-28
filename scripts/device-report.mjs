#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const serial = process.argv[2];
if (!serial || serial.startsWith('-')) {
  console.error('Usage: node scripts/device-report.mjs <adb-device-id>');
  process.exit(1);
}
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT;
const adb = sdk ? join(sdk, 'platform-tools', 'adb') : 'adb';
const shell = (...args) => execFileSync(adb, ['-s', serial, 'shell', ...args], { encoding: 'utf8', timeout: 15000 }).trim();
const report = {
  recordedAt: new Date().toISOString(),
  manufacturer: shell('getprop', 'ro.product.manufacturer'),
  model: shell('getprop', 'ro.product.model'),
  android: shell('getprop', 'ro.build.version.release'),
  api: Number(shell('getprop', 'ro.build.version.sdk')),
  oneUi: shell('getprop', 'ro.build.version.oneui') || null,
  emulator: shell('getprop', 'ro.kernel.qemu') === '1',
  memory: shell('cat', '/proc/meminfo').split('\n').filter(line => /^Mem(Total|Available):/.test(line)),
  disk: shell('df', '-k', '/data'),
  modelBenchmarks: { status: 'not_run', samples: [] },
};
const directory = resolve('benchmarks/results');
mkdirSync(directory, { recursive: true });
const destination = join(directory, `device-${Date.now()}.json`);
writeFileSync(destination, JSON.stringify(report, null, 2) + '\n');
console.log(destination);

