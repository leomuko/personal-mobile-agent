import test from 'node:test';
import assert from 'node:assert/strict';
import { buildPreparation, OA_VALIDATOR_SHA, OA_ID } from '../output-adherence-preparation.mjs';
import { loadApprovedAdherence, OA_PREPARATION_SHA, OA_POLICY } from '../output-adherence-gates.mjs';
import { classifyAdherence, matchesAdherenceShape } from '../output-adherence-evidence.mjs';

const approved = loadApprovedAdherence(), controls = buildPreparation().prepared.controls;
const expected = { runId: 'synthetic-oa', startedAtMs: 1000, planSha256: approved.bindings.planSha256,
  freezeSha256: 'synthetic-freeze', policyId: OA_POLICY, plan: approved.plan, oracles: approved.oracles };
function evidence() {
  return { schema: 1, protocolId: OA_ID, runId: expected.runId, startedAtMs: 1100, finishedAtMs: 2000,
    startedAtMonotonicMs: 0, finishedAtMonotonicMs: 240000, planSha256: expected.planSha256,
    executionFreezeSha256: expected.freezeSha256, freezeSha256: OA_PREPARATION_SHA, policyId: OA_POLICY,
    strictValidatorSha256: OA_VALIDATOR_SHA, oraclesSha256: approved.plan.oraclesSha256,
    semanticEnablement: false, largeCampaignAllowed: false, finalCleanup: 'CONFIRMED',
    cases: controls.map((c, i) => {
      const start = i * 40000;
      return { id: c.id, state: 'native-cold', requestId: `${expected.runId}-oa-${i}`, backend: 'GPU',
        packSha256: c.input.payloadSha256, inputBound: c.input.inputBound,
        gemmaInitialState: 'UNLOADED', nativeEncoderInitialState: 'no-resident-worker',
        tokenizerInitialState: i ? 'cached-admission' : 'cold', outcome: 'STRUCTURALLY_VALID',
        runtimeDelivery: 'ELIGIBLE', delivery: 'ELIGIBLE', runtimeDecisionMs: 11000, decisionMs: 11500, wallMs: 12000, cleanupMs: 400,
        startedAtMonotonicMs: start, finishedAtMonotonicMs: start + 12000, caseGateAccepted: true,
        controlShape: 'MATCHED', runtimeCleanup: 'CONFIRMED', encoderCleanup: 'CONFIRMED', runtimeStatus: 'UNLOADED',
        lowMemory: false, thermalStatus: 0, telemetryFailed: false, validation: 'STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED',
        diagnosticOutput: JSON.stringify({ o: c.review.expected.state, d: c.review.expected.details.map(d => ({ i: d.i, s: d.state,
          e: c.review.witnesses.filter(w => w.detail === d.i).map(({ detail, ...span }) => span) })) }),
        sdkInputTokens: 500, sdkOutputTokens: 100, confirmedWorkerDeath: true, encoderDeathAtMs: start + 100,
        gemmaLoads: [start + 120], workerInstance: `synthetic-worker-${i}`,
        workerEvents: ['CHECKING', 'LOADING', 'ENCODING'].map((phase, n) => ({ phase, monotonicMs: start + n * 10, pid: 100 + i, pssKb: 100000 })),
        memory: { schema: 1, policy: { id: OA_POLICY }, startedAtMonotonicMs: start + 1, elapsedMs: 11000,
          droppedSamples: 0, failedSamples: 0, samples: ['BASELINE', 'LOADING', 'PREFILL', 'DECODING', 'POST_RELEASE'].map((stage, n) => ({ stage,
            elapsedMs: n ? 119 + n : 0, eventElapsedMs: n ? 118 + n : 0, policyId: OA_POLICY, backend: 'GPU',
            lowMemory: false, thermalStatus: 0, availableMemoryBytes: 2 ** 30, pressureThresholdBytes: 1000000,
            appPssBytes: 2000000000, appRssBytes: 2100000000 })) } };
    }) };
}

