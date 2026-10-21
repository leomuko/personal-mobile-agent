import { test } from 'node:test';
import assert from 'node:assert/strict';
import { validateEncoderEvidence } from '../p2-encoder-evidence.mjs';

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
