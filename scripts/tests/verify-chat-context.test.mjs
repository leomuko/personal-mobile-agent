// Host-only regression for scripts/verify-chat-context.sh. No device: a synthetic adb on PATH
// records every call and emulates only the secureQa app's private files in a temp directory.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawn, execFileSync, spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmodSync, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '../..');
// VERIFY_CHAT_CONTEXT_SCRIPT lets a reviewer run this suite against another revision of the script.
const script = process.env.VERIFY_CHAT_CONTEXT_SCRIPT || join(root, 'scripts/verify-chat-context.sh');
const serial = 'SYNTHETIC-S24';
const qaPackage = 'dev.edgecompanion.app.secureqa';
const uiClasses = ['PassagesUiTest', 'PassagesCaptureTest', 'ComposerLayoutTest', 'HostRegressionTest']
  .map(name => `dev.edgecompanion.app.${name}`).join(',');
const modelSha = JSON.parse(readFileSync(join(root, 'planning/models/gemma-4-e2b-it.json'), 'utf8')).artifact.sha256;

const fakeAdb = `#!/usr/bin/env node
const { appendFileSync, chmodSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } = require('node:fs');
const { execFileSync, spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { dirname, join } = require('node:path');
const args = process.argv.slice(2);
const device = process.env.FAKE_DEVICE_DIR;
const thermalDir = process.env.THERMAL_REPORT_DIR;
const intentProbe = thermalDir && process.env.FAKE_MONITOR_STOP;
const probePath = name => join(dirname(thermalDir), name);
// Every synthetic adb process records its own PID; the test teardown fails if any is still alive.
const entry = { args, pid: process.pid };
if (args.includes('/proc/uptime')) {
  // Only the thermal monitor reads the boot clock: record which process supervises it.
  entry.monitorPid = process.ppid;
  if (intentProbe) writeFileSync(probePath('probe-monitor-pid'), String(process.ppid));
  else entry.monitorParentPid = Number(execFileSync('ps', ['-o', 'ppid=', '-p', String(process.ppid)], { encoding: 'utf8' }).trim());
  entry.supervisorPidEnv = Number(process.env.QA_SUPERVISOR_PID);
}
appendFileSync(process.env.FAKE_ADB_LOG, JSON.stringify(entry) + '\\n');
const write = (path, value) => { mkdirSync(dirname(join(device, path)), { recursive: true }); writeFileSync(join(device, path), JSON.stringify(value)); };
if (args[0] !== '-s' || args[1] !== '${serial}') process.exit(64);
const [command, ...rest] = args.slice(2);
const option = name => rest[rest.indexOf(name) + 1];
const hang = () => { setInterval(() => {}, 1 << 30); };
// Simulates a stalled device call, but exits once its caller is gone so it cannot outlive the test.
const stallUntilOrphaned = () => { const parent = process.ppid; setInterval(() => { if (process.ppid !== parent) process.exit(1); }, 100); };
if (intentProbe && rest.join(' ') === 'dumpsys thermalservice') {
  const fromMonitor = !existsSync(probePath('probe-monitor-pid')) ||
    Number(readFileSync(probePath('probe-monitor-pid'), 'utf8')) === process.ppid;
  const crossing = fromMonitor && intentProbe !== 'clean' && existsSync(probePath('probe-capture-pull'));
  const status = crossing && intentProbe !== 'skin-only' ? 3 : 2;
  const skin = crossing ? (intentProbe === 'skin-only' ? 45 : 45.1) : 44.9;
  if (crossing && intentProbe === 'intent-error') {
    // Make open fail with ENOTDIR even for a privileged test runner.
    renameSync(thermalDir, probePath('probe-thermal-saved'));
    writeFileSync(thermalDir, 'synthetic file in place of the report directory');
  }
  console.log('Thermal Status: ' + status + '\\nCurrent temperatures from HAL:\\n' +
    'Temperature{mValue=' + skin + ', mType=3, mName=SKIN, mStatus=' + status + '}');
  process.exit(0);
}
if (intentProbe && command === 'shell' && rest.length === 1 && rest[0].includes('/stop.tmp')) {
  if (intentProbe === 'intent-error') {
    rmSync(thermalDir);
    renameSync(probePath('probe-thermal-saved'), thermalDir);
  }
  const marker = join(thermalDir, 'safety-stop.json');
  writeFileSync(probePath('probe-at-dispatch.json'), existsSync(marker) ? readFileSync(marker) : 'null');
  if (intentProbe === 'blocked') { stallUntilOrphaned(); return; }
  if (intentProbe === 'delivery-error') process.exit(1);
  process.exit(0);
}
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
  // Capture pulls: a failing cat, or a short read that keeps the PNG signature, with exit 0.
  if (process.env.FAKE_PULL_FAIL && rest[3].endsWith(process.env.FAKE_PULL_FAIL)) { console.error('synthetic read error'); process.exit(1); }
  if (!existsSync(path)) { console.error('No such file'); process.exit(1); }
  const content = readFileSync(path);
  if (intentProbe && intentProbe !== 'clean' && /^no_backup\\/(?:answer-quality|chat-context)-[^/]+[.]json$/.test(rest[3]) &&
      !existsSync(probePath('probe-capture-pull'))) {
    // Instrumentation has finished. Keep the first capture pull open until the real monitor starts its stop request.
    writeFileSync(probePath('probe-capture-pull'), '');
    const deadline = Date.now() + 12000;
    const timer = setInterval(() => {
      if (existsSync(probePath('probe-at-dispatch.json'))) {
        clearInterval(timer);
        process.stdout.write(content);
      } else if (Date.now() >= deadline) process.exit(1);
    }, 20);
    return;
  }
  const event = { 'after-exit': 'safety-stop.json', 'killed-mid-stop': 'stop-publish-started' }[process.env.FAKE_FINALIZATION];
  if (event && /(?:answer-quality|chat-context)-.+[.]json$/.test(rest[3])) {
    // The wrapper has already waited for instrumentation before it pulls the capture.
    writeFileSync(join(thermalDir, 'capture-pull'), '');
    const deadline = Date.now() + 3000;
    const timer = setInterval(() => {
      if (existsSync(join(thermalDir, event))) {
        clearInterval(timer);
        process.stdout.write(content);
      } else if (Date.now() >= deadline) process.exit(1);
    }, 10);
    return;
  }
  if (process.env.FAKE_TRUNCATE_PULL && rest[3].endsWith(process.env.FAKE_TRUNCATE_PULL)) {
    process.stdout.write(content.subarray(0, 11)); process.exit(0);
  }
  process.stdout.write(content); process.exit(0);
}
if (command === 'shell' && rest.length === 1 && rest[0].startsWith('run-as ${qaPackage} sh -c ')) {
  const result = spawnSync('/bin/sh', ['-c', rest[0].slice('run-as ${qaPackage} '.length)], { cwd: device });
  let out = result.stdout.toString();
  // Capture listing variants: names no real directory can hold, or a listing cut before its end marker.
  if (rest[0].includes('END-OF-CAPTURE-LIST')) {
    if (process.env.FAKE_LISTING_EXTRA) out = out.replace('END-OF-CAPTURE-LIST', process.env.FAKE_LISTING_EXTRA.split('|').join('\\n') + '\\nEND-OF-CAPTURE-LIST');
    if (process.env.FAKE_LISTING === 'no-marker') out = out.replace('END-OF-CAPTURE-LIST\\n', '');
  }
  process.stdout.write(out);
  process.exit(result.status ?? 1);
}
if (command === 'shell' && rest.join(' ') === 'cat /proc/uptime') { console.log('100.25 50.00'); process.exit(0); }
if (command === 'shell' && rest.join(' ') === 'dumpsys thermalservice') {
  if (process.env.FAKE_THERMAL === 'never') { stallUntilOrphaned(); return; }
  if (process.env.FAKE_THERMAL === 'safety-stop') writeFileSync(join(thermalDir, 'safety-stop.json'), '{"reason":"synthetic"}');
  // Controlled monitor tests use the synthetic device solely for the wrapper's final read.
  if (process.env.FAKE_FINALIZATION) {
    const monitor = Number(readFileSync(join(thermalDir, 'monitor-pid'), 'utf8'));
    try {
      process.kill(monitor, 0);
      console.error('Final reading before the monitor exited');
      process.exit(1);
    } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  }
  const final = process.env.FAKE_FINAL_THERMAL;
  if (final === 'unavailable') process.exit(1);
  if (final === 'malformed') { console.log('Thermal Status: 0'); process.exit(0); }
  const status = final === 'severe-status' ? 3 : 0;
  const skin = final === 'skin-limit' ? 45 : 30.5;
  console.log('Thermal Status: ' + status + '\\nCurrent temperatures from HAL:\\n' +
    'Temperature{mValue=' + skin + ', mType=3, mName=SKIN, mStatus=' + status + '}');
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
    if (process.env.FAKE_INSTRUMENTATION === 'old-count') { console.log('OK (8 tests)'); process.exit(0); }
    // A failed, errored, ignored or skipped test beside an otherwise passing count.
    const status = process.env.FAKE_INSTRUMENTATION;
    if (/^-[1-4]$/.test(status)) console.log('INSTRUMENTATION_STATUS_CODE: ' + status);
    console.log('OK (9 tests)'); process.exit(status === 'exit-code' ? 1 : 0);
  }
  if (cls.split(',').includes('dev.edgecompanion.app.AnswerQualityTest')) {
    const { candidateDirectory, expectedLoads, fixtureDirectory, fixtureNameForSet, fixturePassages, loadCandidates,
      loadFixture, schedule, sha256, profile, sampler, promptVersion } = require(join(process.cwd(), 'scripts/answer-quality.mjs'));
    const runId = option('supervisionRunId'), set = option('answerQualitySet'), passes = Number(option('answerQualityPasses'));
    const name = fixtureNameForSet(set), fixture = loadFixture(fixtureDirectory(name), name);
    const mode = process.env.FAKE_QUALITY;
    const candidateLock = rest.includes('candidates') ? option('candidates') : null;
    const round = process.env.FAKE_CANDIDATE_ROUND;
    const candidates = candidateLock ? loadCandidates(candidateDirectory(round), candidateLock) : null;
    const library = Object.entries(fixture.library).map(([file, text], index) =>
      ({ file, sourceId: index + 1, revisionId: index + 100, sha256: sha256(text) }));
    const indexedPassages = [];
    for (const source of library) {
      let offset = 0;
      for (const text of fixturePassages(fixture.library[source.file])) {
        indexedPassages.push({ passageId: indexedPassages.length + 1, sourceId: source.sourceId,
          revisionId: source.revisionId, startUtf16: offset, endUtf16: offset + text.length, textSha256: sha256(text) });
        offset += text.length;
      }
    }
    // Consulted references point at the indexed passages that hold each item's gold quotes.
    const consultedFor = id => fixture.items.find(item => item.id === id).answerSpans.map((span, index) => {
      const source = library.find(entry => entry.file === span.file);
      const { textSha256, ...ref } = indexedPassages.find(entry => entry.sourceId === source.sourceId &&
        fixture.library[span.file].slice(entry.startUtf16, entry.endUtf16).includes(span.text));
      return { ...ref, position: index + 1 };
    });
    const cases = schedule(fixture.items, set, passes, candidates).map(row => {
      const candidate = candidates && candidates.entries.find(entry => entry.id === row.candidateId);
      const consulted = candidate?.id === 'exact-match' && row.id === 'answer-one-01' ? [] : consultedFor(row.id);
      return { ...row, profile, sampler, promptVersion: candidate?.version ?? promptVersion,
        ...(candidate ? { candidateTextSha256: candidate.textSha256 } : {}),
        answer: 'Synthetic captured answer', typedResult: null, error: null, consulted,
        packedPassageIds: consulted.map(ref => ref.passageId), modelCalls: 1, promptSha256: sha256(row.id),
        outputSha256: sha256('Synthetic captured answer'), timings: { elapsedMs: 30, modelMs: 20, firstTextMs: 5 },
        retrieval: { status: 'COMPLETE', reason: null, indexedRevisions: library.length, totalRevisions: library.length },
        decodeTokens: 4, inputTokens: 300, possiblyAtCap: false };
    });
    if (mode === 'missing-item') cases.pop();
    if (mode === 'thermal-row') Object.assign(cases.at(-1), {answer: null, error: 'THERMAL', outputSha256: null});
    if (mode === 'cooldown-timeout') cases.splice(0);
    if (mode === 'interrupted' || mode === 'invariant') cases.splice(2);
    const engineLoads = mode === 'cooldown-timeout' ? [] : expectedLoads(set, passes, candidates);
    const candidateCooldowns = engineLoads.map(({pass, scope, candidateId}) => ({
      pass, scope, candidateId, thermalStatusAtLoad: 0, waitMs: 15000,
      availableMemoryBeforeLoadBytes: 5000000000, availableMemoryAfterLoadBytes: 4100000000,
    }));
    write('no_backup/answer-quality-' + runId + '.json', { schema: 1, runId,
      status: mode === 'interrupted' ? 'running' : mode === 'invariant' ? 'failed' : 'collected',
      ...(mode === 'invariant' ? { failureType: 'HarnessInvariant', invariantBreach: 'message-count' } : {}),
      ...(mode === 'cooldown-timeout' ? {status: 'failed', preconditionFailure: 'thermal-cooldown-timeout'} : {}),
      set, passes: mode === 'wrong-passes' ? 3 : passes,
      library, indexedPassages, cases, engineLoads, indexingComplete: true,
      ...(candidateLock ? {candidateLockSha256: mode === 'wrong-candidate-lock' ? '0'.repeat(64) : candidateLock} : {}),
      ...(candidateLock && mode !== 'missing-cooldown' ? {candidateCooldowns} : {}),
      cleanupConfirmed: mode !== 'interrupted', databaseCleanupConfirmed: mode !== 'interrupted',
      fixtureLockSha256: mode === 'wrong-lock' ? 'b'.repeat(64) : option('answerQualityLockSha256'),
      librarySha256: fixture.lock.librarySha256, device: 'SM-S928B', buildType: 'secureQa',
      backend: 'GPU', automaticRetries: 0, automaticFallback: false, profile, sampler, promptVersion,
      modelSha256: process.env.FAKE_MODEL_SHA, runtimeVersion: '0.17.1' });
    if (mode === 'interrupted') { hang(); return; }
    if (mode === 'invariant') { console.log('FAILURES!!!'); console.log('Tests run: 1,  Failures: 1'); process.exit(0); }
    write('no_backup/qa-supervision/' + runId + '/status.json', { schema: 2, runId, status: 'closed', reason: null,
      cleanupConfirmed: false, elapsedMs: 100, capturedAtMs: Date.now(), leaseMs: 20000, pollMs: 500, cleanupMs: 5000 });
    if (mode === 'skipped') console.log('INSTRUMENTATION_STATUS_CODE: -4');
    const ranCandidateTest = cls.split(',').includes('dev.edgecompanion.app.support.AnswerQualityCandidateTest') &&
      mode !== 'one-test';
    console.log(ranCandidateTest ? 'OK (2 tests)' : 'OK (1 test)'); process.exit(0);
  }
  if (cls === '${uiClasses}') {
    if (process.env.FAKE_INSTRUMENTATION === 'hang') { hang(); return; }
    // Synthetic captures: 40 PNGs by default; FAKE_SCREENSHOTS varies the set the device leaves behind.
    const runId = option('passagesCaptureRunId');
    const shots = process.env.FAKE_SCREENSHOTS || '';
    const dir = 'no_backup/passages-captures/' + runId;
    mkdirSync(join(device, dir), { recursive: true });
    const count = shots === 'missing' ? 39 : shots === 'extra' ? 41 : 40;
    const captures = {};
    for (let i = 1; i <= count; i++) {
      const name = String(1 + (i - 1) % 9).padStart(2, '0') + '-synthetic-capture-' + i + '.png';
      const png = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from('synthetic ' + i)]);
      const bytes = shots === 'not-png' && i === 1 ? Buffer.from('synthetic text') : png;
      writeFileSync(join(device, dir, name), bytes);
      // As PassagesCaptureTest records it on the device: size and sha256 of the written PNG.
      captures[name] = { item: 1 + (i - 1) % 9, widthPx: 1080, heightPx: 2000, bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex') };
    }
    if (shots === 'metadata-hash') captures[Object.keys(captures)[0]].sha256 = 'c'.repeat(64);
    if (shots === 'bad-name') writeFileSync(join(device, dir, 'synthetic-notes.txt'), 'synthetic');
    if (shots === 'metadata-mismatch') delete captures[Object.keys(captures)[0]];
    if (shots !== 'no-metadata') write(dir + '.json', { schema: 1, runId: shots === 'metadata-run-id' ? runId + '-other' : runId, captures });
    const status = process.env.FAKE_INSTRUMENTATION;
    if (/^-[1-4]$/.test(status)) console.log('INSTRUMENTATION_STATUS_CODE: ' + status);
    console.log(status === 'old-count' ? 'OK (26 tests)' : 'OK (27 tests)'); process.exit(status === 'exit-code' ? 1 : 0);
  }
}
console.error('Unexpected synthetic adb call'); process.exit(1);
`;

