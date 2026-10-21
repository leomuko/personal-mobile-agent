import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

const serial = process.env.ANDROID_SERIAL;
if (!/^[A-Za-z0-9._:-]+$/.test(serial || '')) throw new Error('Set ANDROID_SERIAL to the designated device');
const folder = 'build/encoder-counting';
const proof = JSON.parse(readFileSync(join(folder, 'counting-proof.json')));
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
if (proof.passed !== true || proof.admissionVerified !== true ||
    proof.referenceSha256 !== digest(readFileSync(join(folder, 'reference.tsv'))) ||
    Object.entries(proof.sourceSha256).some(([path, sha]) => digest(readFileSync(path)) !== sha)) throw new Error('No current host counting proof');
const adb = process.env.ADB || join(process.env.HOME, 'Library/Android/sdk/platform-tools/adb');
const adbRun = (...args) => execFileSync(adb, ['-s', serial, ...args], { timeout: 30000, maxBuffer: 8 * 1024 * 1024 });
const pkg = 'dev.edgecompanion.app.secureqa';
const runId = randomUUID();
adbRun('push', join(folder, 'reference.tsv'), '/data/local/tmp/pma-encoder-counting.tsv');
adbRun('shell', 'run-as', pkg, 'cp', '/data/local/tmp/pma-encoder-counting.tsv', 'no_backup/qa-embedding/counting-reference.tsv');
const log = adbRun('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', 'dev.edgecompanion.app.EncoderAdmissionTest',
  '-e', 'p2Admission', 'true', '-e', 'admissionRunId', runId, `${pkg}.test/androidx.test.runner.AndroidJUnitRunner`);
writeFileSync(join(folder, `admission-${runId}.txt`), log);
if (!/^OK \(1 test\)\s*$/m.test(log.toString())) throw new Error('Android admission did not pass');
const report = JSON.parse(adbRun('exec-out', 'run-as', pkg, 'cat', 'no_backup/p2-encoder-admission.json'));
if (report.runId !== runId || report.passed !== true || report.cases !== 1038 || report.jniTransportVerified !== true ||
    report.nativeEncoderInvoked !== false || report.transport !== 'mediapipe-packet-bytes-v1') throw new Error('No fresh Android admission evidence');
report.hostProofSha256 = digest(readFileSync(join(folder, 'counting-proof.json')));
report.appSha256 = digest(readFileSync('androidApp/build/outputs/apk/secureQa/androidApp-secureQa.apk'));
report.testSha256 = digest(readFileSync('androidApp/build/outputs/apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk'));
writeFileSync(join(folder, 'android-admission.json'), JSON.stringify(report, null, 2));
adbRun('shell', 'rm', '-f', '/data/local/tmp/pma-encoder-counting.tsv');
console.log(`Android admission passed: ${report.cases} exact SDK-byte cases; run ${runId}`);