test('six complete synthetic controls pass only the limited feasibility classifier', () => {
  const result = classifyAdherence(evidence(), expected);
  assert.equal(result.status, 'passed'); assert.equal(result.strictValidRequests, 6); assert.equal(result.shapeCorrectRequests, 6);
  assert.equal(result.semanticEnablement, false); assert.equal(result.semanticQualityQualified, false);
});
test('final delivery cannot promote revoked or pending runtime eligibility', () => {
  for (const runtimeDelivery of ['REVOKED', 'PENDING']) {
    for (const claim of ['eligible-delivery', 'accepted-gate', 'both']) {
      const e = evidence(); if (claim !== 'both') e.cases = e.cases.slice(0, 1); const c = e.cases[0];
      Object.assign(c, { runtimeDelivery, delivery: claim === 'accepted-gate' ? 'REVOKED' : 'ELIGIBLE',
        caseGateAccepted: claim !== 'eligible-delivery' });
      assert.throws(() => classifyAdherence(e, expected), /cannot promote/);
    }
  }
});
test('a protocol gate may revoke an eligible runtime result without rewriting it', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1); const c = e.cases[0];
  Object.assign(c, { delivery: 'REVOKED', caseGateAccepted: false });
  assert.equal(classifyAdherence(e, expected).status, 'blocked');
  assert.equal(c.runtimeDelivery, 'ELIGIBLE'); assert.equal(c.outcome, 'STRUCTURALLY_VALID');
});
test('a strictly valid abstention is a control-shape failure, never a successful supported answer', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1); const c = e.cases[0];
  c.diagnosticOutput = '{"o":"U","d":[{"i":"a","s":"U","e":[]}]}';
  c.controlShape = 'MISMATCH'; c.caseGateAccepted = false; c.delivery = 'REVOKED';
  const result = classifyAdherence(e, expected);
  assert.equal(result.status, 'blocked'); assert.equal(result.notRunRequests, 5); assert.equal(result.shapeCorrectRequests, 0);
  e.cases.push(evidence().cases[1]); assert.throws(() => classifyAdherence(e, expected), /continued/);
});
test('strict rejection retains the raw fenced output and cannot become a repaired success', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1); const c = e.cases[0];
  c.diagnosticOutput = '```json\n' + c.diagnosticOutput + '\n```';
  Object.assign(c, { outcome: 'INVALID_OUTPUT', validation: 'JSON', delivery: 'REVOKED', runtimeDelivery: 'REVOKED', controlShape: 'NOT_REACHED', caseGateAccepted: false });
  const raw = c.diagnosticOutput; assert.equal(classifyAdherence(e, expected).status, 'blocked'); assert.equal(c.diagnosticOutput, raw);
});
test('deadline includes cleanup and host-shape decisions without replacing a latched runtime outcome', () => {
  const e = evidence(); e.cases = e.cases.slice(0, 1); const c = e.cases[0];
  Object.assign(c, { decisionMs: 30000, wallMs: 30100, finishedAtMonotonicMs: 30100,
    delivery: 'REVOKED', runtimeDelivery: 'REVOKED', controlShape: 'NOT_REACHED', validation: 'NOT_REACHED', caseGateAccepted: false });
  assert.equal(classifyAdherence(e, expected).status, 'blocked'); assert.equal(c.outcome, 'STRUCTURALLY_VALID');
  const before = evidence(); before.cases.forEach(c => { c.decisionMs = 29998; c.wallMs = 29999; c.finishedAtMonotonicMs = c.startedAtMonotonicMs + 29999; });
  assert.equal(classifyAdherence(before, expected).status, 'passed');
});
test('wrong identity, stale trace, missing cleanup, allocation overlap and incorrect shape claims cannot pass', () => {
  for (const edit of [e => { e.oraclesSha256 = 'other'; }, e => { e.strictValidatorSha256 = 'other'; },
    e => { e.cases[0].controlShape = 'NOT_REACHED'; }, e => { e.cases[0].runtimeDecisionMs = 12000; },
    e => { e.cases[0].inputBound++; }, e => { e.cases[0].gemmaLoads = [1]; },
    e => { e.cases[0].memory.failedSamples = 1; }, e => { e.finalCleanup = 'UNCERTAIN'; },
    e => { e.cases[1].workerInstance = e.cases[0].workerInstance; }, e => { e.cases[0].memory.samples[0].lowMemory = true; }]) {
    const e = evidence(); edit(e); assert.throws(() => classifyAdherence(e, expected));
  }
  assert.equal(matchesAdherenceShape({ o: 'C', d: [{ i: 'a', s: 'C', e: [{ p: 'only-one' }] }] }, approved.oracles.controls[1]), false);
});