// Control when the owned monitor publishes a marker without relying on the live monitor's sample interval.
const finalizationMonitor = `
import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
const dir = process.env.THERMAL_REPORT_DIR;
const mode = process.env.FAKE_FINALIZATION;
const publish = () => {
  writeFileSync(join(dir, 'safety-stop.json'), JSON.stringify({reason: 'thermal_limit', status: 3, delivery: 'pending'}));
  process.exit(2);
};
// The real monitor has no handler while the stop publish is outstanding.
if (mode !== 'killed-mid-stop') process.on('SIGTERM', () => {
  if (mode === 'during-join') setTimeout(publish, 250);
  else process.exit(0);
});
mkdirSync(dir, {recursive: true});
writeFileSync(join(dir, 'monitor-pid'), String(process.pid));
writeFileSync(join(dir, 'samples.csv'), 'recorded_at,thermal_status,skin_c\\n2026-11-23,0,30\\n');
const deadline = Date.now() + 30000;
setInterval(() => {
  if (existsSync(join(dir, 'capture-pull'))) {
    if (mode === 'after-exit') publish();
    if (mode === 'killed-mid-stop' && !existsSync(join(dir, 'stop-publish-started')))
      writeFileSync(join(dir, 'stop-publish-started'), '');
  }
  if (Date.now() >= deadline || process.ppid !== Number(process.env.QA_SUPERVISOR_PID)) process.exit(3);
}, 10);
`;

