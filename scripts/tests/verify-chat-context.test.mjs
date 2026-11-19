// Host-only regression for scripts/verify-chat-context.sh. No device: a synthetic adb on PATH
// records every call and emulates only the secureQa app's private files in a temp directory.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');
// VERIFY_CHAT_CONTEXT_SCRIPT lets a reviewer run this suite against another revision of the script.
const script = process.env.VERIFY_CHAT_CONTEXT_SCRIPT || join(root, 'scripts/verify-chat-context.sh');
const serial = 'SYNTHETIC-S24';
const qaPackage = 'dev.edgecompanion.app.secureqa';
const modelSha = JSON.parse(readFileSync(join(root, 'planning/models/gemma-4-e2b-it.json'), 'utf8')).artifact.sha256;

const fakeAdb = `#!/usr/bin/env node
const { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } = require('node:fs');
const { execFileSync, spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { dirname, join } = require('node:path');
const args = process.argv.slice(2);
const device = process.env.FAKE_DEVICE_DIR;
// Every synthetic adb process records its own PID; the test teardown fails if any is still alive.
const entry = { args, pid: process.pid };
if (args.includes('/proc/uptime')) {
  // Only the thermal monitor reads the boot clock: record which process supervises it.
  entry.monitorPid = process.ppid;
  entry.monitorParentPid = Number(execFileSync('ps', ['-o', 'ppid=', '-p', String(process.ppid)], { encoding: 'utf8' }).trim());
  entry.supervisorPidEnv = Number(process.env.QA_SUPERVISOR_PID);
}
appendFileSync(process.env.FAKE_ADB_LOG, JSON.stringify(entry) + '\\n');
const write = (path, value) => { mkdirSync(dirname(join(device, path)), { recursive: true }); writeFileSync(join(device, path), JSON.stringify(value)); };
if (args[0] !== '-s' || args[1] !== '${serial}') process.exit(64);
const [command, ...rest] = args.slice(2);
const option = name => rest[rest.indexOf(name) + 1];
const thermalDir = process.env.THERMAL_REPORT_DIR;
const hang = () => { setInterval(() => {}, 1 << 30); };
// Simulates a stalled device call, but exits once its caller is gone so it cannot outlive the test.
const stallUntilOrphaned = () => { const parent = process.ppid; setInterval(() => { if (process.ppid !== parent) process.exit(1); }, 100); };
if (command === 'install' && rest[0] === '-r' && existsSync(rest[1])) {
  // Simulates a rebuild that replaces the Gradle outputs after the script staged its copies.
  if (process.env.FAKE_REBUILD_DURING_INSTALL === '1') for (const [path, id] of [
    ['secureQa/androidApp-secureQa.apk', '${qaPackage}'],
    ['androidTest/secureQa/androidApp-secureQa-androidTest.apk', '${qaPackage}.test']])
    writeFileSync(join(process.env.APK_ROOT, path), id + '\\nrebuilt after staging\\n');
  const bytes = readFileSync(rest[1]);
  // Simulates the staged test APK changing after the app install, before its own install.
  if (process.env.FAKE_TAMPER_STAGED_TEST === '1' && rest[1].endsWith('/androidApp-secureQa.apk')) {
    const staged = join(dirname(rest[1]), 'androidApp-secureQa-androidTest.apk');
    chmodSync(staged, 0o600); appendFileSync(staged, 'tampered');
  }
  // What the device would receive: the installed path and a digest of its bytes at install time.
  appendFileSync(process.env.FAKE_ADB_LOG + '.installs', JSON.stringify({ path: rest[1],
    sha256: createHash('sha256').update(bytes).digest('hex') }) + '\\n');
  const app = rest[1].endsWith('/androidApp-secureQa.apk');
  if (app && process.env.FAKE_INSTALL === 'fail-app') { console.log('Failure [INSTALL_FAILED_SYNTHETIC]'); process.exit(1); }
  if (app && process.env.FAKE_INSTALL === 'quiet-failure') { console.log('Failure [INSTALL_FAILED_SYNTHETIC]'); process.exit(0); }
  console.log('Success'); process.exit(0);
}
if (command === 'exec-out' && rest[0] === 'run-as' && rest[1] === '${qaPackage}' && rest[2] === 'cat') {
  const path = join(device, rest[3]);
  if (!existsSync(path)) { console.error('No such file'); process.exit(1); }
  process.stdout.write(readFileSync(path)); process.exit(0);
}
if (command === 'shell' && rest.length === 1 && rest[0].startsWith('run-as ${qaPackage} sh -c ')) {
  const result = spawnSync('/bin/sh', ['-c', rest[0].slice('run-as ${qaPackage} '.length)], { cwd: device });
  process.stdout.write(result.stdout);
  process.exit(result.status ?? 1);
}
if (command === 'shell' && rest.join(' ') === 'cat /proc/uptime') { console.log('100.25 50.00'); process.exit(0); }
if (command === 'shell' && rest.join(' ') === 'dumpsys thermalservice') {
  if (process.env.FAKE_THERMAL === 'never') { stallUntilOrphaned(); return; }
  if (process.env.FAKE_THERMAL === 'safety-stop') writeFileSync(join(thermalDir, 'safety-stop.json'), '{"reason":"synthetic"}');
  console.log('Thermal Status: 0\\nCurrent temperatures from HAL:\\n\\tTemperature{mValue=30.5, mType=3, mName=SKIN, mStatus=0}');
  process.exit(0);
}
if (command === 'shell' && rest[0] === 'am' && rest[1] === 'instrument') {
  const cls = option('class');
  if (cls.includes('MemoryPolicyProbeTest')) {
    write('no_backup/memory-policy.json', { schema: 1, id: 'synthetic-baseline', calibrated: false,
      enforceAvailableMemoryFloor: true, modelSha256: 'a'.repeat(64), runtimeVersion: 'synthetic',
      loadingFloorBytes: 1, generationFloorBytes: 1, minimumTotalBytes: 1, availableMemorySamples: 1,
      sampleIntervalMs: 1, severeThermalStatus: 3, maxSkinCelsius: 45, contextTokens: 2048, outputTokens: 512,
      supervisedLoadReserveBytes: 0 });
    write('no_backup/memory-baseline.json', {}); write('no_backup/memory-exits.json', []);
    console.log('OK (2 tests)'); process.exit(0);
  }
  if (cls === 'dev.edgecompanion.app.ChatContextMeasurementTest') {
    const runId = option('supervisionRunId');
    const report = 'no_backup/chat-context-' + runId + '.json';
    const running = { schema: 2, runId, status: 'running', cases: [{ case: 'near_bound', status: 'running' }] };
    write(report, running);
    write('no_backup/chat-context-' + runId + '-telemetry.json', { samples: [] });
    if (process.env.FAKE_INSTRUMENTATION === 'hang') { hang(); return; }
    // A synthetic report that matches the request (profile from the run ID, repeat from the argument).
    const profile = Number(runId.split('-')[1]);
    const repeat = Number(option('chatContextRepeat'));
    const cases = [];
    for (let iteration = 1; iteration <= repeat; iteration++) for (const name of ['near_bound', 'short_control', 'long_answer']) {
      // FAKE_SHORT_LONG_ANSWERS lists iterations (or "all") whose long answer stays under 128 tokens.
      const shortList = (process.env.FAKE_SHORT_LONG_ANSWERS || '').split(',');
      const short = name === 'long_answer' && (shortList.includes('all') || shortList.includes(String(iteration)));
      const long = name === 'long_answer' && !short;
      cases.push({ case: name, iteration, status: 'measured', cleanupConfirmed: true,
        resident: { status: 'LOADED', backend: 'GPU', loadedContextTokens: profile, loadedOutputTokens: 512 },
        loadMs: 9000 + 100 * iteration, firstTextMs: 500 + iteration, coldRequestFirstTextMs: 14000 + 10 * iteration,
        generationMs: long ? 20000 : 600, inputTokens: 17, outputTokens: long ? 300 : short ? 90 : 6,
        decodeTokensPerSecond: long ? 18 + iteration : 10, decodeRateMeaningful: long,
        sampledPeakPssBytes: 2000000000 + iteration, sampledPeakRssBytes: 3000000000 });
    }
    // Mismatch regressions patch the top level, or the last case (its resident object is merged).
    const casePatch = JSON.parse(process.env.FAKE_CASE_PATCH || '{}');
    const last = cases.at(-1);
    Object.assign(last, casePatch, { resident: { ...last.resident, ...(casePatch.resident || {}) } });
    write(report, { ...running, status: 'measured', cleanupConfirmed: true, buildType: 'secureQa', backend: 'GPU',
      automaticFallback: false, bakedContextTokens: profile, reservedOutputTokens: 512,
      modelSha256: process.env.FAKE_MODEL_SHA, repeat, cases, ...JSON.parse(process.env.FAKE_REPORT_PATCH || '{}') });
    if (process.env.FAKE_REPORT_RAW) writeFileSync(join(device, report), process.env.FAKE_REPORT_RAW);
    const marker = 'no_backup/chat-context-' + runId + '-supervision-stop.json';
    if (process.env.FAKE_STOP_MARKER === 'valid')
      write(marker, { schema: 1, runId, status: 'failed', failureType: 'SupervisionLoss', recordedAtMs: 1 });
    if (process.env.FAKE_STOP_MARKER === 'malformed') writeFileSync(join(device, marker), 'cat: no such file\\n');
    write('no_backup/qa-supervision/' + runId + '/status.json', { schema: 2, runId, status: 'closed', reason: null,
      cleanupConfirmed: false, elapsedMs: 1, capturedAtMs: Date.now(), leaseMs: 20000, pollMs: 500, cleanupMs: 5000 });
    if (process.env.FAKE_INSTRUMENTATION === 'safety-stop') writeFileSync(join(thermalDir, 'safety-stop.json'), '{"reason":"synthetic"}');
    console.log('OK (1 test)'); process.exit(process.env.FAKE_INSTRUMENTATION === 'exit-code' ? 1 : 0);
  }
  if (cls === 'dev.edgecompanion.app.PassageSqlCipherTest,dev.edgecompanion.app.GroundingSqlCipherTest') {
    if (process.env.FAKE_INSTRUMENTATION === 'hang') { hang(); return; }
    if (process.env.FAKE_INSTRUMENTATION === 'passage-only') { console.log('OK (3 tests)'); process.exit(0); }
    // A skipped (assumption failure, -4) or ignored (-3) opt-in test beside an otherwise passing count.
    if (process.env.FAKE_INSTRUMENTATION === 'assumption-failure') console.log('INSTRUMENTATION_STATUS_CODE: -4');
    if (process.env.FAKE_INSTRUMENTATION === 'ignored') console.log('INSTRUMENTATION_STATUS_CODE: -3');
    console.log('OK (8 tests)'); process.exit(process.env.FAKE_INSTRUMENTATION === 'exit-code' ? 1 : 0);
  }
}
console.error('Unexpected synthetic adb call'); process.exit(1);
`;

