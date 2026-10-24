import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { loadFeasibilityPlan, V3_FREEZE, sha256 } from './v3-feasibility.mjs';
import { validateMemoryPolicy } from './memory-policy.mjs';

export const V4_ID = 'serial-evidence-verification-v4-draft-1';
export const V4_DEADLINE = 30000;
export const V4_STATES = ['cold-all-unloaded', 'cached-admission-only'];
export const requireV4 = (condition, message) => { if (!condition) throw new Error(message); };
export const readV4 = path => JSON.parse(readFileSync(path, 'utf8'));
export function v4Profile(base = '.') {
  const profile = validateMemoryPolicy(readV4(join(base, 'build/v4-resource-profile.json')));
  requireV4((profile.id === 'memory-baseline-v1' && profile.enforceAvailableMemoryFloor === true) ||
    (profile.id === 'memory-android-pressure-test-v1' && profile.enforceAvailableMemoryFloor === false), 'Unknown V4 resource profile');
  return profile;
}

/** Calibration-only plan; V3 preparation/counting verification is reused, never rewritten. */
export function loadV4Plan(base = '.') {
  const original = loadFeasibilityPlan(base);
  const profile = v4Profile(base);
  return { ...original, schema: 4, scope: 'v4-small-supervised-feasibility', protocolId: V4_ID,
    policyId: profile.id, androidPressure: !profile.enforceAvailableMemoryFloor,
    deadlineMs: V4_DEADLINE, cleanupWithinDeadline: true,
    entries: original.entries.map((entry, i) => ({ ...entry, state: V4_STATES[Math.floor(i / 4)] })) };
}

export function classifyV4(evidence, expected) {
  return classifySerialVerification(evidence, expected, { schema: 4, protocolId: V4_ID, requests: 8,
    sourceFreeze: V3_FREEZE, requestPrefix: 'v4', coldAdmissionCount: 4 });
}