// Synthetic build-tools aapt2 reads the same package ID from a text stub or a ZIP marker.
const fakeAapt2 = '#!/bin/sh\n[ "$1" = dump ] && [ "$2" = packagename ] && [ -f "$3" ] || exit 2\n' +
  'unzip -p "$3" package-id.txt 2>/dev/null || head -n 1 "$3"\n';
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
  // FAKE_GRADLE_APPEND names a file the synthetic build changes (S6Q2-F5); it is only ever a sandbox mirror path.
  writeFileSync(join(bin, 'gradlew'), '#!/bin/sh\nprintf "%s\\n" "$*" >> "$FAKE_GRADLE_LOG"\n' +
    'if [ -n "$FAKE_GRADLE_APPEND" ]; then printf "// changed during build\\n" >> "$FAKE_GRADLE_APPEND"; fi\n');
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

function packageQualityAssets(dir, issue, round) {
  const contents = join(dir, 'quality-apk');
  const assets = join(contents, 'assets');
  mkdirSync(assets, {recursive: true});
  writeFileSync(join(contents, 'package-id.txt'), `${qaPackage}.test\n`);
  const fixture = 'answer-quality-dev-v1';
  cpSync(join(root, 'planning/fixtures', fixture), join(assets, fixture), {recursive: true});
  for (const name of ['candidates.json', 'candidates.lock.json']) {
    if (issue === 'missing-candidate' && name === 'candidates.json') continue;
    cpSync(join(root, `planning/fixtures/grounded-prompt-candidates-${round}`, name), join(assets, name));
  }
  if (round === 'r2') {
    const source = join(assets, 'source-r1');
    mkdirSync(source);
    for (const name of ['candidates.json', 'candidates.lock.json']) {
      if (issue === 'missing-source-lock' && name === 'candidates.lock.json') continue;
      cpSync(join(root, 'planning/fixtures/grounded-prompt-candidates-r1', name), join(source, name));
    }
  }
  if (issue === 'changed-dev') writeFileSync(join(assets, fixture, 'library/brackenshivor-close.txt'), 'Changed synthetic text.');
  const apk = join(dir, testApk);
  rmSync(apk);
  execFileSync('zip', ['-q', '-r', apk, 'package-id.txt', 'assets'], {cwd: contents});
}