// Synthetic build-tools aapt2: the first line of a synthetic APK is its application ID.
const fakeAapt2 = '#!/bin/sh\n[ "$1" = dump ] && [ "$2" = packagename ] && [ -f "$3" ] || exit 2\nhead -n 1 "$3"\n';
const appApk = 'apk/secureQa/androidApp-secureQa.apk';
const testApk = 'apk/androidTest/secureQa/androidApp-secureQa-androidTest.apk';

function sandbox() {
  const dir = mkdtempSync(join(tmpdir(), 'verify-chat-context-'));
  const bin = join(dir, 'bin');
  const tools = join(dir, 'sdk/build-tools/36.1.0');
  for (const path of [bin, tools, join(dir, 'device'), join(dir, 'results'), join(dir, 'apk/secureQa'), join(dir, 'apk/androidTest/secureQa')])
    mkdirSync(path, { recursive: true });
  writeFileSync(join(dir, appApk), `${qaPackage}\nsynthetic app\n`);
  writeFileSync(join(dir, testApk), `${qaPackage}.test\nsynthetic tests\n`);
  writeFileSync(join(bin, 'adb'), fakeAdb);
  writeFileSync(join(bin, 'gradlew'), '#!/bin/sh\nprintf "%s\\n" "$*" >> "$FAKE_GRADLE_LOG"\n');
  writeFileSync(join(tools, 'aapt2'), fakeAapt2);
  for (const path of [join(bin, 'adb'), join(bin, 'gradlew'), join(tools, 'aapt2')]) chmodSync(path, 0o755);
  return dir;
}

