// Host-only regression for scripts/verify-chat-context.sh. No device: a synthetic adb on PATH
// records every call and emulates only the secureQa app's private files in a temp directory.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');
const script = join(root, 'scripts/verify-chat-context.sh');
const serial = 'SYNTHETIC-S24';
const qaPackage = 'dev.edgecompanion.app.secureqa';

const fakeAdb = `#!/usr/bin/env node
const { appendFileSync, existsSync, mkdirSync, readFileSync, writeFileSync } = require('node:fs');
const { execFileSync, spawnSync } = require('node:child_process');
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
    const running = { schema: 1, runId, status: 'running', cases: [{ case: 'near_bound', status: 'running' }] };
    write('no_backup/chat-context-' + runId + '.json', running);
    if (process.env.FAKE_INSTRUMENTATION === 'hang') { hang(); return; }
    write('no_backup/chat-context-' + runId + '.json', { ...running, status: 'measured', cleanupConfirmed: true,
      cases: [{ case: 'near_bound', status: 'measured' }] });
    write('no_backup/qa-supervision/' + runId + '/status.json', { schema: 2, runId, status: 'closed', reason: null,
      cleanupConfirmed: false, elapsedMs: 1, capturedAtMs: Date.now(), leaseMs: 20000, pollMs: 500, cleanupMs: 5000 });
    if (process.env.FAKE_INSTRUMENTATION === 'safety-stop') writeFileSync(join(thermalDir, 'safety-stop.json'), '{"reason":"synthetic"}');
    console.log('OK (1 test)'); process.exit(process.env.FAKE_INSTRUMENTATION === 'exit-code' ? 1 : 0);
  }
  if (cls === 'dev.edgecompanion.app.PassageSqlCipherTest') {
    if (process.env.FAKE_INSTRUMENTATION === 'hang') { hang(); return; }
    console.log('OK (3 tests)'); process.exit(process.env.FAKE_INSTRUMENTATION === 'exit-code' ? 1 : 0);
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
    FAKE_ADB_LOG: join(dir, 'adb.jsonl'), FAKE_GRADLE_LOG: join(dir, 'gradle.txt'), ...extra };
  for (const name of ['QA_SUPERVISION_RUN_ID', 'QA_SUPERVISOR_PID', 'MEMORY_POLICY_REPORT', 'THERMAL_REPORT_DIR']) delete env[name];
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
      const results = join(dir, 'results', name);
      done({ code, output, pid: child.pid, results, calls: adbCalls(dir),
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
      assert.match(args[4], /\/androidApp-secureQa(-androidTest)?\.apk$/);
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
}));

test('SQLCipher gate uses only install -r and am instrument on the secureQa app', () => withSandbox(async dir => {
  const result = await run(dir, ['sqlcipher']);
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'passed');
  assertClientStopped(result, summary);
  assertOnlySecureQa(result.calls);
  assert.deepEqual(result.calls.map(call => call.args[2]), ['install', 'install', 'shell']);
}));

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
  ['a test APK with another application ID', dir => writeFileSync(join(dir, testApk), 'dev.edgecompanion.app.test\n'), 1],
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
