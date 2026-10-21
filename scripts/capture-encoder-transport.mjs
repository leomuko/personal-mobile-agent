import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

// Diagnostic packet copies only. Never loads an encoder/Gemma or reads personal sources.
const serial = process.env.ANDROID_SERIAL;
if (!/^[A-Za-z0-9._:-]+$/.test(serial || '')) throw new Error('Set ANDROID_SERIAL to the designated device');
const adb = process.env.ADB || join(process.env.HOME, 'Library/Android/sdk/platform-tools/adb');
const python = process.env.ENCODER_PYTHON || 'build/encoder-verification/bin/python';
const folder = 'build/encoder-counting';
mkdirSync(folder, { recursive: true });
execFileSync(python, ['scripts/prepare-encoder-counting.py', '--seed-only', '--output', folder], { stdio: 'inherit' });
const pkg = 'dev.edgecompanion.app.secureqa';
const runId = randomUUID();
const adbRun = (...args) => execFileSync(adb, ['-s', serial, ...args], { timeout: 30000, maxBuffer: 8 * 1024 * 1024 });
adbRun('push', join(folder, 'transport-inputs.txt'), '/data/local/tmp/pma-transport-inputs.txt');
adbRun('shell', 'run-as', pkg, 'mkdir', '-p', 'no_backup/qa-embedding');
adbRun('shell', 'run-as', pkg, 'cp', '/data/local/tmp/pma-transport-inputs.txt', 'no_backup/qa-embedding/transport-inputs.txt');
const log = adbRun('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'dev.edgecompanion.app.EncoderTransportCaptureTest',
  '-e', 'p2Transport', 'true', '-e', 'transportRunId', runId, `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`);
writeFileSync(join(folder, `transport-${runId}.txt`), log);
if (!/^OK \(1 test\)\s*$/m.test(log.toString())) throw new Error('SDK byte capture did not pass');
const report = JSON.parse(adbRun('exec-out', 'run-as', pkg, 'cat', 'no_backup/qa-embedding/transport.json'));
const snapshot = adbRun('exec-out', 'run-as', pkg, 'cat', 'no_backup/qa-embedding/transport.tsv');
if (report.runId !== runId || report.cases !== 1038 || report.nativeEncoderInvoked !== false ||
    createHash('sha256').update(snapshot).digest('hex') !== report.transportSha256) throw new Error('Stale or mismatched SDK capture');
report.sourceSha256 = Object.fromEntries([
  'androidApp/src/secureQa/kotlin/dev/edgecompanion/app/prerequisites/SdkPacketTransport.kt',
  'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/EncoderTransportCaptureTest.kt',
].map(path => [path, createHash('sha256').update(readFileSync(path)).digest('hex')]));
writeFileSync(join(folder, 'sdk-transport.tsv'), snapshot);
writeFileSync(join(folder, 'sdk-transport.json'), JSON.stringify(report, null, 2));
adbRun('shell', 'rm', '-f', '/data/local/tmp/pma-transport-inputs.txt');
console.log(`Captured ${report.cases} synthetic SDK byte cases; no model execution. Run: ${runId}`);