const alive = pid => { try { process.kill(pid, 0); return true; } catch { return false; } };
const adbCalls = dir => (existsSync(join(dir, 'adb.jsonl'))
  ? readFileSync(join(dir, 'adb.jsonl'), 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)) : []);

// Teardown: every synthetic adb process (and the monitor that ran them) must be gone. Short-lived
// calls get up to 3 s to finish; anything left is killed so the suite never leaks, then reported.
async function withSandbox(body) {
  const dir = sandbox();
  try { await body(dir); } finally {
    const pids = new Set(adbCalls(dir).flatMap(call => [call.pid, call.monitorPid].filter(Boolean)));
    let survivors = [...pids].filter(alive);
    for (let i = 0; i < 30 && survivors.length; i++) {
      await new Promise(next => setTimeout(next, 100));
      survivors = survivors.filter(alive);
    }
    for (const pid of survivors) { try { process.kill(pid, 'SIGKILL'); } catch {} }
    rmSync(dir, { recursive: true, force: true });
    if (survivors.length) throw new Error(`processes left behind by the run: ${survivors.join(', ')}`);
  }
}

const isRunInstrument = call => call.args.includes('instrument') && !call.args.join(' ').includes('MemoryPolicyProbeTest');

function run(dir, args, extra = {}, { signal } = {}) {
  const env = { ...process.env, PATH: `${join(dir, 'bin')}:${process.env.PATH}`, ANDROID_SERIAL: serial,
    ANDROID_HOME: join(dir, 'sdk'), ADB: join(dir, 'bin/adb'), GRADLE: join(dir, 'bin/gradlew'),
    APK_ROOT: join(dir, 'apk'), RESULTS_ROOT: join(dir, 'results'), FAKE_DEVICE_DIR: join(dir, 'device'),
    FAKE_ADB_LOG: join(dir, 'adb.jsonl'), FAKE_GRADLE_LOG: join(dir, 'gradle.txt'), FAKE_MODEL_SHA: modelSha, ...extra };
  for (const name of ['QA_SUPERVISION_RUN_ID', 'QA_SUPERVISOR_PID', 'MEMORY_POLICY_REPORT', 'THERMAL_REPORT_DIR',
    'CHAT_CONTEXT_DEADLINE_SECONDS', 'PASSAGE_DEADLINE_SECONDS']) if (!(name in extra)) delete env[name];
  return new Promise(done => {
    const child = spawn('/bin/bash', [script, ...args], { cwd: root, env, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    child.stdout.on('data', chunk => { output += chunk; });
    child.stderr.on('data', chunk => { output += chunk; });
    // Signal the script itself (not its children) once the run's instrumentation client has started.
    const poll = signal && setInterval(() => {
      if (adbCalls(dir).some(isRunInstrument)) { clearInterval(poll); child.kill(signal); }
    }, 100);
    child.on('close', code => {
      if (poll) clearInterval(poll);
      const [name] = readdirSync(join(dir, 'results'));
      const results = name ? join(dir, 'results', name) : null;
      const installsLog = join(dir, 'adb.jsonl.installs');
      const installs = existsSync(installsLog)
        ? readFileSync(installsLog, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)) : [];
      done({ code, output, pid: child.pid, results, calls: adbCalls(dir), installs,
        json: file => JSON.parse(readFileSync(join(results, file), 'utf8')) });
    });
  });
}

// The instrumentation client PID the script recorded must be the synthetic adb process itself.
function assertClientStopped(result, summary) {
  const clients = result.calls.filter(isRunInstrument);
  assert.equal(clients.length, 1, 'one instrumentation client');
  assert.equal(summary.instrumentationPid, clients[0].pid, '$! is the adb client, not a shell');
  assert.ok(!alive(clients[0].pid), 'adb instrumentation client stopped');
}

function assertOnlySecureQa(calls) {
  for (const { args } of calls) {
    assert.deepEqual(args.slice(0, 2), ['-s', serial], 'every call names the explicit serial');
    const text = args.join(' ');
    assert.doesNotMatch(text, /\buninstall\b|\bpm\b|\bclear\b/, `forbidden adb command: ${text}`);
    for (const match of text.matchAll(/run-as (\S+)/g)) assert.equal(match[1], qaPackage);
    if (args[2] === 'install') {
      assert.equal(args[3], '-r');
      // Only the run's staged copies are installed, never the Gradle outputs.
      assert.match(args[4], /\/results\/[^/]+\/apks\/androidApp-secureQa(-androidTest)?\.apk$/);
    }
    if (args.includes('instrument')) assert.match(args.at(-1), /^dev\.edgecompanion\.app\.secureqa\.test\/androidx\.test\.runner\.AndroidJUnitRunner$/);
  }
}

test('measurement uses this script as the supervisor PID and only touches the secureQa app', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '4096']);
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'passed');
  assert.equal(summary.supervisorPid, result.pid, 'QA_SUPERVISOR_PID is the script process itself ($$)');
  const beats = result.calls.filter(call => call.monitorPid);
  assert.ok(beats.length >= 1, 'monitor renewed supervision');
  for (const beat of beats) {
    assert.equal(beat.monitorParentPid, result.pid, 'monitor parent is the script, as renewSupervision requires');
    assert.equal(beat.supervisorPidEnv, result.pid);
  }
  assert.ok(existsSync(join(result.results, 'thermal/supervision-heartbeats.jsonl')));
  assert.ok(!existsSync(join(result.results, 'thermal/safety-stop.json')));
  assert.equal(result.json('chat-context.json').status, 'measured');
  assert.ok(!alive(summary.monitorPid), 'monitor stopped');
  assertClientStopped(result, summary);
  assert.match(readFileSync(join(result.results, 'apk-identity.txt'), 'utf8'),
    new RegExp(`^${qaPackage} ${qaPackage} .*\n${qaPackage}\\.test ${qaPackage}\\.test `));
  assertOnlySecureQa(result.calls);
  assert.match(readFileSync(join(dir, 'gradle.txt'), 'utf8'), /-PtestBuildType=secureQa -PchatContextTokens=4096 /);
  // Default --repeat 3 reaches the device, and the deadline follows 270 + 630 x N.
  assert.match(result.calls.find(isRunInstrument).args.join(' '), / -e chatContextRepeat 3 /);
  assert.equal(summary.repeat, 3);
  assert.equal(summary.deadlineSeconds, 270 + 630 * 3);
  assert.equal(summary.deadlineSource, 'formula');
  // The optional supervision-loss marker is recorded as absent, not saved as an error text.
  assert.equal(result.json('evidence.json').supervisionStop, 'absent');
  assert.ok(!existsSync(join(result.results, 'supervision-stop.json')));
  assert.deepEqual(result.json('evidence.json'), { chatContext: 'present', partialTelemetry: 'present',
    supervisionStop: 'absent', supervision: 'present', thermalFinal: 'present' });
  const report = result.json('summary.json');
  assert.deepEqual(Object.keys(report.cases), ['near_bound', 'short_control', 'long_answer']);
  assert.deepEqual(report.cases.near_bound.loadMs, { median: 9200, min: 9100, max: 9300, values: [9100, 9200, 9300] });
  assert.deepEqual(report.cases.long_answer.coldToFirstTextMs, { median: 14020, min: 14010, max: 14030, values: [14010, 14020, 14030] });
  assert.deepEqual(report.cases.long_answer.decodeTokensPerSecond,
    { median: 20, min: 19, max: 21, values: [19, 20, 21], iterations: [1, 2, 3], shortIterations: 0 });
  assert.equal(report.cases.long_answer.decodeRateMeaningful, true);
  for (const name of ['near_bound', 'short_control']) {
    assert.equal(report.cases[name].decodeRateMeaningful, false);
    assert.equal(report.cases[name].decodeTokensPerSecond, null, 'short-case decode rates are not reported');
  }
}));

