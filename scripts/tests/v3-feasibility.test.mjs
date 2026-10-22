import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, copyFileSync, cpSync, readFileSync, writeFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { spawnSync } from 'node:child_process';
import { loadFeasibilityPlan, classifyFeasibility, V3_ROOT } from '../v3-feasibility.mjs';

const plan = loadFeasibilityPlan();
const expected = { runId: 'synthetic-v3', startedAtMs: 1000, planSha256: 'abc', policyId: 'memory-baseline-v1', plan };
function evidence() {
  return { schema: 3, runId: expected.runId, startedAtMs: 1100, finishedAtMs: 2000,
    planSha256: 'abc', policyId: expected.policyId, finalCleanup: 'CONFIRMED', semanticEnablement: false,
    cases: plan.entries.map((e, i) => ({ id: e.id, state: e.state, packSha256: e.packSha256,
      runtimeCleanup: 'CONFIRMED', encoderCleanup: 'CONFIRMED', runtimeStatus: 'UNLOADED',
      backend: 'GPU', lowMemory: false, thermalStatus: 0, decisionMs: 1000, cleanupMs: 200, wallMs: 1200,
      outcome: 'STRUCTURALLY_VALID', sdkInputTokens: 400, sdkOutputTokens: 80,
      delivery: 'ELIGIBLE', telemetryFailed: false, workerInstance: `synthetic-worker-${i}`,
      gemmaLoads: [120], confirmedWorkerDeath: true, encoderDeathAtMs: 100,
      workerEvents: ['CHECKING', 'LOADING', 'ENCODING'].map((phase, n) => ({ phase, monotonicMs: n * 10, pssKb: 90000, pid: 200 + i })) })) };
}

test('feasibility plan contains only eight frozen calibration packs and no gold or held-out data', () => {
  assert.equal(plan.entries.length, 8);
  assert.deepEqual(plan.entries.map(e => e.id), Array(2).fill(['v3k01qc', 'v3k01qm', 'v3k01qs', 'v3k01qt']).flat());
  assert.equal(plan.deadlineMs, 5000); assert.equal(plan.outputTokens, 512); assert.equal(plan.contextTokens, 2048);
  for (const e of plan.entries) {
    assert.deepEqual(Object.keys(e), ['id', 'state', 'question', 'payload', 'packSha256', 'detailIds', 'passages']);
    assert.ok(!e.id.startsWith('v3h')); assert.ok(!Object.hasOwn(e, 'gold'));
  }
});
test('eight complete bounded requests establish feasibility only, never semantic enablement', () => {
  assert.deepEqual(classifyFeasibility(evidence(), expected), { status: 'passed', largeCampaignAllowed: false,
    semanticEnablement: false, completedRequests: 8, notRunRequests: 0 });
});
test('five-second overrun blocks and preserves later requests as not run even after confirmed cleanup', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1);
  Object.assign(e.cases[0], { outcome: 'TIMED_OUT', decisionMs: 5001, wallMs: 9000, cleanupMs: 3999 });
  assert.deepEqual(classifyFeasibility(e, expected), { status: 'blocked', largeCampaignAllowed: false,
    semanticEnablement: false, completedRequests: 1, notRunRequests: 7 });
  e.cases.push(evidence().cases[1]); assert.throws(() => classifyFeasibility(e, expected));
});
test('missing stale duplicate unsafe or uncertain evidence cannot pass', () => {
  for (const change of [e => { e.runId = 'old'; }, e => { e.planSha256 = 'old'; }, e => { e.startedAtMs = 0; },
    e => { e.finalCleanup = 'UNCERTAIN'; }, e => { e.cases.pop(); }, e => { e.cases[1].id = e.cases[0].id; },
    e => { e.cases[0].lowMemory = true; }, e => { e.cases[0].thermalStatus = 3; },
    e => { e.cases[0].runtimeCleanup = 'PENDING'; }, e => { delete e.cases[0].gemmaLoads; },
    e => { e.cases[0].sdkInputTokens = 2000; }, e => { e.cases[0].sdkOutputTokens = 513; },
    e => { e.cases[0].encoderDeathAtMs = 121; }, e => { e.cases[0].confirmedWorkerDeath = false; }]) {
    const e = evidence(); change(e); assert.throws(() => classifyFeasibility(e, expected));
  }
});
test('invalid output is a visible blocking failure, not a successful abstention', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1); e.cases[0].outcome = 'INVALID_OUTPUT';
  assert.equal(classifyFeasibility(e, expected).status, 'blocked');
});
test('successful claims require actual load, fresh worker phases, death and healthy telemetry', () => {
  for (const change of [c => { c.gemmaLoads = []; }, c => { c.workerEvents = []; },
    c => { delete c.workerInstance; }, c => { c.workerInstance = ''; }, c => { c.confirmedWorkerDeath = false; },
    c => { delete c.encoderDeathAtMs; }, c => { c.telemetryFailed = true; },
    c => { c.workerEvents[1].monotonicMs = 200; }, c => { c.workerEvents[0].phase = 'LOADING'; },
    c => { c.workerEvents[0].pssKb = 0; }]) {
    const e = evidence(); change(e.cases[0]); assert.throws(() => classifyFeasibility(e, expected));
  }
  const duplicate = evidence(); duplicate.cases[1].workerInstance = duplicate.cases[0].workerInstance;
  assert.throws(() => classifyFeasibility(duplicate, expected));
});
test('revoked validation is blocked even though the original decision remains exactly once', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1); e.cases[0].delivery = 'REVOKED';
  assert.equal(classifyFeasibility(e, expected).status, 'blocked');
});

