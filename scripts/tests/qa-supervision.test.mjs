import { test } from 'node:test';
import assert from 'node:assert/strict';
import { renewSupervision, stopSupervisedQa, supervisionIdentity, supervisionDirectory } from '../qa-supervision.mjs';
import { validSupervision } from '../verify-qa-supervision.mjs';

const identity = { runId: 'synthetic-run', parent: 42 };
const options = { adb: 'fake-adb', serial: 'synthetic-device', ...identity, sequence: 1 };

test('only a live, run-correlated supervisor can renew a device-clock heartbeat', () => {
  const calls = [];
  const beat = renewSupervision(options, (...args) => {
    calls.push(args);
    return calls.length === 1 ? '100.25 50.00\n' : '';
  }, () => 42);
  assert.deepEqual(beat, { schema: 2, runId: identity.runId, sequence: 1, elapsedMs: 100250 });
  assert.match(calls[1][1].at(-1), /run-as dev\.edgecompanion\.app\.secureqa/);
  assert.match(calls[1][1].at(-1), /qa-supervision\/synthetic-run\/heartbeat\.tmp.*&& mv.*heartbeat\.json/);
  assert.ok(calls.every(call => call[2].timeout === 5000));
  assert.deepEqual(supervisionIdentity({ QA_SUPERVISION_RUN_ID: identity.runId, QA_SUPERVISOR_PID: '42' }), identity);
});

test('orphaned monitor, missing clocks and ADB failure do not renew supervision', () => {
  assert.throws(() => supervisionIdentity({}));
  assert.throws(() => renewSupervision(options, () => { assert.fail('No ADB call after parent loss'); }, () => 1));
  let checks = 0; let calls = 0;
  assert.throws(() => renewSupervision(options, () => { calls++; return '100.00 10.00\n'; }, () => ++checks === 1 ? 42 : 1));
  assert.equal(calls, 1);
  assert.throws(() => renewSupervision(options, () => 'invalid', () => 42));
  assert.throws(() => renewSupervision(options, () => { throw new Error('ADB disconnected'); }, () => 42));
  assert.throws(() => renewSupervision({ ...options, runId: "x'; echo unsafe" }, () => assert.fail(), () => 42));
});

test('a lost, missing, stale, foreign or differently configured proof cannot pass', () => {
  const proof = { schema: 2, runId: identity.runId, status: 'closed', reason: null, leaseMs: 20000,
    pollMs: 500, cleanupMs: 5000, capturedAtMs: 200, elapsedMs: 100 };
  assert.equal(validSupervision(proof, identity.runId, 200), true);
  for (const invalid of [null, { ...proof, status: 'active' }, { ...proof, status: 'terminated' },
    { ...proof, runId: 'old' }, { ...proof, capturedAtMs: 199 }, { ...proof, reason: 'EXPIRED' },
    { ...proof, leaseMs: 30000 }, { ...proof, capturedAtMs: undefined }, { ...proof, schema: 1 }]) {
    assert.equal(validSupervision(invalid, identity.runId, 200), false);
  }
});

test('ownership handoff before stop publication cannot redirect an old stop to the new run', () => {
  assert.deepEqual(stopSupervisedQa({ ...options, runId: undefined }, () => assert.fail('No unscoped stop')),
    { hostStopError: 'InvalidIdentity' });
  let owner = identity.runId;
  let stopped;
  const calls = [];
  const report = stopSupervisedQa(options, (_adb, args) => {
    calls.push(args);
    // Reproduce the original interleaving if a future change reintroduces check-then-stop.
    if (args.includes('exec-out')) { const prior = JSON.stringify({ runId: owner }); owner = 'new-run'; return prior; }
    owner = 'new-run';
    if (args.includes('force-stop')) stopped = owner;
    else {
      assert.match(args.at(-1), /qa-supervision\/synthetic-run\/stop\.json/);
      assert.ok(!args.at(-1).includes('qa-supervision/new-run/'));
    }
    return '';
  });
  assert.deepEqual(report, { stopRequested: true });
  assert.equal(calls.length, 1);
  assert.equal(stopped, undefined);
  assert.equal(owner, 'new-run');
  assert.deepEqual(stopSupervisedQa(options, () => { throw new Error('sensitive ADB command details'); }), { hostStopError: 'Error' });
});

test('a stale heartbeat writer cannot overwrite the new run after ownership changes', () => {
  const calls = [];
  renewSupervision(options, (_adb, args) => {
    calls.push(args);
    if (args.includes('/proc/uptime')) return '100.00 10.00\n';
    assert.match(args.at(-1), /qa-supervision\/synthetic-run\/heartbeat\.json/);
    assert.ok(!args.at(-1).includes('qa-supervision/new-run/'));
    return '';
  }, () => 42);
  assert.equal(calls.length, 2);
  assert.notEqual(supervisionDirectory('new-run'), supervisionDirectory(identity.runId));
  assert.throws(() => supervisionDirectory('../other'));
});