test('measure 2048 is the baseline profile and --repeat sets the iterations and deadline', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '2048', '--repeat', '2']);
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'passed');
  assert.equal(summary.profile, 2048);
  assert.equal(summary.repeat, 2);
  assert.equal(summary.deadlineSeconds, 270 + 630 * 2);
  assert.match(readFileSync(join(dir, 'gradle.txt'), 'utf8'), /-PtestBuildType=secureQa -PchatContextTokens=2048 /);
  assert.match(result.calls.find(isRunInstrument).args.join(' '), / -e chatContextRepeat 2 /);
  const report = result.json('summary.json');
  assert.equal(report.profile, 2048);
  assert.deepEqual(report.cases.short_control.loadMs, { median: 9150, min: 9100, max: 9200, values: [9100, 9200] });
  assertOnlySecureQa(result.calls);
}));

for (const args of [['measure', '1024'], ['measure', '4096', '--repeat', '0'], ['measure', '4096', '--repeat', '6'],
  ['measure', '4096', '--repeat'], ['measure', '4096', '--times', '3'], ['measure', '4096', '--repeat', '2x']]) {
  test(`invalid arguments ${args.join(' ')} exit 2 before any build or device call`, () => withSandbox(async dir => {
    const result = await run(dir, args);
    assert.equal(result.code, 2, result.output);
    assert.equal(result.results, null, 'no run directory');
    assert.equal(result.calls.length, 0);
    assert.ok(!existsSync(join(dir, 'gradle.txt')));
  }));
}