function run(dir, args, extra = {}, { signal, repo = null, assetIssue = null, finalization = null } = {}) {
  const round = args.includes('--candidates') ? args[args.indexOf('--candidates') + 1] : 'r1';
  if (args[0] === 'quality' && (args[2] === 'dev' || args[2] === 'tuning' && args.includes('--candidates')))
    packageQualityAssets(dir, assetIssue, round);
  if (finalization) {
    const monitor = join(dir, 'finalization-monitor.mjs');
    const shim = join(dir, 'bin/node');
    writeFileSync(monitor, finalizationMonitor);
    writeFileSync(shim, '#!/bin/sh\nif [ "$1" = scripts/monitor-model-thermal.mjs ]; then\n' +
      '  exec "$FAKE_NODE" "$FAKE_MONITOR"\nfi\nexec "$FAKE_NODE" "$@"\n', {mode: 0o700});
    extra = {...extra, FAKE_FINALIZATION: finalization, FAKE_MONITOR: monitor, FAKE_NODE: process.execPath};
  }
  const env = { ...process.env, PATH: `${join(dir, 'bin')}:${process.env.PATH}`, ANDROID_SERIAL: serial,
    ANDROID_HOME: join(dir, 'sdk'), ADB: join(dir, 'bin/adb'), GRADLE: join(dir, 'bin/gradlew'),
    APK_ROOT: join(dir, 'apk'), RESULTS_ROOT: join(dir, 'results'), FAKE_DEVICE_DIR: join(dir, 'device'),
    FAKE_CANDIDATE_ROUND: round,
    FAKE_ADB_LOG: join(dir, 'adb.jsonl'), FAKE_GRADLE_LOG: join(dir, 'gradle.txt'), FAKE_MODEL_SHA: modelSha, ...extra };
  for (const name of ['QA_SUPERVISION_RUN_ID', 'QA_SUPERVISOR_PID', 'MEMORY_POLICY_REPORT', 'THERMAL_REPORT_DIR',
    'CHAT_CONTEXT_DEADLINE_SECONDS', 'PASSAGE_DEADLINE_SECONDS', 'ANSWER_QUALITY_DEADLINE_SECONDS',
    'FAKE_GRADLE_APPEND', 'FAKE_QUALITY', 'FAKE_FINALIZATION', 'FAKE_FINAL_THERMAL'])
    if (!(name in extra)) delete env[name];
  return new Promise(done => {
    const child = spawn('/bin/bash', [repo ? join(repo, 'scripts/verify-chat-context.sh') : script, ...args],
      { cwd: repo ?? root, env, stdio: ['ignore', 'pipe', 'pipe'] });
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

for (const [mode, count] of [['passage-only', 3], ['old-count', 8]]) {
  test(`the SQLCipher gate fails when only ${count} tests ran`, () => withSandbox(async dir => {
    const result = await run(dir, ['sqlcipher'], { FAKE_INSTRUMENTATION: mode });
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.reason, 'instrumentation');
    assert.equal(summary.stage, 'validation');
    assertClientStopped(result, summary);
  }));
}

// S4-F8: FAILURE_PATTERN rejects these status codes before the OK count is read.
for (const code of [-1, -2, -3, -4]) {
  test(`the SQLCipher gate fails on status ${code} even beside OK (9 tests)`, () => withSandbox(async dir => {
    const result = await run(dir, ['sqlcipher'], { FAKE_INSTRUMENTATION: String(code) });
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
  ['a safety stop written during the run', ['measure', '4096'], { FAKE_INSTRUMENTATION: 'safety-stop' }, 'thermal-stop', 'validation'],
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

// S5 device runner: `ui` runs the Compose UI classes and pulls the run's captures from secureQa.
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');

test('ui mode runs the four UI classes, pulls 40 captures and records their hashes', () => withSandbox(async dir => {
  const result = await run(dir, ['ui']);
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'passed');
  assert.equal(summary.mode, 'ui');
  assert.equal(summary.deadlineSeconds, 900);
  assertClientStopped(result, summary);
  assertOnlySecureQa(result.calls);
  assert.match(readFileSync(join(dir, 'gradle.txt'), 'utf8'), /^--offline -PtestBuildType=secureQa :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest /);
  assert.equal(result.installs.length, 2);
  for (const install of result.installs) assert.ok(install.path.startsWith(join(result.results, 'apks') + '/'));
  const instrument = result.calls.find(isRunInstrument).args;
  assert.deepEqual(instrument.slice(2, 8), ['shell', 'am', 'instrument', '-w', '-r', '-e']);
  assert.equal(instrument[8], 'class');
  assert.equal(instrument[9], uiClasses);
  assert.deepEqual(instrument.slice(10, 13), ['-e', 'passagesCaptureRunId', summary.runId]);
  const evidence = result.json('evidence.json');
  assert.equal(evidence.captureMetadata, 'present');
  assert.equal(evidence.screenshotList, 'present');
  assert.equal(evidence.expectedScreenshots, 40);
  assert.equal(evidence.screenshotCount, 40);
  assert.deepEqual(evidence.pullFailures, []);
  const pulled = readdirSync(join(result.results, 'screenshots')).sort();
  assert.equal(pulled.length, 40);
  assert.deepEqual(Object.keys(evidence.screenshots).sort(), pulled);
  const remote = join(dir, 'device/no_backup/passages-captures', summary.runId);
  for (const name of pulled) {
    assert.match(name, /^[0-9]{2}-[a-z0-9-]+\.png$/);
    const bytes = readFileSync(join(result.results, 'screenshots', name));
    assert.equal(evidence.screenshots[name].sha256, sha256(bytes), `manifest hash for ${name}`);
    assert.equal(evidence.screenshots[name].bytes, bytes.length);
    assert.equal(sha256(readFileSync(join(remote, name))), sha256(bytes), `pulled bytes match the device file ${name}`);
  }
  // Every capture pull reads this run's directory in the secureQa app, and nothing else.
  const pulls = result.calls.filter(call => call.args[2] === 'exec-out' && call.args.at(-1).endsWith('.png'));
  assert.equal(pulls.length, 40);
  for (const { args } of pulls) assert.ok(args.at(-1).startsWith(`no_backup/passages-captures/${summary.runId}/`));
  assert.equal(result.json('capture-metadata.json').runId, summary.runId);
}));

test('ui mode rejects extra arguments before any build or device call', () => withSandbox(async dir => {
  const result = await run(dir, ['ui', 'extra']);
  assert.equal(result.code, 2, result.output);
  assert.equal(result.results, null);
  assert.equal(result.calls.length, 0);
}));

const uiFailures = [
  ['a wrong test count', { FAKE_INSTRUMENTATION: 'old-count' }, 'instrumentation'],
  ...[-1, -2, -3, -4].map(code => [`status ${code} beside OK (27 tests)`, { FAKE_INSTRUMENTATION: String(code) }, 'instrumentation']),
  ['a missing screenshot', { FAKE_SCREENSHOTS: 'missing' }, 'screenshot-count'],
  ['an extra screenshot', { FAKE_SCREENSHOTS: 'extra' }, 'screenshot-count'],
  ['an extra file that is not a capture', { FAKE_SCREENSHOTS: 'bad-name' }, 'screenshot-name'],
  ['a capture that is not a PNG', { FAKE_SCREENSHOTS: 'not-png' }, 'screenshot-format'],
  ['missing capture metadata', { FAKE_SCREENSHOTS: 'no-metadata' }, 'capture-metadata'],
  ['metadata that names other captures', { FAKE_SCREENSHOTS: 'metadata-mismatch' }, 'capture-metadata'],
  // QA round 1 (S5R-F2, S5R-F3).
  ['metadata from another run', { FAKE_SCREENSHOTS: 'metadata-run-id' }, 'capture-metadata'],
  ['a device hash that differs from the pulled file', { FAKE_SCREENSHOTS: 'metadata-hash' }, 'screenshot-pull-mismatch'],
  ['a truncated pull that keeps the PNG signature', { FAKE_TRUNCATE_PULL: '-capture-1.png' }, 'screenshot-pull-mismatch'],
  ['a listing without its end marker', { FAKE_LISTING: 'no-marker' }, 'screenshots-unavailable'],
  ['a failed capture pull', { FAKE_PULL_FAIL: '-capture-2.png' }, 'screenshot-pull-failed'],
  ['a listing that repeats a pulled name', { FAKE_LISTING_EXTRA: '01-synthetic-capture-1.png' }, 'screenshots-unavailable'],
  ['a non-zero instrumentation exit beside OK (27 tests)', { FAKE_INSTRUMENTATION: 'exit-code' }, 'instrumentation'],
];
for (const [name, extra, reason] of uiFailures) {
  test(`ui mode fails on ${name} (${reason})`, () => withSandbox(async dir => {
    const result = await run(dir, ['ui'], extra);
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.status, 'failed');
    assert.equal(summary.reason, reason);
    assert.equal(summary.stage, 'validation');
    assertClientStopped(result, summary);
    assertOnlySecureQa(result.calls);
    // Whatever was pulled is still kept and hashed for inspection.
    const evidence = result.json('evidence.json');
    for (const [file, entry] of Object.entries(evidence.screenshots))
      assert.equal(entry.sha256, sha256(readFileSync(join(result.results, 'screenshots', file))));
  }));
}

test('ui mode never pulls a listed name outside NN-name.png, including path traversal', () => withSandbox(async dir => {
  const unsafe = ['../01-escape.png', '01-a/../02-b.png', 'sub/01-nested.png', '/data/01-absolute.png', '01-Upper.png'];
  const result = await run(dir, ['ui'], { FAKE_LISTING_EXTRA: unsafe.join('|') });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.reason, 'screenshot-name');
  assert.equal(summary.stage, 'validation');
  const pulled = result.calls.filter(call => call.args[2] === 'exec-out').map(call => call.args.at(-1));
  for (const name of unsafe) assert.ok(!pulled.some(path => path.endsWith(name)), `not pulled: ${name}`);
  assert.equal(pulled.filter(path => path.endsWith('.png')).length, 40, 'only the 40 valid captures were pulled');
  assert.deepEqual(readdirSync(join(result.results, 'screenshots')).length, 40);
  assert.ok(!existsSync(join(result.results, '01-escape.png')) && !existsSync(join(dir, 'results', '01-escape.png')));
  assertOnlySecureQa(result.calls);
}));

test('ui evidence records why the listing or a pull failed', () => withSandbox(async dir => {
  const noMarker = await run(dir, ['ui'], { FAKE_LISTING: 'no-marker' });
  assert.equal(noMarker.json('evidence.json').screenshotList, 'unavailable');
  const shots = join(noMarker.results, 'screenshots');
  assert.ok(!existsSync(shots) || readdirSync(shots).length === 0, 'nothing pulled without a confirmed listing');
  assert.equal(noMarker.calls.filter(call => call.args[2] === 'exec-out' && call.args.at(-1).endsWith('.png')).length, 0);
}));

test('a failed capture pull is recorded and its partial file removed', () => withSandbox(async dir => {
  const result = await run(dir, ['ui'], { FAKE_PULL_FAIL: '-capture-2.png' });
  assert.equal(result.json('result.json').reason, 'screenshot-pull-failed');
  const evidence = result.json('evidence.json');
  assert.deepEqual(evidence.pullFailures, ['02-synthetic-capture-2.png']);
  assert.ok(!existsSync(join(result.results, 'screenshots', '02-synthetic-capture-2.png')));
  assert.equal(evidence.screenshotCount, 39);
}));

// S6: all commands use the sandbox's fake adb and Gradle, including interruption tests.
for (const [set, passes, items, deadline] of [['tuning', 1, 10, 1740], ['heldout', 3, 306, 38460]]) {
  test(`quality captures ${set} with ${passes} pass(es) and binds the fixture, commit, and APKs`, () => withSandbox(async dir => {
    const result = await run(dir, ['quality', '--set', set, ...(passes === 3 ? ['--passes', '3'] : [])]);
    assert.equal(result.code, 0, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.mode, 'quality');
    assert.equal(summary.status, 'passed');
    assert.equal(summary.deadlineSeconds, deadline);
    assert.equal(summary.qualitySet, set);
    assert.equal(summary.qualityPasses, passes);
    assert.match(summary.fixtureLockSha256, /^[a-f0-9]{64}$/);
    const report = result.json('answer-quality.json');
    assert.equal(report.cases.length, items);
    assert.ok(report.cases.some(row => row.consulted.length > 0), 'the fake capture consults passages');
    const precheck = result.json('precheck.json');
    assert.equal(precheck.references.passed, true);
    assert.equal(precheck.references.resolved, precheck.references.total);
    assert.ok(precheck.references.total > 0, 'references were consulted and resolved');
    assert.equal(summary.deadlineSource, 'formula');
    // Untracked S6 files are bound by content hash (S6Q-F9).
    const binding = result.json('quality-source-binding.json');
    for (const name of ['scripts/answer-quality.mjs',
      'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/AnswerQualityTest.kt',
      'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/AnswerQualityFixture.kt',
      'planning/fixtures/answer-quality-v1/answer-quality-v1.lock.json'])
      assert.equal(binding.files[name], sha256(readFileSync(join(root, name))), name);
    assert.equal(binding.files['planning/fixtures/answer-quality-v1/answer-quality-v1.lock.json'], summary.fixtureLockSha256);
    assert.equal(report.engineLoads.length, passes * (set === 'heldout' ? 2 : 1));
    assert.equal(result.json('precheck.json').cases.length, items);
    assert.equal(result.json('capture-summary.json').determinism.identical, passes === 1 ? null : true);
    assert.equal(sha256(readFileSync(join(result.results, 'fixture/answer-quality-v1.lock.json'))), summary.fixtureLockSha256);
    assert.equal(existsSync(join(result.results, 'app-head.txt')), true);
    assert.equal(existsSync(join(result.results, 'apks.sha256')), true);
    const instrument = result.calls.find(isRunInstrument).args.join(' ');
    assert.match(instrument, / -e class dev\.edgecompanion\.app\.AnswerQualityTest -e answerQuality true -e supervisedThermal true /);
    assert.ok(instrument.includes(` -e answerQualitySet ${set} -e answerQualityPasses ${passes} -e answerQualityLockSha256 ${summary.fixtureLockSha256} `));
    assertOnlySecureQa(result.calls);
    assertClientStopped(result, summary);
    assert.ok(!alive(summary.monitorPid), 'monitor stopped');
  }));
}

for (const [scenario, reason] of [['wrong-lock', 'fixture-hash'], ['missing-item', 'quality-items'],
  ['skipped', 'instrumentation'], ['wrong-passes', 'quality-passes']]) {
  test(`quality rejects a synthetic ${scenario} capture`, () => withSandbox(async dir => {
    const result = await run(dir, ['quality', '--set', 'tuning'], { FAKE_QUALITY: scenario });
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.stage, 'validation');
    assert.equal(summary.reason, reason);
    assert.equal(result.json('evidence.json').answerQuality, 'present');
    assertClientStopped(result, summary);
    assert.ok(!alive(summary.monitorPid), 'monitor stopped');
  }));
}

test('quality retains completed items and stops owned clients on interruption', () => withSandbox(async dir => {
  const result = await run(dir, ['quality', '--set', 'tuning'],
    { FAKE_QUALITY: 'interrupted', QA_STOP_GRACE_SECONDS: '2' }, { signal: 'SIGTERM' });
  assert.equal(result.code, 143, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'failed');
  assert.equal(result.json('answer-quality.json').cases.length, 2);
  assert.equal(result.json('evidence.json').answerQuality, 'present');
  assert.equal(result.json('deadline-stop.json').reason, 'interrupted');
  assertClientStopped(result, summary);
  assert.ok(!alive(summary.monitorPid), 'monitor stopped');
}));

test('quality validates the set and explicit pass count before contacting a device', () => withSandbox(async dir => {
  for (const args of [['quality', '--set', 'either'], ['quality', '--set', 'heldout', '--passes', '2']]) {
    const result = await run(dir, args);
    assert.equal(result.code, 2, result.output);
    assert.equal(result.calls.length, 0);
    assert.equal(existsSync(join(dir, 'gradle.txt')), false);
  }
}));

// S6Q2-F5: source-binding checks run against a sandbox mirror of the bound sources, so the repository is never changed.
// The mirror has no Git metadata; a mirror-only git stub answers the two read-only calls the wrapper makes.
const groundingSource = 'shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/session/GroundedPrompt.kt';
const runtimeSource = 'shared/src/androidMain/kotlin/dev/edgecompanion/core/inference/litert/LiteRtLlmEngine.kt';
const fixtureSource = 'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/support/AnswerQualityFixture.kt';
function mirror(dir) {
  const repo = join(dir, 'repo'), stubs = join(dir, 'mirror-bin');
  for (const path of ['scripts/verify-chat-context.sh', 'scripts/answer-quality.mjs', 'planning/fixtures/answer-quality-v1',
    'planning/fixtures/answer-quality-dev-v1',
    'planning/fixtures/grounded-prompt-candidates-r1/candidates.json',
    'planning/fixtures/grounded-prompt-candidates-r1/candidates.lock.json',
    'planning/models/gemma-4-e2b-it.json', groundingSource, runtimeSource, fixtureSource,
    'androidApp/src/androidTest/kotlin/dev/edgecompanion/app/AnswerQualityTest.kt'])
    cpSync(join(root, path), join(repo, path), { recursive: true });
  mkdirSync(stubs);
  writeFileSync(join(stubs, 'git'), '#!/bin/sh\ncase "$1" in rev-parse) echo ' + 'a'.repeat(40) + ' ;; diff) ;; *) exit 1 ;; esac\n');
  chmodSync(join(stubs, 'git'), 0o755);
  return { repo, path: `${stubs}:${join(dir, 'bin')}:${process.env.PATH}` };
}

test('quality fails when a bound source changes during the build', () => withSandbox(async dir => {
  const { repo, path } = mirror(dir);
  const result = await run(dir, ['quality', '--set', 'tuning'],
    { PATH: path, FAKE_GRADLE_APPEND: join(repo, fixtureSource) }, { repo });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.reason, 'quality-source-binding');
  assert.equal(existsSync(join(dir, 'gradle.txt')), true, 'the pre-build binding passed and the build ran');
  assert.equal(existsSync(join(result.results, 'apks.sha256')), true);
  assert.equal(result.calls.filter(call => call.args.includes('install')).length, 0, 'nothing was installed');
  assert.equal(readFileSync(join(root, fixtureSource), 'utf8').includes('changed during build'), false);
}));

for (const [name, file, from, to] of [
  ['prompt passage limit', groundingSource, 'const val MAX_PASSAGES = 4', 'const val MAX_PASSAGES = 5'],
  ['prompt version', groundingSource, 'const val VERSION = "grounded-prompt-v1"', 'const val VERSION = "grounded-prompt-v2"'],
  ['sampler', runtimeSource, 'SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)',
    'SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 1)'],
]) {
  test(`quality refuses ${name} drift before building`, () => withSandbox(async dir => {
    const { repo, path } = mirror(dir);
    const source = readFileSync(join(repo, file), 'utf8');
    assert.ok(source.includes(from), `mirror source contains ${from}`);
    // The last occurrence is the ordinary generation call the binding reads; the first sampler is another path.
    const at = source.lastIndexOf(from);
    writeFileSync(join(repo, file), source.slice(0, at) + to + source.slice(at + from.length));
    const result = await run(dir, ['quality', '--set', 'tuning'], { PATH: path }, { repo });
    assert.equal(result.code, 1, result.output);
    assert.equal(result.json('result.json').reason, 'quality-source-binding');
    assert.equal(existsSync(join(dir, 'gradle.txt')), false, 'no build after drift');
    assert.equal(result.calls.filter(call => call.args.includes('install')).length, 0);
  }));
}

test('quality fails a broken harness invariant with its own reason, not an item Error', () => withSandbox(async dir => {
  const result = await run(dir, ['quality', '--set', 'tuning'], { FAKE_QUALITY: 'invariant' });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.stage, 'validation');
  assert.equal(summary.reason, 'quality-invariant');
  const report = result.json('answer-quality.json');
  assert.equal(report.failureType, 'HarnessInvariant');
  assert.equal(report.invariantBreach, 'message-count');
  assert.equal(report.cases.length, 2);
  assert.equal(existsSync(join(result.results, 'precheck.json')), false);
  assertClientStopped(result, summary);
  assert.ok(!alive(summary.monitorPid), 'monitor stopped');
}));

