import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateEncoderEvidence, validateForegroundEvidence } from '../p2-encoder-evidence.mjs';

const options = { runId: 'current', startedAtMs: 1000, modelSha256: 'pinned', policyId: 'memory-android-pressure-test-v1' };
function valid() {
  return { schema: 1, runId: 'current', modelSha256: 'pinned', runtime: 'com.google.mediapipe:tasks-text:1.1.1',
    startedAtMs: 1010, finishedAtMs: 2000, passed: true,
    cases: ['cold-warm', 'cancel-active-batch', 'fresh-recovery'].map(mode => ({
      mode, policyId: options.policyId, passed: true, confirmedDeath: true, lowMemory: false, thermalStatus: 0,
      totalMs: 500, heads: 2, dimensions: 100, loadMs: 100, encodeMs: [20, 10], cancelToDeathMs: 40,
      events: (mode === 'cancel-active-batch' ? ['CHECKING', 'LOADING', 'ENCODING'] : ['CHECKING', 'LOADING', 'ENCODING', 'RESULT', 'CLOSED'])
        .map((phase, index) => ({ phase, elapsedMs: 50 + index, pssKb: 50000 })),
    })) };
}
test('fresh bounded encoder evidence passes', () => assert.equal(validateEncoderEvidence(valid(), options), true));
test('stale, failed, uncorrelated and unsafe encoder evidence fails', () => {
  for (const mutate of [
    value => { value.startedAtMs = 999; }, value => { value.runId = 'old'; },
    value => { value.passed = false; }, value => { value.cases.pop(); },
    value => { value.cases[0].confirmedDeath = false; }, value => { value.cases[1].cancelToDeathMs = 2001; },
    value => { value.cases[0].totalMs = 5001; }, value => { value.cases[0].lowMemory = true; },
    value => { value.cases[0].thermalStatus = -1; }, value => { value.cases[0].thermalStatus = 3; },
    value => { value.cases[0].policyId = 'different'; }, value => { value.cases[2].dimensions++; },
    value => { value.cases[0].events.pop(); }, value => { value.cases[0].events[0].pssKb = 400000; },
    value => { value.cases[0].events[1].elapsedMs = 1; },
  ]) {
    const fixture = valid(); mutate(fixture); assert.equal(validateEncoderEvidence(fixture, options), false);
  }
});

function foreground() {
  return { schema: 1, runId: 'current', modelSha256: 'pinned', runtime: 'com.google.mediapipe:tasks-text:1.1.1',
    startedAtMs: 1010, finishedAtMs: 2000, passed: true, contextTokens: 2048, outputReservation: 512, fullRetrievalVerified: false, gemmaCleanup: 'CONFIRMED',
    cases: ['cold', 'resident-reclaim', 'cancel-counting', 'cancel-loading', 'cancel-encoding', 'timeout-before-handoff', 'fresh-worker-recovery'].map((mode, i) => ({
      mode, policyId: options.policyId, passed: true, encoderCleanup: 'CONFIRMED', lowMemory: false, thermalStatus: 0,
      backend: 'GPU', panelIsolationChecked: true, inputBound: 500, sdkInputTokens: 250,
      totalMs: 1000, preparationMs: 500, confirmedDeath: true, workerInstance: `worker-${i}`, encoderDeathAtMs: 100,
      gemmaLoadStarts: mode.startsWith('cancel-') || mode.startsWith('timeout-') ? [] : [101],
      outcome: mode.startsWith('cancel-') ? 'CANCELLED' : mode.startsWith('timeout-') ? 'TIMED_OUT' : 'COMPLETED',
      error: mode.startsWith('timeout-') ? 'ENCODER_TIMEOUT' : null, nativeEncoderInvoked: mode === 'cancel-counting' ? false : true,
      workerEvents: [{ phase: 'LOADING', pssKb: 50000, pid: 10 + i, monotonicMs: 50 }],
    })) };
}
test('fresh foreground evidence proves bounded handoff but not full retrieval', () => {
  assert.equal(validateForegroundEvidence(foreground(), options), true);
});
test('foreground evidence rejects overlap stale workers missing cleanup and deadline inflation', () => {
  for (const mutate of [
    x => { x.runId = 'old'; }, x => { x.startedAtMs = 999; }, x => { x.fullRetrievalVerified = true; },
    x => { x.contextTokens = 4096; }, x => { x.outputReservation = 256; },
    x => { x.cases[0].preparationMs = 5000; }, x => { x.cases[0].gemmaLoadStarts = [99]; },
    x => { x.cases[1].workerInstance = x.cases[0].workerInstance; }, x => { x.cases[0].confirmedDeath = false; },
    x => { x.cases[2].nativeEncoderInvoked = true; }, x => { x.cases[3].gemmaLoadStarts = [101]; },
    x => { x.cases[5].error = 'different'; }, x => { x.cases[6].encoderCleanup = 'UNCERTAIN'; },
    x => { x.cases[0].workerEvents[0].pssKb = 400000; }, x => { x.cases[0].lowMemory = true; },
  ]) {
    const value = foreground(); mutate(value); assert.equal(validateForegroundEvidence(value, options), false);
  }
});