function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'pma-v3-'));
  const copy = p => { mkdirSync(dirname(join(root, p)), { recursive: true }); copyFileSync(p, join(root, p)); };
  for (const f of JSON.parse(readFileSync(`${V3_ROOT}/freeze.json`)).files) copy(f.path);
  for (const p of [`${V3_ROOT}/freeze.json`, `${V3_ROOT}/independent-review.json`,
    'build/encoder-counting/counting-proof.json', 'build/encoder-counting/reference.tsv',
    'planning/fixtures/semantic-retrieval-v1.json', 'planning/fixtures/semantic-retrieval-v1.lock.json']) copy(p);
  const proof = JSON.parse(readFileSync('build/encoder-counting/counting-proof.json'));
  for (const p of Object.keys(proof.sourceSha256)) copy(p);
  cpSync('scripts', join(root, 'scripts'), { recursive: true });
  return root;
}
test('complete device wrapper rejects missing/tampered review before ADB access', () => {
  for (const mode of ['missing', 'failed', 'tampered']) {
    const root = fixture();
    try {
      const path = join(root, V3_ROOT, 'independent-review.json');
      if (mode === 'missing') rmSync(path);
      else { const r = JSON.parse(readFileSync(path)); if (mode === 'failed') r.verdict = 'FAIL'; else r.freezeSha256 = 'x'; writeFileSync(path, JSON.stringify(r)); }
      const marker = join(root, 'adb-accessed');
      const fake = join(root, 'fake-adb'); writeFileSync(fake, `#!/bin/sh\ntouch '${marker}'\n`, { mode: 0o755 });
      const result = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--v3-feasibility'],
        { cwd: root, env: { ...process.env, ADB: fake, ANDROID_SERIAL: 'synthetic' }, encoding: 'utf8' });
      assert.notEqual(result.status, 0); assert.equal(existsSync(marker), false);
      assert.match(result.stderr, /independent|ENOENT/);
    } finally { rmSync(root, { recursive: true, force: true }); }
  }
});
test('changed counting proof or frozen payload fails closed before planning', () => {
  const root = fixture();
  try {
    const path = join(root, 'build/encoder-counting/counting-proof.json');
    const proof = JSON.parse(readFileSync(path)); proof.passed = false; writeFileSync(path, JSON.stringify(proof));
    assert.throws(() => loadFeasibilityPlan(root), /counting proof/);
    const dataset = join(root, V3_ROOT, 'calibration.json'); writeFileSync(dataset, '{}');
    assert.throws(() => loadFeasibilityPlan(root), /Frozen artifact changed/);
  } finally { rmSync(root, { recursive: true, force: true }); }
});