test('a quality run that outlives its deadline publishes a run stop and keeps completed items', () => withSandbox(async dir => {
  const result = await run(dir, ['quality', '--set', 'tuning'],
    { FAKE_QUALITY: 'interrupted', ANSWER_QUALITY_DEADLINE_SECONDS: '2', QA_STOP_GRACE_SECONDS: '2' });
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'failed');
  assert.equal(summary.stage, 'deadline');
  assert.equal(summary.reason, 'deadline');
  assert.equal(summary.deadlineSeconds, 2);
  assert.equal(summary.deadlineSource, 'override');
  const stop = result.json('deadline-stop.json');
  assert.equal(stop.reason, 'deadline');
  assert.equal(stop.runId, summary.runId);
  assert.equal(stop.stopRequested, true);
  const published = JSON.parse(readFileSync(join(dir, 'device/no_backup/qa-supervision', summary.runId, 'stop.json'), 'utf8'));
  assert.deepEqual(published, { schema: 2, runId: summary.runId, action: 'stop' });
  assert.equal(result.json('answer-quality.json').cases.length, 2, 'completed items retained');
  assert.equal(result.json('evidence.json').answerQuality, 'present');
  assertClientStopped(result, summary);
  assert.ok(!alive(summary.monitorPid), 'monitor stopped');
}));

