import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, mkdirSync, mkdtempSync, copyFileSync, writeFileSync, rmSync, existsSync,
  readdirSync, statSync, chmodSync, symlinkSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { execFileSync, spawnSync } from 'node:child_process';
import { loadV4Plan, classifyV4, V4_ID } from '../v4-feasibility.mjs';
import { createV4Freeze, admitV4Execution, prepareV4Apks, installV4Apks,
  reserveV4Attempt, v4SourceSnapshot, V4_APKS } from '../v4-execution-freeze.mjs';
import { sha256 } from '../v3-feasibility.mjs';

const plan = loadV4Plan();
const expected = { runId: 'synthetic-v4', startedAtMs: 1000, planSha256: sha256(JSON.stringify(plan)),
  freezeSha256: 'synthetic-freeze', policyId: plan.policyId, plan };
function evidence() {
  return { schema: 4, protocolId: V4_ID, runId: expected.runId, startedAtMs: 1100, finishedAtMs: 2000,
    startedAtMonotonicMs: 0, finishedAtMonotonicMs: 400000,
    planSha256: expected.planSha256, executionFreezeSha256: expected.freezeSha256, freezeSha256: plan.freezeSha256,
    policyId: expected.policyId, semanticEnablement: false, largeCampaignAllowed: false, finalCleanup: 'CONFIRMED',
    cases: plan.entries.map((p, i) => ({ id: p.id, state: p.state, packSha256: p.packSha256,
      requestId: `${expected.runId}-v4-${i}`, backend: 'GPU', gemmaInitialState: 'UNLOADED',
      nativeEncoderInitialState: 'no-resident-worker', tokenizerInitialState: i < 4 ? 'cold' : 'cached-admission',
      outcome: 'STRUCTURALLY_VALID', delivery: 'ELIGIBLE', decisionMs: 12000, cleanupMs: 100, wallMs: 12100,
      startedAtMonotonicMs: 0, finishedAtMonotonicMs: 12100, caseGateAccepted: true,
      runtimeCleanup: 'CONFIRMED', encoderCleanup: 'CONFIRMED', runtimeStatus: 'UNLOADED', lowMemory: false,
      thermalStatus: 0, telemetryFailed: false, validation: 'STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED',
      diagnosticOutput: JSON.stringify({ o: 'U', d: p.detailIds.map(id => ({ i: id, s: 'U', e: [] })) }),
      sdkInputTokens: 500, sdkOutputTokens: 100, confirmedWorkerDeath: true, encoderDeathAtMs: 100,
      gemmaLoads: [120], workerInstance: `worker-${i}`, workerEvents: ['CHECKING', 'LOADING', 'ENCODING']
        .map((phase, n) => ({ phase, monotonicMs: n * 10, pid: 200 + i, pssKb: 100000 })),
      memory: { schema: 1, policy: { id: expected.policyId }, startedAtMonotonicMs: 1, elapsedMs: 12000, droppedSamples: 0, failedSamples: 0,
        samples: ['BASELINE', 'LOADING', 'PREFILL', 'DECODING', 'POST_RELEASE'].map((stage, n) => ({ stage,
          elapsedMs: n ? 119 + n : 0, eventElapsedMs: n ? 118 + n : 0,
          policyId: expected.policyId, backend: 'GPU', lowMemory: false, thermalStatus: 0, availableMemoryBytes: 6 * 2 ** 30,
          pressureThresholdBytes: 1000000, appPssBytes: 2000000000, appRssBytes: 2100000000 })) } }))
      .map((c, i) => ({ ...c, startedAtMonotonicMs: i * 40000, finishedAtMonotonicMs: i * 40000 + c.wallMs,
        encoderDeathAtMs: i * 40000 + c.encoderDeathAtMs, gemmaLoads: c.gemmaLoads.map(t => i * 40000 + t),
        workerEvents: c.workerEvents.map(e => ({ ...e, monotonicMs: i * 40000 + e.monotonicMs })),
        memory: { ...c.memory, startedAtMonotonicMs: i * 40000 + c.memory.startedAtMonotonicMs } })) };
}

