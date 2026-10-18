import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateNativeLoss } from '../probe-native-supervision.mjs';

const policy = { id: 'synthetic-policy', modelSha256: 'synthetic-sha', runtimeVersion: 'synthetic-runtime', severeThermalStatus: 3 };
const fixture = phase => ({ phase, runId: 'synthetic-run', policy, pidGone: true,
  drop: { runId: 'synthetic-run', phase, elapsedMs: 200 },
  stop: { runId: 'synthetic-run', phase, elapsedSinceLossMs: 6000 },
  loss: { schema: 1, runId: 'synthetic-run', target: phase, phase, elapsedMs: 250, lossObserved: true,
    backend: 'GPU', automaticFallback: false, policyId: policy.id, modelSha256: policy.modelSha256,
    runtimeVersion: policy.runtimeVersion, lowMemory: false, thermalStatus: 0,
    runtimeStatus: phase === 'INITIALIZING' ? 'LOADING' : 'GENERATING', runtimeError: null,
    promptSha256: 'e769e953b92ff0677b861257131627d3558d06de671c645fbfe08bee7bf13f5c', contextTokens: 2048, maxOutputTokens: 512,
    timeline: ['INITIALIZING', ...(phase === 'INITIALIZING' ? [] : ['LOADED', 'PREFILL']),
      ...(phase === 'DECODING' ? ['DECODING'] : [])].map((phase, index) => ({ phase, elapsedMs: 100 + index * 10 })) },
  supervision: { schema: 2, runId: 'synthetic-run', status: 'terminated', reason: 'UNAVAILABLE',
    elapsedMs: 300, leaseMs: 20000, pollMs: 500, cleanupMs: 5000 } });

test('native loss requires actual phase evidence and process termination for each target', () => {
  for (const phase of ['INITIALIZING', 'PREFILL', 'DECODING']) assert.equal(validateNativeLoss(fixture(phase)), true);
});

test('missed phases, old evidence, missing callbacks and live processes cannot pass', () => {
  const valid = fixture('DECODING');
  for (const invalid of [
    { ...valid, pidGone: false }, { ...valid, drop: undefined },
    { ...valid, stop: undefined },
    { ...valid, stop: { ...valid.stop, elapsedSinceLossMs: 9001 } },
    { ...valid, stop: { ...valid.stop, elapsedSinceLossMs: -1 } },
    { ...valid, drop: { ...valid.drop, runId: 'old' } },
    { ...valid, loss: { ...valid.loss, phase: 'TERMINAL' } },
    { ...valid, loss: { ...valid.loss, elapsedMs: 100 } },
    { ...valid, loss: { ...valid.loss, elapsedMs: 9000 } },
    { ...valid, loss: { ...valid.loss, lowMemory: true } },
    { ...valid, loss: { ...valid.loss, runtimeStatus: 'FAILED' } },
    { ...valid, loss: { ...valid.loss, runtimeError: 'RESTART_REQUIRED' } },
    { ...valid, loss: { ...valid.loss, thermalStatus: 3 } },
    { ...valid, loss: { ...valid.loss, timeline: [{ phase: 'DECODING', elapsedMs: 100 }] } },
    { ...valid, supervision: { ...valid.supervision, runId: 'old' } },
    { ...valid, supervision: { ...valid.supervision, reason: 'STOP_REQUESTED' } },
  ]) assert.equal(validateNativeLoss(invalid), false);
});

test('prefill proof must use the verified fixture without enlarging context or output limits', () => {
  const valid = fixture('PREFILL');
  for (const change of [{ promptSha256: 'different-fixture' }, { contextTokens: 4096 }, { maxOutputTokens: 1024 }]) {
    assert.equal(validateNativeLoss({ ...valid, loss: { ...valid.loss, ...change } }), false);
  }
});