test('APK outputs rebuilt after staging are not what gets installed', () => withSandbox(async dir => {
  const original = { app: readFileSync(join(dir, appApk)), test: readFileSync(join(dir, testApk)) };
  const result = await run(dir, ['measure', '4096', '--repeat', '1'], { FAKE_REBUILD_DURING_INSTALL: '1' });
  assert.equal(result.code, 0, result.output);
  assert.match(readFileSync(join(dir, appApk), 'utf8'), /rebuilt after staging/, 'the build outputs did change');
  const hashes = Object.fromEntries(readFileSync(join(result.results, 'apks.sha256'), 'utf8').trim().split('\n')
    .map(line => line.split(/\s+/)).map(([sha, name]) => [name, sha]));
  const digest = bytes => createHash('sha256').update(bytes).digest('hex');
  assert.equal(hashes['androidApp-secureQa.apk'], digest(original.app));
  assert.equal(hashes['androidApp-secureQa-androidTest.apk'], digest(original.test));
  assert.equal(result.installs.length, 2);
  for (const install of result.installs) {
    assert.ok(install.path.startsWith(join(result.results, 'apks') + '/'), `installed a staged copy: ${install.path}`);
    const name = install.path.split('/').at(-1);
    assert.equal(install.sha256, hashes[name], 'installed bytes are the hashed staged bytes');
  }
  assert.notEqual(digest(readFileSync(join(dir, appApk))), hashes['androidApp-secureQa.apk']);
  assertOnlySecureQa(result.calls);
}));