test('V4 keeps the exact eight calibration packs and changes no V3 timing or quality inputs', () => {
  assert.equal(plan.deadlineMs, 30000); assert.equal(plan.cleanupWithinDeadline, true);
  assert.equal(plan.entries.length, 8); assert.equal(plan.contextTokens, 2048); assert.equal(plan.outputTokens, 512);
  assert.deepEqual(plan.entries.slice(0, 4).map(e => e.id), ['v3k01qc', 'v3k01qm', 'v3k01qs', 'v3k01qt']);
  for (const e of plan.entries) assert.deepEqual(Object.keys(e), ['id', 'state', 'question', 'payload', 'packSha256', 'detailIds', 'passages']);
  assert.equal(classifyV4(evidence(), expected).status, 'passed');
});
test('both exact deadline boundaries block, while one millisecond below is eligible', () => {
  const before = evidence(); before.cases.forEach(c => { c.decisionMs = 29998; c.wallMs = 29999; c.finishedAtMonotonicMs = c.startedAtMonotonicMs + 29999; });
  assert.equal(classifyV4(before, expected).status, 'passed');
  for (const field of ['decisionMs', 'wallMs']) {
    const e = evidence(); e.cases = e.cases.slice(0, 1); e.cases[0][field] = 30000;
    if (field === 'decisionMs') e.cases[0].wallMs = 30001;
    e.cases[0].finishedAtMonotonicMs = e.cases[0].wallMs;
    assert.equal(classifyV4(e, expected).status, 'blocked');
    e.cases.push(evidence().cases[1]); assert.throws(() => classifyV4(e, expected), /continued/);
  }
});
test('timeouts, invalidity, revocation and uncertain cleanup never become successful abstentions', () => {
  for (const edit of [c => { c.outcome = 'TIMED_OUT'; }, c => { c.outcome = 'INVALID_OUTPUT'; },
    c => { c.delivery = 'REVOKED'; }, c => { c.runtimeCleanup = 'UNCERTAIN'; }, c => { c.lowMemory = true; }]) {
    const e = evidence(); e.cases = e.cases.slice(0, 1); edit(e.cases[0]);
    const result = classifyV4(e, expected); assert.equal(result.status, 'blocked'); assert.equal(result.notRunRequests, 7);
  }
});
test('missing, stale, overlapping, duplicated or unsafe telemetry cannot pass', () => {
  for (const edit of [e => { e.executionFreezeSha256 = 'stale'; }, e => { e.cases.pop(); },
    e => { e.cases[1].requestId = e.cases[0].requestId; }, e => { e.cases[1].workerInstance = e.cases[0].workerInstance; },
    e => { e.cases[0].gemmaLoads = [99]; }, e => { e.cases[0].workerEvents = []; },
    e => { e.cases[0].sdkOutputTokens = 513; }, e => { e.cases[0].memory.failedSamples = 1; },
    e => { e.cases[0].memory.samples[0].pressureThresholdBytes = null; }, e => { delete e.cases[0].diagnosticOutput; },
    e => { e.cases[0].telemetryFailed = true; }, e => { e.finalCleanup = 'PENDING'; }]) {
    const e = evidence(); edit(e); assert.throws(() => classifyV4(e, expected));
  }
});
test('stage timing must be ordered, request-bound and exactly reproduce Gemma load observations', () => {
  for (const edit of [c => { c.memory.samples.reverse(); }, c => { delete c.memory.samples[0].elapsedMs; },
    c => { c.memory.startedAtMonotonicMs = 99999999; }, c => { c.gemmaLoads = [121]; },
    c => { c.finishedAtMonotonicMs++; }, c => { c.workerEvents[0].monotonicMs = -1; }]) {
    const e = evidence(); edit(e.cases[0]); assert.throws(() => classifyV4(e, expected));
  }
  const blocked = evidence(); blocked.cases = blocked.cases.slice(0, 1); blocked.cases[0].caseGateAccepted = false;
  assert.equal(classifyV4(blocked, expected).status, 'blocked');
  const overlap = evidence(); overlap.cases[1].startedAtMonotonicMs = 100;
  overlap.cases[1].finishedAtMonotonicMs = 12200;
  assert.throws(() => classifyV4(overlap, expected));
});

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'v4-host-only-'));
  try {
    for (const path of Object.keys(v4SourceSnapshot())) {
      const target = join(root, path); mkdirSync(dirname(target), { recursive: true }); copyFileSync(path, target);
    }
    for (const path of V4_APKS) {
      mkdirSync(dirname(join(root, path)), { recursive: true });
      writeFileSync(join(root, path), Buffer.alloc(100001, 1)); // Synthetic host fixture, never installed.
    }
    const review = join(root, 'synthetic-review.json');
    writeFileSync(review, JSON.stringify({ verdict: 'PASS', scope: 'v4-implementation-and-host-qa',
      snapshotSha256: sha256(JSON.stringify(v4SourceSnapshot(root))), deviceExecutionAuthorized: false }));
    const output = join(root, 'freeze'); const value = createV4Freeze(output, review, root);
    const freeze = join(output, 'execution-freeze.json'); const auth = join(root, 'synthetic-authorization.json');
    writeFileSync(auth, JSON.stringify({ schema: 4, scope: 'single-supervised-v4-eight-request', approved: true,
      freezeSha256: sha256(readFileSync(freeze)), profileSha256: value.profileSha256, serial: 'synthetic',
      requests: 8, deadlineMs: 30000, device: 'SM-S928B' }));
    return run({ root, output, value, freeze, auth, review });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('freeze binds code, APKs, packs, shared profile and review without authorizing a device', () => fixture(f => {
  assert.equal(f.value.deviceExecutionAuthorized, false); assert.equal(f.value.profile.id, plan.policyId);
  assert.equal(admitV4Execution(f.freeze, f.auth, 'synthetic', f.root).frozen.plan.entries.length, 8);
  assert.throws(() => createV4Freeze(f.output, f.review, f.root), /EEXIST/);
  for (const [path, hash] of Object.entries({ ...f.value.sources, ...f.value.apks }))
    assert.equal(sha256(readFileSync(join(f.output, 'snapshot', path))), hash);
}));
test('stale reviews, edited sources and changed APKs invalidate freeze admission', () => fixture(f => {
  const review = JSON.parse(readFileSync(f.review)); review.snapshotSha256 = 'stale'; writeFileSync(f.review, JSON.stringify(review));
  assert.throws(() => createV4Freeze(join(f.root, 'second'), f.review, f.root), /stale independent/);
  const apk = join(f.root, V4_APKS[0]), old = readFileSync(apk); writeFileSync(apk, 'changed');
  assert.throws(() => admitV4Execution(f.freeze, f.auth, 'synthetic', f.root), /stale V4/); writeFileSync(apk, old);
  writeFileSync(join(f.root, 'scripts/v4-feasibility.mjs'), '// modified');
  assert.throws(() => admitV4Execution(f.freeze, f.auth, 'synthetic', f.root), /stale V4/);
}));
test('complete wrapper rejects absent or mismatched later authorization before any ADB access', () => fixture(f => {
  const marker = join(f.root, 'adb-accessed'), fake = join(f.root, 'fake-adb');
  writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`, { mode: 0o755 });
  for (const flags of [[], ['--v4-freeze', f.freeze], ['--v4-freeze', f.freeze, '--v4-authorization', f.auth]]) {
    const result = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--v4-feasibility', ...flags],
      { cwd: f.root, env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'wrong-device' }, encoding: 'utf8' });
    assert.notEqual(result.status, 0); assert.equal(existsSync(marker), false);
    assert.match(result.stderr, /authorization/i);
  }
}));
test('fake ADB installs only private frozen bytes when a concurrent build replaces outputs after admission', () => fixture(f => {
  const admission = admitV4Execution(f.freeze, f.auth, 'synthetic', f.root);
  for (const source of V4_APKS) writeFileSync(join(f.root, source), 'new build before staging');
  const prepared = prepareV4Apks(admission, f.freeze);
  const log = join(f.root, 'installed.jsonl'), fake = join(f.root, 'fake-adb.mjs');
  writeFileSync(fake, `import { readFileSync, writeFileSync, appendFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const args = process.argv.slice(2);
// A separate build happens at the first ADB boundary, after admission and private staging.
for (const path of ${JSON.stringify(V4_APKS.map(p => join(f.root, p)))}) writeFileSync(path, 'new build bytes');
if (args[0] === 'shell') console.log('SM-S928B');
else appendFileSync(${JSON.stringify(log)}, JSON.stringify({ path: args[2],
  sha256: createHash('sha256').update(readFileSync(args[2])).digest('hex') }) + '\\n');
`);
  try {
    const run = (...args) => execFileSync(process.execPath, [fake, ...args], { encoding: 'utf8' });
    assert.equal(run('shell', 'getprop', 'ro.product.model').trim(), 'SM-S928B');
    installV4Apks(prepared, run);
    const installed = readFileSync(log, 'utf8').trim().split('\n').map(JSON.parse);
    assert.deepEqual(installed, prepared.files.map(({ path, sha256 }) => ({ path, sha256 })));
    for (const file of prepared.files) {
      assert.equal(file.sha256, admission.frozen.apks[file.source]);
      assert.notEqual(sha256(readFileSync(join(f.root, file.source))), file.sha256);
      assert.notEqual(file.path, join(f.output, 'snapshot', file.source));
      assert.equal(statSync(dirname(file.path)).mode & 0o777, 0o700);
      assert.equal(statSync(file.path).mode & 0o777, 0o400);
    }
  } finally { prepared.close(); }
  assert.equal(existsSync(dirname(prepared.files[0].path)), false);
  prepared.close(); // Cleanup is safe on repeated closure.
}));
test('private copies remain independent of subsequent frozen-bundle mutation', () => fixture(f => {
  const prepared = prepareV4Apks(admitV4Execution(f.freeze, f.auth, 'synthetic', f.root), f.freeze);
  try {
    for (const source of V4_APKS) writeFileSync(join(f.output, 'snapshot', source), 'changed after staging');
    const installed = [];
    installV4Apks(prepared, (...args) => installed.push(sha256(readFileSync(args[2]))));
    assert.deepEqual(installed, V4_APKS.map(path => f.value.apks[path]));
  } finally { prepared.close(); }
}));
test('a corrupt private test APK blocks the whole pair before any install callback', () => fixture(f => {
  const prepared = prepareV4Apks(admitV4Execution(f.freeze, f.auth, 'synthetic', f.root), f.freeze);
  try {
    chmodSync(prepared.files[1].path, 0o600); writeFileSync(prepared.files[1].path, 'corrupt');
    let calls = 0;
    assert.throws(() => installV4Apks(prepared, () => calls++), /Private V4 APK hash mismatch/);
    assert.equal(calls, 0);
  } finally { prepared.close(); }
}));
test('complete wrapper rejects missing, corrupt or linked frozen APKs before ADB and removes partial copies', () => fixture(f => {
  const marker = join(f.root, 'adb-accessed'), fake = join(f.root, 'fake-adb');
  writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`, { mode: 0o755 });
  for (const source of V4_APKS) for (const fault of ['missing', 'corrupt', 'symlink']) {
    const path = join(f.output, 'snapshot', source), original = readFileSync(path);
    rmSync(path);
    if (fault === 'corrupt') writeFileSync(path, 'corrupt frozen copy');
    if (fault === 'symlink') symlinkSync(join(f.root, source), path);
    try {
      const result = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--v4-feasibility',
        ...(!f.value.profile.enforceAvailableMemoryFloor ? ['--android-pressure'] : []),
        '--v4-freeze', f.freeze, '--v4-authorization', f.auth], { cwd: f.root, encoding: 'utf8',
        env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'synthetic', TMPDIR: f.root, P2_ENCODER_MODEL: '' } });
      assert.notEqual(result.status, 0);
      assert.match(result.stderr, /Frozen APK hash mismatch|Invalid frozen APK file|ENOENT/);
      assert.equal(existsSync(marker), false);
      assert.deepEqual(readdirSync(f.root).filter(name => name.startsWith('pma-v4-apks-')), []);
      assert.equal(existsSync(join(f.root, 'benchmarks/results/v4-execution-ledger')), false);
    } finally { rmSync(path, { force: true }); writeFileSync(path, original); }
  }
}));
test('complete wrapper removes private copies when encoder setup fails before device access', () => fixture(f => {
  const marker = join(f.root, 'adb-accessed'), fake = join(f.root, 'fake-adb');
  writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`, { mode: 0o755 });
  const corruptModel = join(f.root, 'corrupt-model'); writeFileSync(corruptModel, 'invalid encoder');
  for (const model of ['', corruptModel]) {
    const result = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--v4-feasibility',
      ...(!f.value.profile.enforceAvailableMemoryFloor ? ['--android-pressure'] : []),
      '--v4-freeze', f.freeze, '--v4-authorization', f.auth], { cwd: f.root, encoding: 'utf8',
      env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'synthetic', TMPDIR: f.root, P2_ENCODER_MODEL: model } });
    assert.notEqual(result.status, 0); assert.match(result.stderr, /Set P2_ENCODER_MODEL|Encoder candidate size\/hash mismatch/);
    assert.equal(existsSync(marker), false);
    assert.deepEqual(readdirSync(f.root).filter(name => name.startsWith('pma-v4-apks-')), []);
  }
}));
test('resource profile and execution scope cannot be changed by the later authorization', () => fixture(f => {
  const original = JSON.parse(readFileSync(f.auth));
  for (const edit of [a => { a.profileSha256 = 'other'; }, a => { a.requests = 9; },
    a => { a.deadlineMs = 60000; }, a => { a.approved = false; }]) {
    const a = structuredClone(original); edit(a); writeFileSync(f.auth, JSON.stringify(a));
    assert.throws(() => admitV4Execution(f.freeze, f.auth, 'synthetic', f.root), /authorization/);
  }
}));
test('a freeze can reserve only one retained attempt, independent of authorization formatting or file location', () => fixture(f => {
  const admission = admitV4Execution(f.freeze, f.auth, 'synthetic', f.root);
  const record = reserveV4Attempt(admission, 'first', f.output, f.root);
  const original = readFileSync(record);
  const copied = join(f.root, 'copied-freeze.json'); copyFileSync(f.freeze, copied);
  writeFileSync(f.auth, JSON.stringify(JSON.parse(readFileSync(f.auth)), null, 4));
  const same = admitV4Execution(copied, f.auth, 'synthetic', f.root);
  assert.throws(() => reserveV4Attempt(same, 'second', f.root, f.root), /EEXIST/);
  assert.deepEqual(readFileSync(record), original);
}));
test('pressure mode removes only available-RAM floors and never accepts OS pressure or invalid signals', () => {
  const pressurePlan = { ...plan, policyId: 'memory-android-pressure-test-v1', androidPressure: true };
  const pressureExpected = { ...expected, policyId: pressurePlan.policyId, plan: pressurePlan };
  const e = evidence(); e.policyId = pressurePlan.policyId;
  for (const c of e.cases) {
    c.memory.policy.id = pressurePlan.policyId;
    c.memory.samples.forEach(s => { s.policyId = pressurePlan.policyId; s.availableMemoryBytes = 2 ** 30; });
  }
  assert.equal(classifyV4(e, pressureExpected).status, 'passed');
  const baseline = structuredClone(e); const baselinePlan = { ...plan, policyId: 'memory-baseline-v1', androidPressure: false };
  baseline.policyId = baselinePlan.policyId;
  baseline.cases.forEach(c => { c.memory.policy.id = baselinePlan.policyId; c.memory.samples.forEach(s => { s.policyId = baselinePlan.policyId; }); });
  assert.throws(() => classifyV4(baseline, { ...expected, policyId: baselinePlan.policyId, plan: baselinePlan }));
  const unsafe = structuredClone(e); unsafe.cases[0].memory.samples[0].lowMemory = true;
  assert.throws(() => classifyV4(unsafe, pressureExpected));
  const unknown = structuredClone(e); unknown.cases[0].memory.samples[0].availableMemoryBytes = null;
  assert.throws(() => classifyV4(unknown, pressureExpected));
});