const developmentArgs = ['quality', '--set', 'dev', '--candidates', 'r1'];
const developmentCandidateFile = 'planning/fixtures/grounded-prompt-candidates-r1/candidates.json';

test('quality binds one dev block per candidate and produces a packing comparison and rubric report', () => withSandbox(async dir => {
  const result = await run(dir, developmentArgs);
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.deadlineSeconds, 300 + (240 + 900) * 5 + 120 * 200);
  assert.match(readFileSync(join(result.results, 'quality-assets.txt'), 'utf8'), /Verified 14 staged test APK assets/);
  const report = result.json('answer-quality.json');
  assert.equal(report.cases.length, 200);
  assert.equal(report.candidateLockSha256, summary.candidateLockSha256);
  const ids = ['v1', 'exact-match', 'near-question', 'fixed-refusal', 'exact-fixed'];
  assert.deepEqual(report.engineLoads.map(load => load.candidateId), ids);
  assert.ok(report.engineLoads.every(load => load.scope === 'SOURCES' && load.count === 1));
  assert.equal(result.json('capture-summary.json').candidateLockSha256, summary.candidateLockSha256);
  const binding = result.json('quality-source-binding.json');
  assert.equal(binding.files[developmentCandidateFile], sha256(readFileSync(join(root, developmentCandidateFile))));
  assert.equal(binding.files['planning/fixtures/answer-quality-dev-v1/answer-quality-dev-v1.lock.json'], summary.fixtureLockSha256);
  const instrument = result.calls.find(isRunInstrument).args.join(' ');
  assert.ok(instrument.includes(' -e candidates ' + summary.candidateLockSha256 + ' '));
  assert.match(readFileSync(join(dir, 'gradle.txt'), 'utf8'), /-PanswerQualityCandidateRound=r1/);
  const execute = (...args) => spawnSync(process.execPath, [join(root, 'scripts/answer-quality.mjs'), ...args], {encoding: 'utf8'});
  const compare = execute('compare', result.results);
  assert.equal(compare.status, 0, compare.stderr);
  assert.deepEqual(result.json('compare.json').candidates.map(entry => entry.candidateId), ids);
  assert.deepEqual(result.json('compare.json').candidates[1].packingDifferences, [{
    id: 'answer-one-01', v1: report.cases[0].packedPassageIds, candidate: [],
  }]);
  assert.match(readFileSync(join(result.results, 'compare.md'), 'utf8'), /development set, not a quality result/);

  const labelsFile = join(dir, 'labels.json');
  writeFileSync(labelsFile, JSON.stringify({labels: report.cases.map(({pass, scope, id, candidateId}) => ({
    pass, scope, id, candidateId, label: 'Abstained', reason: 'Synthetic label for tool wiring',
    ...(id.startsWith('diagnostic-contradiction-') ? {notedConflict: false} : {}),
  }))}));
  const scored = execute('report', result.results, '--labels', labelsFile);
  assert.equal(scored.status, 0, scored.stderr);
  assert.equal(result.json('report.json').candidates[0].rubric.unanswerable.labels.Abstained, 20);
  assert.match(readFileSync(join(result.results, 'report.md'), 'utf8'), /development set, not a quality result/);
  assertOnlySecureQa(result.calls);
  assertClientStopped(result, summary);
}));