test('a staged APK changed before its install fails the run before that install', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '4096', '--repeat', '1'], { FAKE_TAMPER_STAGED_TEST: '1' });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.reason, 'apk-integrity');
  assert.equal(summary.stage, 'install-tests');
  assert.equal(result.installs.length, 1, 'only the app APK was installed');
  assert.match(result.installs[0].path, /\/apks\/androidApp-secureQa\.apk$/);
  assert.equal(result.calls.filter(isRunInstrument).length, 0);
}));

test('SQLCipher gate uses only install -r and am instrument on the secureQa app', () => withSandbox(async dir => {
  const result = await run(dir, ['sqlcipher']);
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'passed');
  assertClientStopped(result, summary);
  assertOnlySecureQa(result.calls);
  assert.deepEqual(result.calls.map(call => call.args[2]), ['install', 'install', 'shell']);
  const instrument = result.calls.find(isRunInstrument).args.join(' ');
  assert.match(instrument, / -e class dev\.edgecompanion\.app\.PassageSqlCipherTest,dev\.edgecompanion\.app\.GroundingSqlCipherTest /);
  assert.match(instrument, / -e p2PassageSqlCipher true -e p2GroundingSqlCipher true /);
}));

test('the SQLCipher gate fails when only the passage tests ran', () => withSandbox(async dir => {
  const result = await run(dir, ['sqlcipher'], { FAKE_INSTRUMENTATION: 'passage-only' });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'failed');
  assert.equal(summary.reason, 'instrumentation');
  assert.equal(summary.stage, 'validation');
  assertClientStopped(result, summary);
}));

// S4-F8: FAILURE_PATTERN rejects these status codes before the OK count is read.
for (const [mode, code] of [['assumption-failure', -4], ['ignored', -3]]) {
  test(`the SQLCipher gate fails on status ${code} even beside OK (8 tests)`, () => withSandbox(async dir => {
    const result = await run(dir, ['sqlcipher'], { FAKE_INSTRUMENTATION: mode });
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.reason, 'instrumentation');
    assert.equal(summary.stage, 'validation');
    assertClientStopped(result, summary);
  }));
}

test('a never-finishing instrumentation hits the deadline, stops this run and keeps failed evidence', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '8192'],
    { FAKE_INSTRUMENTATION: 'hang', CHAT_CONTEXT_DEADLINE_SECONDS: '2', QA_STOP_GRACE_SECONDS: '2' });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'failed');
  assert.equal(summary.reason, 'deadline');
  const runId = summary.runId;
  const stop = result.json('deadline-stop.json');
  assert.equal(stop.runId, runId);
  assert.equal(stop.stopRequested, true);
  const published = JSON.parse(readFileSync(join(dir, 'device/no_backup/qa-supervision', runId, 'stop.json'), 'utf8'));
  assert.deepEqual(published, { schema: 2, runId, action: 'stop' });
  const stops = result.calls.filter(call => /qa-supervision\/[^/]+\/stop\.json/.test(call.args.join(" ")));
  assert.equal(stops.length, 1, 'exactly one run-specific stop');
  assert.match(stops[0].args.at(-1), new RegExp(`qa-supervision/${runId}/stop\\.tmp`));
  assert.deepEqual(result.json('chat-context.json').cases, [{ case: 'near_bound', status: 'running' }],
    'partial device evidence retained');
  assert.equal(result.json('evidence.json').supervisionStop, 'absent');
  assert.ok(existsSync(join(result.results, 'thermal/samples.csv')));
  assert.ok(existsSync(join(result.results, 'thermal-final.txt')));
  assert.ok(!alive(summary.monitorPid), 'heartbeat monitor stopped');
  assertClientStopped(result, summary);
  const beatsAfterStop = result.calls.slice(result.calls.indexOf(stops[0]) + 1).filter(call => call.monitorPid);
  assert.equal(beatsAfterStop.length, 0, 'no heartbeat after the stop');
  assertOnlySecureQa(result.calls);
}));

test('the SQLCipher deadline stops the host adb client', () => withSandbox(async dir => {
  const result = await run(dir, ['sqlcipher'],
    { FAKE_INSTRUMENTATION: 'hang', PASSAGE_DEADLINE_SECONDS: '2', QA_STOP_GRACE_SECONDS: '1' });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.reason, 'deadline');
  assertClientStopped(result, summary);
}));

for (const [signal, code, mode] of [['SIGTERM', 143, 'measure'], ['SIGINT', 130, 'sqlcipher']]) {
  test(`${signal} to the script during ${mode} instrumentation stops the adb client and records failure`, () => withSandbox(async dir => {
    const args = mode === 'measure' ? ['measure', '4096'] : ['sqlcipher'];
    const result = await run(dir, args, { FAKE_INSTRUMENTATION: 'hang' }, { signal });
    assert.equal(result.code, code, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.exitCode, code);
    assert.equal(summary.reason, 'instrumentation');
    assertClientStopped(result, summary);
    if (mode === 'measure') assert.ok(!alive(summary.monitorPid), 'monitor stopped');
  }));
}