/** Shared lifecycle/telemetry checks; protocol entry points supply fixed contracts, never input-selected limits. */
export function classifySerialVerification(evidence, expected, contract) {
  requireV4(expected.plan.policyId === expected.policyId &&
    expected.plan.androidPressure === (expected.policyId === 'memory-android-pressure-test-v1'), 'V4 profile/plan mismatch');
  const integer = x => Number.isSafeInteger(x) && x >= 0;
  requireV4(evidence?.schema === contract.schema && evidence.protocolId === contract.protocolId && evidence.runId === expected.runId &&
    evidence.planSha256 === expected.planSha256 && evidence.executionFreezeSha256 === expected.freezeSha256 &&
    evidence.freezeSha256 === contract.sourceFreeze && evidence.policyId === expected.policyId &&
    evidence.semanticEnablement === false && evidence.largeCampaignAllowed === false &&
    evidence.finalCleanup === 'CONFIRMED' && integer(evidence.startedAtMs) && evidence.startedAtMs >= expected.startedAtMs &&
    integer(evidence.finishedAtMs) && evidence.finishedAtMs >= evidence.startedAtMs &&
    integer(evidence.startedAtMonotonicMs) && integer(evidence.finishedAtMonotonicMs) &&
    evidence.finishedAtMonotonicMs >= evidence.startedAtMonotonicMs &&
    Array.isArray(evidence.cases) && evidence.cases.length >= 1 && evidence.cases.length <= contract.requests,
  'Uncorrelated/incomplete V4 evidence');
  const workers = new Set(), requests = new Set(); let blocked = false;
  for (const [i, c] of evidence.cases.entries()) {
    const entry = expected.plan.entries[i];
    requireV4(c.id === entry.id && c.state === entry.state && c.packSha256 === entry.packSha256 &&
      c.requestId === `${expected.runId}-${contract.requestPrefix}-${i}` && !requests.has(c.requestId) &&
      c.backend === 'GPU' && c.gemmaInitialState === 'UNLOADED' && c.nativeEncoderInitialState === 'no-resident-worker' &&
      c.tokenizerInitialState === (i < contract.coldAdmissionCount ? 'cold' : 'cached-admission') &&
      integer(c.startedAtMonotonicMs) && integer(c.finishedAtMonotonicMs) &&
      c.finishedAtMonotonicMs >= c.startedAtMonotonicMs && c.wallMs === c.finishedAtMonotonicMs - c.startedAtMonotonicMs &&
      c.startedAtMonotonicMs >= evidence.startedAtMonotonicMs && c.finishedAtMonotonicMs <= evidence.finishedAtMonotonicMs &&
      (!i || c.startedAtMonotonicMs >= evidence.cases[i - 1].finishedAtMonotonicMs) &&
      ['STRUCTURALLY_VALID', 'INVALID_OUTPUT', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED', 'REJECTED', 'FAILED'].includes(c.outcome) &&
      ['ELIGIBLE', 'REVOKED', 'PENDING'].includes(c.delivery) && integer(c.decisionMs) && integer(c.cleanupMs) &&
      integer(c.wallMs) && c.wallMs >= c.decisionMs && c.cleanupMs <= c.wallMs &&
      Array.isArray(c.gemmaLoads) && Array.isArray(c.workerEvents), 'Malformed V4 request');
    requests.add(c.requestId);
    const complete = c.outcome === 'STRUCTURALLY_VALID' && c.delivery === 'ELIGIBLE' &&
      c.caseGateAccepted === true &&
      c.decisionMs < V4_DEADLINE && c.wallMs < V4_DEADLINE &&
      c.runtimeCleanup === 'CONFIRMED' && c.encoderCleanup === 'CONFIRMED' && c.runtimeStatus === 'UNLOADED' &&
      c.lowMemory === false && Number.isInteger(c.thermalStatus) && c.thermalStatus >= 0 && c.thermalStatus < 3;
    if (c.gemmaLoads.length) requireV4(c.confirmedWorkerDeath === true && integer(c.encoderDeathAtMs) &&
      c.encoderDeathAtMs >= c.startedAtMonotonicMs && c.encoderDeathAtMs <= c.finishedAtMonotonicMs &&
      c.gemmaLoads.every(t => integer(t) && t >= c.encoderDeathAtMs && t <= c.finishedAtMonotonicMs), 'V4 native allocation overlap');
    if (complete) {
      requireV4(c.telemetryFailed === false && c.validation === 'STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED' &&
        typeof c.diagnosticOutput === 'string' && Buffer.byteLength(c.diagnosticOutput) <= 1536 &&
        c.diagnosticOutput.length > 0 && c.gemmaLoads.length === 1 && c.confirmedWorkerDeath === true &&
        typeof c.workerInstance === 'string' && c.workerInstance.length > 0 && !workers.has(c.workerInstance) &&
        c.workerEvents.length >= 3 && c.workerEvents.slice(0, 3).map(e => e.phase).join(',') === 'CHECKING,LOADING,ENCODING' &&
        c.workerEvents.every((e, n) => integer(e.monotonicMs) && e.monotonicMs <= c.encoderDeathAtMs &&
          e.monotonicMs >= c.startedAtMonotonicMs &&
          (!n || e.monotonicMs >= c.workerEvents[n - 1].monotonicMs) && Number.isSafeInteger(e.pid) && e.pid > 0 &&
          Number.isSafeInteger(e.pssKb) && e.pssKb > 0 && e.pssKb <= 384 * 1024) &&
        Number.isInteger(c.sdkInputTokens) && c.sdkInputTokens > 0 && c.sdkInputTokens + 512 <= 2048 &&
        Number.isInteger(c.sdkOutputTokens) && c.sdkOutputTokens > 0 && c.sdkOutputTokens <= 512,
      'Missing V4 lifecycle, token or output proof');
      // Runtime performs strict decoding; a parseable raw result is additionally required in host evidence.
      const raw = JSON.parse(c.diagnosticOutput);
      requireV4(['S', 'C', 'M', 'U'].includes(raw.o) && Array.isArray(raw.d) &&
        JSON.stringify(raw.d.map(d => d.i)) === JSON.stringify(entry.detailIds), 'Wrong V4 diagnostic output');
      workers.add(c.workerInstance);
      const memory = c.memory;
      requireV4(memory?.schema === 1 && memory.policy?.id === expected.policyId && integer(memory.startedAtMonotonicMs) &&
        memory.startedAtMonotonicMs >= c.startedAtMonotonicMs && integer(memory.elapsedMs) &&
        memory.startedAtMonotonicMs + memory.elapsedMs <= c.finishedAtMonotonicMs &&
        memory.droppedSamples === 0 && memory.failedSamples === 0 && Array.isArray(memory.samples) && memory.samples.length > 0 &&
        ['BASELINE', 'LOADING', 'PREFILL', 'DECODING', 'POST_RELEASE'].every(stage => memory.samples.some(s => s.stage === stage)) &&
        memory.samples.every((s, n) => integer(s.elapsedMs) && integer(s.eventElapsedMs) &&
          s.elapsedMs >= s.eventElapsedMs && s.elapsedMs <= memory.elapsedMs &&
          (!n || (s.elapsedMs >= memory.samples[n - 1].elapsedMs && s.eventElapsedMs >= memory.samples[n - 1].eventElapsedMs)) &&
          s.policyId === expected.policyId && s.backend === 'GPU' && s.lowMemory === false &&
          Number.isInteger(s.thermalStatus) && s.thermalStatus >= 0 && s.thermalStatus < 3 &&
          integer(s.availableMemoryBytes) && (expected.plan.androidPressure || s.availableMemoryBytes >= 3 * 2 ** 30) &&
          Number.isSafeInteger(s.pressureThresholdBytes) && s.pressureThresholdBytes > 0 &&
          Number.isSafeInteger(s.appPssBytes) && s.appPssBytes > 0 && Number.isSafeInteger(s.appRssBytes) && s.appRssBytes > 0),
      'Missing/unsafe V4 allocation telemetry');
      requireV4(JSON.stringify(c.gemmaLoads) === JSON.stringify(memory.samples.filter(s => s.stage === 'LOADING')
        .map(s => memory.startedAtMonotonicMs + s.eventElapsedMs)), 'Uncorrelated V4 load trace');
    } else {
      requireV4(i === evidence.cases.length - 1, 'V4 continued after blocking result');
      blocked = true;
    }
  }
  requireV4(blocked || evidence.cases.length === contract.requests, 'V4 missing planned requests');
  return { status: blocked ? 'blocked' : 'passed', completedRequests: evidence.cases.length,
    notRunRequests: contract.requests - evidence.cases.length, largeCampaignAllowed: false, semanticEnablement: false };
}