test('quality refuses heldout candidates and multiple dev passes before a build or device call', () => withSandbox(async dir => {
  for (const args of [['quality', '--set', 'heldout', '--candidates', 'r1'], [...developmentArgs, '--passes', '3']]) {
    const result = await run(dir, args);
    assert.equal(result.code, 2, result.output);
    assert.deepEqual(result.calls, []);
    assert.equal(existsSync(join(dir, 'gradle.txt')), false);
  }
}));

test('quality rejects a captured candidate lock different from the staged lock', () => withSandbox(async dir => {
  const result = await run(dir, developmentArgs, {FAKE_QUALITY: 'wrong-candidate-lock'});
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'candidate-lock');
  assert.equal(existsSync(join(result.results, 'precheck.json')), false);
}));

test('quality rejects a candidate file changed during the build before installing an APK', () => withSandbox(async dir => {
  const {repo, path} = mirror(dir);
  const result = await run(dir, developmentArgs, {PATH: path, FAKE_GRADLE_APPEND: join(repo, developmentCandidateFile)}, {repo});
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'quality-source-binding');
  assert.equal(result.installs.length, 0);
  assert.deepEqual(result.calls.filter(isRunInstrument), []);
}));


for (const [issue, message] of [
  ['missing-candidate', /Expected one staged APK entry: assets\/candidates.json/],
  ['changed-dev', /Staged APK asset differs: assets\/answer-quality-dev-v1\/library\/brackenshivor-close.txt/],
]) {
  test(`quality refuses ${issue} before the first device call`, () => withSandbox(async dir => {
    const result = await run(dir, developmentArgs, {}, {assetIssue: issue});
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.stage, 'quality-assets');
    assert.equal(summary.reason, 'quality-assets');
    assert.match(readFileSync(join(result.results, 'quality-assets.txt'), 'utf8'), message);
    assert.deepEqual(result.calls, []);
  }));
}

test('quality assets-only verifies a tuning candidate APK with no device serial or device commands', () => withSandbox(async dir => {
  const result = await run(dir, ['quality', '--set', 'tuning', '--candidates', 'r1', '--assets-only'], {ANDROID_SERIAL: ''});
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.stage, 'quality-assets');
  assert.equal(summary.assetsOnly, true);
  assert.match(result.output, /Asset check only, no device run/);
  assert.match(readFileSync(join(result.results, 'quality-assets.txt'), 'utf8'), /Verified 14 staged test APK assets/);
  assert.deepEqual(result.calls, []);
  assert.equal(existsSync(join(result.results, 'answer-quality.json')), false);
  for (const command of ['precheck', 'compare', 'report']) {
    const args = command === 'report' ? ['--labels', join(dir, 'labels.json')] : [];
    const refused = spawnSync(process.execPath, [join(root, 'scripts/answer-quality.mjs'), command, result.results, ...args],
      {encoding: 'utf8'});
    assert.equal(refused.status, 1, refused.stderr);
    assert.match(refused.stderr, /asset check only, no device run to analyze/);
    assert.equal(existsSync(join(result.results, `${command}.json`)), false);
  }
}));

const pairArgs = ['quality', '--set', 'dev', '--candidates', 'r2'];
const stopMeasureArgs = ['measure', '4096', '--repeat', '1'];

for (const [probe, args] of [
  ['blocked', stopMeasureArgs], ['blocked', pairArgs], ['skin-only', pairArgs],
  ['delivery-error', stopMeasureArgs], ['intent-error', stopMeasureArgs], ['clean', pairArgs],
]) {
  test(`real monitor stop intent handles ${probe} in ${args[0]}`, () => withSandbox(async dir => {
    const result = await run(dir, args, { FAKE_MONITOR_STOP: probe });
    const summary = result.json('result.json');
    const marker = join(result.results, 'thermal/safety-stop.json');
    const clean = probe === 'clean';
    assert.equal(result.code, clean ? 0 : 1, result.output);
    assert.equal(summary.status, clean ? 'passed' : 'failed');
    assert.equal(summary.reason, clean ? null : probe === 'intent-error' ? 'thermal-monitor' : 'thermal-stop');
    assert.ok(!alive(summary.monitorPid), 'monitor joined');
    assert.equal(result.json('evidence.json').thermalFinal, 'present');
    const final = readFileSync(join(result.results, 'thermal-final.txt'), 'utf8');
    assert.match(final, /Thermal Status: 2\b/);
    assert.match(final, /mValue=44\.9,/);
    if (clean) {
      assert.ok(!existsSync(marker));
      assert.ok(!existsSync(join(result.results, 'probe-at-dispatch.json')));
      return;
    }

    const atDispatch = result.json('probe-at-dispatch.json');
    if (probe === 'intent-error') {
      assert.equal(atDispatch, null, 'stop was attempted without a written intent');
      assert.ok(!existsSync(marker));
      assert.match(readFileSync(join(result.results, 'thermal-monitor.txt'), 'utf8'), /thermal-stop-intent-write-failed/);
      return;
    }

    const event = { runId: summary.runId, reason: 'thermal_limit', recordedAt: atDispatch.recordedAt,
      status: probe === 'skin-only' ? 2 : 3, skin: probe === 'skin-only' ? 45 : 45.1 };
    assert.equal(new Date(atDispatch.recordedAt).toISOString(), atDispatch.recordedAt);
    assert.deepEqual(atDispatch, { ...event, delivery: 'pending' });
    const outcome = probe === 'skin-only' ? { delivery: 'requested', stopRequested: true }
      : { delivery: 'failed', hostStopError: 'Error' };
    assert.deepEqual(result.json('thermal/safety-stop.json'), { ...event, ...outcome });
  }));
}