// DP-F4: gates must hold under /bin/bash 3.2, where errexit ignores a failing bare [[ ]].
const gateFailures = [
  ['a thermal sample that never arrives', ['measure', '4096'], { FAKE_THERMAL: 'never', THERMAL_READY_SECONDS: '2' },
    'thermal-monitor', 'thermal-monitor'],
  ['a safety stop present at readiness', ['measure', '4096'], { FAKE_THERMAL: 'safety-stop' }, 'thermal-monitor', 'thermal-monitor'],
  ['a safety stop written during the run', ['measure', '4096'], { FAKE_INSTRUMENTATION: 'safety-stop' }, 'thermal-monitor', 'validation'],
  ['a failing app install', ['measure', '4096'], { FAKE_INSTALL: 'fail-app' }, 'install-app', 'install-app'],
  ['an app install that reports failure with exit 0', ['sqlcipher'], { FAKE_INSTALL: 'quiet-failure' }, 'install-app', 'install-app'],
  ['a non-zero measurement instrumentation exit', ['measure', '4096'], { FAKE_INSTRUMENTATION: 'exit-code' }, 'instrumentation', 'validation'],
  ['a non-zero SQLCipher instrumentation exit', ['sqlcipher'], { FAKE_INSTRUMENTATION: 'exit-code' }, 'instrumentation', 'validation'],
];
for (const [name, args, extra, reason, stage] of gateFailures) {
  test(`under /bin/bash, ${name} fails the run with a non-zero exit`, () => withSandbox(async dir => {
    const result = await run(dir, args, extra);
    assert.equal(result.code, 1, result.output);  // the gate itself, not the monitor-died branch (exit 2)
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.reason, reason);
    assert.equal(summary.stage, stage);
    assert.equal(summary.exitCode, result.code);
    if (stage !== 'validation') assert.equal(result.calls.filter(isRunInstrument).length, 0, 'instrumentation never started');
    if (extra.FAKE_THERMAL === 'never')
      assert.equal(readFileSync(join(result.results, 'thermal/samples.csv'), 'utf8'), 'recorded_at,thermal_status,skin_c\n');
    assertOnlySecureQa(result.calls);
  }));
}

// DP-F5: the application ID is checked with build-tools aapt2 before each install -r, failing closed.
const identityFailures = [
  ['an app APK with another application ID', dir => writeFileSync(join(dir, appApk), 'dev.edgecompanion.app\n'), 0],
  ['a test APK with another application ID', dir => writeFileSync(join(dir, testApk), 'dev.edgecompanion.app.test\n'), 0],
  ['no aapt2 in any build-tools', dir => rmSync(join(dir, 'sdk/build-tools'), { recursive: true }), 0],
  ['an APK aapt2 cannot read', dir => writeFileSync(join(dir, appApk), ''), 0],
];
for (const [name, prepare, installs] of identityFailures) {
  test(`${name} is rejected before its install`, () => withSandbox(async dir => {
    prepare(dir);
    const result = await run(dir, ['sqlcipher']);
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.reason, 'apk-identity');
    assert.equal(result.calls.filter(call => call.args[2] === 'install').length, installs);
    assert.equal(result.calls.filter(isRunInstrument).length, 0);
  }));
}

// Part B: the report must describe exactly the requested run, or the run fails with a specific reason.
const reportMismatches = [
  ['a requested-4096 report that says 8192', { FAKE_REPORT_PATCH: '{"bakedContextTokens":8192}' }, 'report-profile'],
  ['a case resident at 8192', { FAKE_CASE_PATCH: '{"resident":{"loadedContextTokens":8192}}' }, 'report-profile'],
  ['a case resident output of 256', { FAKE_CASE_PATCH: '{"resident":{"loadedOutputTokens":256}}' }, 'report-output'],
  ['a reserved output of 256', { FAKE_REPORT_PATCH: '{"reservedOutputTokens":256}' }, 'report-output'],
  ['a CPU backend', { FAKE_REPORT_PATCH: '{"backend":"CPU"}' }, 'report-backend'],
  ['automatic fallback', { FAKE_REPORT_PATCH: '{"automaticFallback":true}' }, 'report-backend'],
  ['a CPU resident', { FAKE_CASE_PATCH: '{"resident":{"backend":"CPU"}}' }, 'report-backend'],
  ['another model SHA', { FAKE_MODEL_SHA: 'b'.repeat(64) }, 'report-model'],
  ['a debug build type', { FAKE_REPORT_PATCH: '{"buildType":"debug"}' }, 'report-build-type'],
  ['another run ID', { FAKE_REPORT_PATCH: '{"runId":"context-4096-other"}' }, 'report'],
  ['a repeat count other than requested', { FAKE_REPORT_PATCH: '{"repeat":2}' }, 'report-cases'],
  ['a missing iteration', { FAKE_REPORT_PATCH: '{"cases":[]}' }, 'report-cases'],
  ['a short long answer flagged as a meaningful decode rate', { FAKE_CASE_PATCH: '{"outputTokens":6}' }, 'report-long-answer'],
  ['a 300-token long answer not flagged as meaningful', { FAKE_CASE_PATCH: '{"decodeRateMeaningful":false}' }, 'report-long-answer'],
  ['a failed case', { FAKE_CASE_PATCH: '{"status":"failed"}' }, 'report-cases'],
];
for (const [name, extra, reason] of reportMismatches) {
  test(`${name} fails the run (${reason})`, () => withSandbox(async dir => {
    const result = await run(dir, ['measure', '4096', '--repeat', '1'], extra);
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.reason, reason);
    assert.equal(summary.stage, 'validation');
    assert.ok(!existsSync(join(result.results, 'summary.json')), 'no summary for a rejected run');
    assertClientStopped(result, summary);
  }));
}

