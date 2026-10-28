import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, existsSync, chmodSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { CD_ID, CD_ROOT, CD_LOCK, CD_PROOF, CD_POLICY, loadConstrainedPlan } from '../constrained-decoding-gates.mjs';
import { constrainedSnapshot, createConstrainedFreeze, admitConstrainedExecution, reserveConstrainedAttempt } from '../constrained-decoding-freeze.mjs';
import { prepareV4Apks, installV4Apks, V4_APKS } from '../v4-execution-freeze.mjs';
import { classifyConstrained, validateConstrainedDeadline, diagnosticUtf16Hex } from '../constrained-decoding-evidence.mjs';
import { sha256 } from '../v3-feasibility.mjs';

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'constrained-host-synthetic-'));
  try {
    for (const path of Object.keys(constrainedSnapshot())) {
      mkdirSync(dirname(join(root, path)), { recursive: true }); copyFileSync(path, join(root, path));
    }
    for (const [i, path] of V4_APKS.entries()) {
      mkdirSync(dirname(join(root, path)), { recursive: true }); writeFileSync(join(root, path), Buffer.alloc(100001, i + 1));
    }
    // All permissions/reviews below are synthetic, confined to this removed temporary root.
    const review = join(root, 'synthetic-review.json');
    writeFileSync(review, JSON.stringify({ verdict: 'PASS', scope: 'constrained-decoding-v2-integration-and-host-qa',
      snapshotSha256: sha256(JSON.stringify(constrainedSnapshot(root))), deviceExecutionAuthorized: false }));
    const output = join(root, 'freeze'); const frozen = createConstrainedFreeze(output, review, root);
    const freeze = join(output, 'execution-freeze.json'), auth = join(root, 'synthetic-authorization.json');
    const correspondence = join(output, 'independent-freeze-review.json');
    writeFileSync(correspondence, JSON.stringify({ verdict: 'PASS', scope: 'constrained-decoding-v2-execution-freeze',
      freezeSha256: sha256(readFileSync(freeze)), deviceExecutionAuthorized: false }));
    const authorization = { schema: 2, protocolId: CD_ID, approved: true, scope: 'single-supervised-constrained-decoding-nine-request',
      freezeSha256: sha256(readFileSync(freeze)), profileSha256: frozen.profileSha256, serial: 'synthetic', device: 'SM-S928B',
      requests: 9, deadlineMs: 30000, negativeCase: 'cd07', negativeDecodeTokenCap: 1, reservedOutputTokens: 512 };
    writeFileSync(auth, JSON.stringify(authorization));
    return run({ root, output, frozen, freeze, auth, authorization, correspondence, review });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('exact reviewed schemas and nine inputs stay separate from QA judgments', () => {
  const { plan, oracles } = loadConstrainedPlan();
  assert.equal(plan.preparationLockSha256, CD_LOCK); assert.equal(plan.entries.length, 9);
  assert.deepEqual(plan.entries.map(e => e.decodeTokenCap), [512, 512, 512, 512, 512, 512, 1, 512, 512]);
  for (const entry of plan.entries) {
    assert.equal(entry.packSha256, sha256(entry.payload)); assert.equal(entry.schemaSha256, sha256(entry.constraintSchema));
    assert.equal(entry.encoderInputSha256, sha256(entry.question));
    assert.equal(entry.inputBound, 224 + Buffer.byteLength(entry.payload));
    for (const key of ['review', 'expected', 'witnesses', 'judgments']) assert.equal(key in entry, false);
  }
  assert.deepEqual(oracles.controls.filter(c => c.state).map(c => [c.id, c.state]), [['cd03', 'S'], ['cd08', 'C'], ['cd09', 'S']]);
});

test('missing, failed or changed prerequisites block without device access', () => fixture(f => {
  const file = join(f.root, CD_PROOF), original = JSON.parse(readFileSync(file));
  for (const edit of [p => { p.status = 'failed'; }, p => { p.oneTokenProof.schemaValidVariants = 1; },
    p => { p.reference.mutationInstancesPerSchema = 33; }, p => { delete p.files['scripts/verify-constrained-decoding.py']; },
    p => { p.dependencies.jsonschema = 'other'; }]) {
    const proof = structuredClone(original); edit(proof); writeFileSync(file, JSON.stringify(proof));
    assert.throws(() => loadConstrainedPlan(f.root), /prerequisites/);
  }
  rmSync(file); assert.throws(() => loadConstrainedPlan(f.root), /ENOENT/);
}));

test('integration permission cannot expand count, cap, reservation, deadline or profile', () => fixture(f => {
  const file = join(f.root, CD_ROOT, 'integration-authorization.json'), original = JSON.parse(readFileSync(file));
  for (const edit of [p => { p.requests = 10; }, p => { p.negativeCase = 'cd03'; }, p => { p.reservedOutputTokens = 1; },
    p => { p.deadlineMs = 30001; }, p => { p.profileId = 'memory-baseline-v1'; }, p => { p.deviceExecutionAuthorized = true; }]) {
    const a = structuredClone(original); edit(a); writeFileSync(file, JSON.stringify(a));
    assert.throws(() => loadConstrainedPlan(f.root), /integration scope/);
  }
}));

test('a proposal or integration approval is insufficient for execution and stale freezes reject', () => fixture(f => {
  assert.equal(f.frozen.deviceExecutionAuthorized, false);
  const admission = admitConstrainedExecution(f.freeze, f.auth, 'synthetic', f.root);
  reserveConstrainedAttempt(admission, 'first', f.output, f.root);
  assert.throws(() => reserveConstrainedAttempt(admission, 'second', f.output, f.root), /EEXIST/);
  rmSync(f.correspondence); assert.throws(() => admitConstrainedExecution(f.freeze, f.auth, 'synthetic', f.root), /ENOENT/);
  writeFileSync(join(f.root, 'scripts/constrained-decoding-evidence.mjs'), '// changed');
  assert.throws(() => admitConstrainedExecution(f.freeze, f.auth, 'synthetic', f.root), /Stale/);
}));

test('separate S24 permission binds all scope and device identities', () => fixture(f => {
  for (const edit of [a => { a.scope = 'single-supervised-output-adherence-six-request'; }, a => { a.serial = 'other'; },
    a => { a.requests = 6; }, a => { a.deadlineMs = 60000; }, a => { a.negativeDecodeTokenCap = 2; },
    a => { a.reservedOutputTokens = 1; }, a => { a.freezeSha256 = 'wrong'; }]) {
    const a = structuredClone(f.authorization); edit(a); writeFileSync(f.auth, JSON.stringify(a));
    assert.throws(() => admitConstrainedExecution(f.freeze, f.auth, 'synthetic', f.root), /S24 authorization/);
  }
}));

test('concurrent builds cannot replace installed frozen APK bytes and corrupt archives reject', () => fixture(f => {
  const admission = admitConstrainedExecution(f.freeze, f.auth, 'synthetic', f.root);
  for (const path of V4_APKS) writeFileSync(join(f.root, path), 'new unrelated build');
  const copies = prepareV4Apks(admission, f.freeze);
  const installed = [];
  try {
    installV4Apks(copies, (...args) => { installed.push(sha256(readFileSync(args.at(-1)))); return ''; });
    assert.deepEqual(installed, V4_APKS.map(p => f.frozen.apks[p]));
  } finally { copies.close(); }
  assert.ok(copies.files.every(f => !existsSync(f.path)));
  writeFileSync(join(f.output, 'snapshot', V4_APKS[0]), 'corrupt');
  assert.throws(() => prepareV4Apks(admission, f.freeze), /Frozen APK hash mismatch/);
}));

test('complete wrapper refuses absent approval before invoking fake ADB', () => {
  const folder = mkdtempSync(join(tmpdir(), 'constrained-no-adb-')); const marker = join(folder, 'adb-called');
  const fake = join(folder, 'adb'); writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`);
  chmodSync(fake, 0o700);
  try {
    const result = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--constrained-decoding', '--android-pressure'],
      { encoding: 'utf8', env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'synthetic' } });
    assert.notEqual(result.status, 0); assert.match(result.stderr, /separate S24 authorization/); assert.equal(existsSync(marker), false);
  } finally { rmSync(folder, { recursive: true, force: true }); }
});

function syntheticEvidence() {
  const approved = loadConstrainedPlan(); const { plan, oracles, sentinel } = approved;
  const expected = { plan, oracles, sentinel, runId: 'synthetic', startedAtMs: 100, planSha256: approved.planSha256, freezeSha256: 'synthetic-freeze' };
  const cases = plan.entries.map((entry, i) => {
    const start = 1000 + 100 * i;
    const phases = [['SETUP_ENTER', 25], ['SETUP_RETURN', 30], ['HANDLE_PUBLISHED', 31], ['SEND_ENTER', 40],
      ['SEND_RETURN', 41], ['FIRST_TEXT', 50], ['TERMINAL', 65], ['CLOSED', 70]];
    if (entry.id === 'cd02') { phases.splice(phases.findIndex(p => p[0] === 'FIRST_TEXT'), 1); phases.push(['NATIVE_ERROR', 50, 'Failed to create LLGuidance constraint: deliberately-invalid-json-schema-type']); }
    if (entry.id === 'cd04') {
      for (let n = phases.length - 1; n >= 0; n--) if (['SEND_ENTER', 'SEND_RETURN', 'FIRST_TEXT', 'TERMINAL'].includes(phases[n][0])) phases.splice(n, 1);
      phases.push(['CANCEL_SIGNAL', 27]);
    }
    if (entry.id === 'cd05') { phases.splice(phases.findIndex(p => p[0] === 'FIRST_TEXT'), 1); phases.push(['CANCEL_SIGNAL', 45]); }
    if (entry.id === 'cd06') phases.push(['CANCEL_SIGNAL', 55]);
    const negative = ['cd02', 'cd04', 'cd05', 'cd06', 'cd07'].includes(entry.id);
    let raw = entry.id === 'cd01' ? sentinel : undefined;
    if (oracles.controls[i].state) {
      const control = JSON.parse(readFileSync('planning/fixtures/output-adherence-v1/prepared.json')).controls
        .find(c => c.id === ({ cd03: 'oa01', cd08: 'oa02', cd09: 'oa06' })[entry.id]);
      raw = { o: oracles.controls[i].state, d: oracles.controls[i].details.map(d => ({ i: d.i, s: d.state,
        e: control.review.witnesses.filter(w => w.detail === d.i).map(({ detail, ...span }) => span) })) };
    }
    const memory = { schema: 1, policy: { id: CD_POLICY }, startedAtMonotonicMs: start, elapsedMs: 75,
      droppedSamples: 0, failedSamples: 0, samples: ['BASELINE', 'LOADING', 'PREFILL', 'DECODING', 'POST_RELEASE'].map((stage, n) =>
        ({ stage, eventElapsedMs: 5 + n * 15, elapsedMs: 5 + n * 15, policyId: CD_POLICY, backend: 'GPU', lowMemory: false,
          thermalStatus: 0, availableMemoryBytes: 1000, pressureThresholdBytes: 1, appPssBytes: 1, appRssBytes: 1 })) };
    const diagnosticOutput = entry.id === 'cd07' ? '{' : entry.id === 'cd06' ? '{"o":' : raw ? JSON.stringify(raw) : '';
    return { id: entry.id, requestId: `synthetic-cd-${i}`, packSha256: entry.packSha256, schemaSha256: entry.schemaSha256,
      decodeTokenCap: entry.decodeTokenCap, reservedOutputTokens: 512, inputBound: entry.inputBound, backend: 'GPU', gemmaInitialState: 'UNLOADED',
      tokenizerInitialState: 'cold', nativeEncoderInitialState: 'no-resident-worker',
      responseFormatOptIn: true, nativeConstraintType: 2, startedAtMonotonicMs: start, finishedAtMonotonicMs: start + 80, wallMs: 80,
      decisionMs: 60, cleanupMs: 10, outcome: negative ? entry.id === 'cd02' ? 'FAILED' : entry.id === 'cd07' ? 'INVALID_OUTPUT' : 'CANCELLED' : 'STRUCTURALLY_VALID',
      delivery: negative ? 'REVOKED' : 'ELIGIBLE', validation: negative ? entry.id === 'cd07' ? 'JSON' : 'NOT_REACHED' : 'STRUCTURALLY_VALID',
      diagnosticOutput, diagnosticSource: 'NATIVE_CALLBACK_BUFFER', diagnosticTruncated: false, diagnosticReceivedUtf16Units: diagnosticOutput.length,
      diagnosticUtf16Hex: diagnosticUtf16Hex(diagnosticOutput),
      sdkInputTokens: 100, sdkOutputTokens: entry.id === 'cd02' ? 0 : entry.id === 'cd07' ? 1 : 30,
      runtimeCleanup: 'CONFIRMED', encoderCleanup: 'CONFIRMED', runtimeStatus: 'UNLOADED', telemetryFailed: false, allocationValid: true,
      shapeMatched: !negative, workerInstance: `worker-${i}`, confirmedWorkerDeath: true, encoderDeathAtMs: start + 15,
      gemmaLoads: [start + 20], workerEvents: ['CHECKING', 'LOADING', 'ENCODING'].map((phase, n) => ({ phase, monotonicMs: start + n, pssKb: 1, pid: i + 1 })),
      memory, constraintPoints: phases.map(([phase, time, diagnostic]) => ({ phase, atMs: start + time, ...(diagnostic ? { diagnostic } : {}) })),
      caseGateAccepted: true, guardClassification: entry.id === 'cd07' ? 'ONE_TOKEN_EXHAUSTION_GUARD' : 'synthetic' };
  });
  return { expected, evidence: { schema: 2, protocolId: CD_ID, runId: 'synthetic', planSha256: expected.planSha256,
    executionFreezeSha256: expected.freezeSha256, preparationLockSha256: CD_LOCK, policyId: CD_POLICY, semanticEnablement: false,
    startedAtMs: 100, finishedAtMs: 200, startedAtMonotonicMs: 1000, finishedAtMonotonicMs: 1900, finalCleanup: 'CONFIRMED', cases } };
}

test('synthetic nine-case replay separates five expected negatives from four structural positives', () => {
  const { evidence, expected } = syntheticEvidence(); const result = classifyConstrained(evidence, expected);
  assert.equal(result.status, 'passed'); assert.equal(result.positiveGuards, 4); assert.equal(result.expectedNegativeGuards, 5);
  assert.equal(result.semanticEnablement, false); assert.equal(result.exhaustedRequests, 1);
});

test('generic compiler errors, missed cancel and empty exhaustion cannot pass or permit continuation', () => {
  for (const edit of [e => { e.cases[1].constraintPoints.find(p => p.phase === 'NATIVE_ERROR').diagnostic = 'GENERATION_FAILED'; },
    e => { e.cases[3].constraintPoints.find(p => p.phase === 'CANCEL_SIGNAL').atMs += 20; },
    e => { e.cases[6].diagnosticOutput = ''; e.cases[6].diagnosticReceivedUtf16Units = 0; e.cases[6].diagnosticUtf16Hex = ''; }, e => { e.cases[8].wallMs = 30000; e.cases[8].finishedAtMonotonicMs += 29920; e.finishedAtMonotonicMs += 29920; }]) {
    const { evidence, expected } = syntheticEvidence(); edit(evidence);
    assert.throws(() => classifyConstrained(evidence, expected), /claimed successful/);
  }
});

test('blocked first case stays blocked and later cases or unsafe telemetry are rejected', () => {
  const { evidence, expected } = syntheticEvidence();
  evidence.cases[0].caseGateAccepted = false; evidence.cases[0].outcome = 'INVALID_OUTPUT'; evidence.cases[0].delivery = 'REVOKED';
  assert.throws(() => classifyConstrained(evidence, expected), /continued after/);
  evidence.cases = evidence.cases.slice(0, 1);
  assert.equal(classifyConstrained(evidence, expected).status, 'blocked');
  const fresh = syntheticEvidence(); fresh.evidence.cases[0].memory.samples[0].lowMemory = true;
  assert.throws(() => classifyConstrained(fresh.evidence, fresh.expected), /telemetry/);
});

test('absent deadline evidence is normal while wrong-run and early emergency records fail', () => {
  assert.equal(validateConstrainedDeadline(null, 'synthetic'), null);
  const value = { schema: 2, runId: 'synthetic', reason: 'CONSTRAINED_REQUEST_CLEANUP_WATCHDOG', caseId: 'cd01',
    requestStartedAtMs: 100, expiredAtMs: 40100 };
  assert.deepEqual(validateConstrainedDeadline(value, 'synthetic'), value);
  for (const wrong of [{ ...value, runId: 'old' }, { ...value, expiredAtMs: 40099 }, { ...value, caseId: 'cd10' }, {}])
    assert.throws(() => validateConstrainedDeadline(wrong, 'synthetic'), /Uncorrelated/);
});

test('phase guards reject reviewed counterexamples and missing or reversed boundaries', () => {
  for (const change of [
    e => { const c = e.cases[5], send = c.constraintPoints.find(p => p.phase === 'SEND_ENTER').atMs;
      c.constraintPoints.find(p => p.phase === 'FIRST_TEXT').atMs = send - 5;
      c.constraintPoints.find(p => p.phase === 'CANCEL_SIGNAL').atMs = send - 4; },
    e => { const c = e.cases[0]; c.constraintPoints.find(p => p.phase === 'FIRST_TEXT').atMs = c.constraintPoints.find(p => p.phase === 'TERMINAL').atMs + 1; },
    e => { e.cases[0].constraintPoints = e.cases[0].constraintPoints.filter(p => p.phase !== 'TERMINAL'); },
    e => { const c = e.cases[0]; c.constraintPoints.find(p => p.phase === 'TERMINAL').atMs = c.constraintPoints.find(p => p.phase === 'SEND_ENTER').atMs - 1; },
  ]) {
    const { evidence, expected } = syntheticEvidence(); change(evidence);
    assert.throws(() => classifyConstrained(evidence, expected), /claimed successful/);
  }
  const { evidence, expected } = syntheticEvidence();
  evidence.cases[0].constraintPoints.find(p => p.phase === 'SEND_RETURN').atMs = evidence.cases[0].constraintPoints.find(p => p.phase === 'TERMINAL').atMs + 1;
  assert.equal(classifyConstrained(evidence, expected).status, 'passed');
});

test('empty or truncated cancellation diagnostics cannot pass a decoding guard', () => {
  for (const edit of [c => { c.diagnosticOutput = ''; c.diagnosticReceivedUtf16Units = 0; c.diagnosticUtf16Hex = ''; }, c => { c.diagnosticTruncated = true; }]) {
    const { evidence, expected } = syntheticEvidence(); edit(evidence.cases[5]);
    assert.throws(() => classifyConstrained(evidence, expected), /claimed successful/);
  }
});

test('diagnostic encoding preserves exact UTF-16 code units including interrupted Unicode', () => {
  assert.equal(diagnosticUtf16Hex('😀'), 'd83dde00');
  assert.equal(diagnosticUtf16Hex('\ud83d'), 'd83d');
  assert.equal(diagnosticUtf16Hex('{"o":'), '007b0022006f0022003a');
  const { evidence, expected } = syntheticEvidence(); evidence.cases[5].diagnosticUtf16Hex = '';
  assert.throws(() => classifyConstrained(evidence, expected), /Uncorrelated/);
});