test('pair assets-only checks the frozen source and budgets 900 seconds of cooling per block', () => withSandbox(async dir => {
  const result = await run(dir, [...pairArgs, '--assets-only'], {ANDROID_SERIAL: ''});
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.deadlineSeconds, 12180);
  assert.equal(summary.assetsOnly, true);
  assert.match(readFileSync(join(result.results, 'quality-assets.txt'), 'utf8'), /Verified 16 staged test APK assets/);
  assert.deepEqual(result.calls, []);
  const path = 'planning/fixtures/grounded-prompt-candidates-r1/candidates.lock.json';
  assert.equal(result.json('quality-source-binding.json').files[path], sha256(readFileSync(join(root, path))));
}));

test('pair assets-only refuses an APK missing the frozen source lock', () => withSandbox(async dir => {
  const result = await run(dir, [...pairArgs, '--assets-only'], {ANDROID_SERIAL: ''}, {assetIssue: 'missing-source-lock'});
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'quality-assets');
  assert.match(readFileSync(join(result.results, 'quality-assets.txt'), 'utf8'), /assets\/source-r1\/candidates.lock.json/);
  assert.deepEqual(result.calls, []);
}));

for (const mode of ['measure', 'quality']) for (const finalization of ['after-exit', 'during-join']) {
  test(`thermal finalization rejects the ${mode} marker published ${finalization}`, () => withSandbox(async dir => {
    const args = mode === 'measure' ? ['measure', '4096', '--repeat', '1'] : pairArgs;
    const result = await run(dir, args, {}, {finalization});
    assert.equal(result.code, 1, result.output);
    const summary = result.json('result.json');
    assert.equal(summary.reason, 'thermal-stop');
    assert.equal(summary.status, 'failed');
    assert.ok(!alive(summary.monitorPid), 'monitor joined');
    assertClientStopped(result, summary);
    assert.equal(result.json('thermal/safety-stop.json').status, 3);
    assert.equal(result.json('thermal/safety-stop.json').delivery, 'pending');
    if (mode === 'quality') {
      assert.equal(result.json('answer-quality.json').cases.length, 80);
      assert.equal(result.json('precheck.json').cases.length, 80);
      assert.equal(existsSync(join(result.results, 'capture-summary.json')), false);
    }
  }));
}

test('thermal finalization rejects a THERMAL row without a monitor marker and keeps precheck evidence', () => withSandbox(async dir => {
  const result = await run(dir, pairArgs, {FAKE_QUALITY: 'thermal-row'}, {finalization: 'clean'});
  assert.equal(result.code, 1, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.reason, 'thermal-stop');
  assert.equal(summary.status, 'failed');
  assert.ok(!alive(summary.monitorPid), 'monitor joined');
  assert.equal(existsSync(join(result.results, 'thermal/safety-stop.json')), false);
  assert.equal(result.json('answer-quality.json').cases.at(-1).error, 'THERMAL');
  assert.equal(result.json('precheck.json').cases.at(-1).error, 'THERMAL');
}));

test('thermal finalization leaves a clean verification pair eligible for capture', () => withSandbox(async dir => {
  const result = await run(dir, pairArgs, {}, {finalization: 'clean'});
  assert.equal(result.code, 0, result.output);
  const summary = result.json('result.json');
  assert.equal(summary.status, 'passed');
  assert.ok(!alive(summary.monitorPid), 'monitor joined');
  assert.equal(result.json('capture-summary.json').items, 80);
  const instrument = result.calls.find(isRunInstrument).args;
  assert.equal(instrument[instrument.indexOf('class') + 1],
    'dev.edgecompanion.app.support.AnswerQualityCandidateTest,dev.edgecompanion.app.AnswerQualityTest');
  assert.match(readFileSync(join(result.results, 'instrumentation.txt'), 'utf8'), /^OK \(2 tests\)$/m);
  assert.deepEqual(result.json('precheck.json').candidates.map(candidate => candidate.candidateId), ['v1', 'exact-fixed']);
}));

test('thermal finalization reports a cooldown precondition failure before any case', () => withSandbox(async dir => {
  const result = await run(dir, pairArgs, {FAKE_QUALITY: 'cooldown-timeout'}, {finalization: 'clean'});
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'thermal-cooldown-timeout');
  assert.deepEqual(result.json('answer-quality.json').cases, []);
  assert.deepEqual(result.json('answer-quality.json').engineLoads, []);
}));

for (const mode of ['measure', 'quality']) for (const [sample, reason] of [
  ['severe-status', 'thermal-stop'],
  ['skin-limit', 'thermal-stop'],
  ['malformed', 'thermal-final-unavailable'],
  ['unavailable', 'thermal-final-unavailable'],
]) {
  test(`post-join thermal gate rejects a ${sample} final reading in ${mode} after an interrupted stop publish`, () =>
    withSandbox(async dir => {
      const args = mode === 'measure' ? ['measure', '4096', '--repeat', '1'] : pairArgs;
      const result = await run(dir, args, {FAKE_FINAL_THERMAL: sample}, {finalization: 'killed-mid-stop'});
      assert.equal(result.code, 1, result.output);
      const summary = result.json('result.json');
      assert.equal(summary.status, 'failed');
      assert.equal(summary.reason, reason);
      assert.ok(existsSync(join(result.results, 'thermal/stop-publish-started')));
      assert.ok(!existsSync(join(result.results, 'thermal/safety-stop.json')));
      assert.ok(!alive(summary.monitorPid), 'monitor joined before final read');
      assert.equal(result.calls.filter(call => call.args.join(' ').endsWith('shell dumpsys thermalservice')).length, 1);
      assert.equal(result.json('evidence.json').thermalFinal, sample === 'unavailable' ? 'unavailable' : 'present');
      if (mode === 'quality') assert.equal(result.json('precheck.json').cases.length, 80);
    }));
}

test('post-join thermal gate allows a clean measurement', () => withSandbox(async dir => {
  const result = await run(dir, ['measure', '4096', '--repeat', '1'], {}, {finalization: 'clean'});
  assert.equal(result.code, 0, result.output);
  assert.equal(result.json('result.json').status, 'passed');
  assert.equal(result.json('evidence.json').thermalFinal, 'present');
}));

test('candidate instrumentation rejects a capture where only one test ran', () => withSandbox(async dir => {
  const result = await run(dir, pairArgs, {FAKE_QUALITY: 'one-test'}, {finalization: 'clean'});
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'instrumentation');
  assert.equal(result.calls.filter(isRunInstrument).length, 1);
  assert.equal(result.json('answer-quality.json').cases.length, 80);
}));

test('candidate cooldown gate rejects a complete capture with no cooldown evidence', () => withSandbox(async dir => {
  const result = await run(dir, pairArgs, {FAKE_QUALITY: 'missing-cooldown'}, {finalization: 'clean'});
  assert.equal(result.code, 1, result.output);
  assert.equal(result.json('result.json').reason, 'quality-cooldown-evidence');
  assert.equal(result.json('answer-quality.json').cases.length, 80);
  assert.ok(!existsSync(join(result.results, 'capture-summary.json')));
}));