// Ruling 2026-11-18: a long answer under 128 tokens does not fail the run; it is excluded from decode rate.
test('long answers under 128 tokens in some iterations pass and are excluded from the decode rate', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '4096'], { FAKE_SHORT_LONG_ANSWERS: '2' });
  assert.equal(result.code, 0, result.output);
  assert.equal(result.json('result.json').status, 'passed');
  const longAnswer = result.json('summary.json').cases.long_answer;
  assert.deepEqual(longAnswer.decodeTokensPerSecond,
    { median: 20, min: 19, max: 21, values: [19, 21], iterations: [1, 3], shortIterations: 1 });
  assert.deepEqual(longAnswer.outputTokens.values, [300, 90, 300], 'every iteration is still recorded');
}));

test('when no long answer reaches 128 tokens the run passes with decode rate unavailable', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '4096'], { FAKE_SHORT_LONG_ANSWERS: 'all' });
  assert.equal(result.code, 0, result.output);
  assert.equal(result.json('result.json').status, 'passed');
  assert.deepEqual(result.json('summary.json').cases.long_answer.decodeTokensPerSecond,
    { status: 'unavailable', shortIterations: 3, minimumOutputTokens: 128 });
}));

// Part C: optional and required evidence files.
test('a present, valid supervision-loss marker is retained and fails the run', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '4096', '--repeat', '1'], { FAKE_STOP_MARKER: 'valid' });
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'supervision-loss');
  assert.equal(result.json('evidence.json').supervisionStop, 'present');
  assert.equal(result.json('supervision-stop.json').failureType, 'SupervisionLoss');
}));

for (const [name, extra, key, file] of [
  ['a present but non-JSON supervision-loss marker', { FAKE_STOP_MARKER: 'malformed' }, 'supervisionStop', 'supervision-stop.json'],
  ['a present but non-JSON report', { FAKE_REPORT_RAW: 'cat: no_backup/chat-context.json: error' }, 'chatContext', 'chat-context.json'],
]) {
  test(`${name} is malformed evidence and fails the run`, () => withSandbox(async dir => {
    const result = await run(dir, ['measure', '4096', '--repeat', '1'], extra);
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.reason, 'malformed-evidence');
    assert.equal(summary.stage, 'validation');
    assert.equal(result.json('evidence.json')[key], 'malformed');
    assert.ok(!existsSync(join(result.results, file)), 'non-JSON text is not saved under the .json name');
    assert.ok(existsSync(join(result.results, file + '.malformed')), 'kept for inspection');
  }));
}

test('the script itself is Bash 3.2 syntax and uses no Bash 4 features', () => {
  execFileSync('/bin/bash', ['-n', script]);
  const text = readFileSync(script, 'utf8');
  assert.match(text, /^#!\/bin\/bash\n/);
  assert.match(text, /QA_SUPERVISOR_PID=\$\$/);
  assert.doesNotMatch(text, /BASHPID|declare -A|local -A|\$\{[A-Za-z_]+(,,|\^\^)\}|wait -n|mapfile|readarray/);
  // Bash 3.2 errexit does not stop on a failing bare [[ ]]; every gate needs an explicit || or if.
  assert.doesNotMatch(text, /^[ \t]*(\[\[.*\]\]|\(\(.*\)\))[ \t]*$/m, 'bare [[ ]] or (( )) gate');
  // The instrumentation client is backgrounded directly, never through the device() function.
  assert.doesNotMatch(text, /^\s*device shell am instrument/m);
});
