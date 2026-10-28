import { isDeepStrictEqual } from 'node:util';
import { CD_ID, CD_LOCK, CD_POLICY, cdCheck as check } from './constrained-decoding-gates.mjs';
import { matchesAdherenceShape } from './output-adherence-evidence.mjs';

const integer = value => Number.isSafeInteger(value) && value >= 0;
export const diagnosticUtf16Hex = text => Array.from({ length: text.length }, (_, i) => text.charCodeAt(i).toString(16).padStart(4, '0')).join('');
const positive = id => ['cd01', 'cd03', 'cd08', 'cd09'].includes(id);
const singleton = (c, phase) => { const points = c.constraintPoints.filter(p => p.phase === phase); return points.length === 1 ? points[0] : null; };

export function validateConstrainedDeadline(value, runId) {
  if (value === null) return null;
  check(value?.schema === 2 && value.runId === runId && value.reason === 'CONSTRAINED_REQUEST_CLEANUP_WATCHDOG' &&
    /^cd0[1-9]$/.test(value.caseId) && integer(value.requestStartedAtMs) && integer(value.expiredAtMs) &&
    value.expiredAtMs - value.requestStartedAtMs >= 40000, 'Uncorrelated constrained emergency evidence');
  return value;
}

function allocation(c, workers) {
  check(c.confirmedWorkerDeath === true && integer(c.encoderDeathAtMs) && c.encoderDeathAtMs >= c.startedAtMonotonicMs &&
    typeof c.workerInstance === 'string' && c.workerInstance && !workers.has(c.workerInstance) &&
    Array.isArray(c.gemmaLoads) && c.gemmaLoads.length === 1 && c.gemmaLoads.every(t => integer(t) && t >= c.encoderDeathAtMs && t <= c.finishedAtMonotonicMs) &&
    Array.isArray(c.workerEvents) && c.workerEvents.length >= 3 && c.workerEvents.slice(0, 3).map(e => e.phase).join(',') === 'CHECKING,LOADING,ENCODING' &&
    c.workerEvents.every((p, i) => integer(p.monotonicMs) && p.monotonicMs >= c.startedAtMonotonicMs && p.monotonicMs <= c.encoderDeathAtMs &&
      (!i || p.monotonicMs >= c.workerEvents[i - 1].monotonicMs) && p.pid > 0 && Number.isInteger(p.pid) && p.pssKb > 0 && p.pssKb <= 384 * 1024),
    'Missing constrained allocation/death evidence');
  const m = c.memory, stages = ['BASELINE', 'LOADING', 'PREFILL', 'POST_RELEASE'];
  if (singleton(c, 'FIRST_TEXT')) stages.push('DECODING');
  check(m?.schema === 1 && m.policy?.id === CD_POLICY && integer(m.startedAtMonotonicMs) && integer(m.elapsedMs) &&
    m.startedAtMonotonicMs >= c.startedAtMonotonicMs && m.startedAtMonotonicMs + m.elapsedMs <= c.finishedAtMonotonicMs &&
    m.droppedSamples === 0 && m.failedSamples === 0 && Array.isArray(m.samples) && m.samples.length > 0 &&
    stages.every(stage => m.samples.some(s => s.stage === stage)) && m.samples.every((s, i) =>
      integer(s.elapsedMs) && integer(s.eventElapsedMs) && s.eventElapsedMs <= s.elapsedMs && s.elapsedMs <= m.elapsedMs &&
      (!i || (s.elapsedMs >= m.samples[i - 1].elapsedMs && s.eventElapsedMs >= m.samples[i - 1].eventElapsedMs)) &&
      s.policyId === CD_POLICY && s.backend === 'GPU' && s.lowMemory === false && Number.isInteger(s.thermalStatus) &&
      s.thermalStatus >= 0 && s.thermalStatus < 3 && integer(s.availableMemoryBytes) &&
      Number.isSafeInteger(s.pressureThresholdBytes) && s.pressureThresholdBytes > 0 &&
      Number.isSafeInteger(s.appPssBytes) && s.appPssBytes > 0 && Number.isSafeInteger(s.appRssBytes) && s.appRssBytes > 0) &&
    isDeepStrictEqual(c.gemmaLoads, m.samples.filter(s => s.stage === 'LOADING').map(s => m.startedAtMonotonicMs + s.eventElapsedMs)),
    'Missing/unsafe constrained telemetry');
  workers.add(c.workerInstance);
}

function expectedGuard(c, entry, oracle, sentinel) {
  if (c.constraintPoints.filter(p => p.phase !== 'NATIVE_ERROR').some((p, i, a) => a.findIndex(q => q.phase === p.phase) !== i)) return false;
  if (c.sdkOutputTokens != null && (!Number.isInteger(c.sdkOutputTokens) || c.sdkOutputTokens < 0 || c.sdkOutputTokens > entry.decodeTokenCap)) return false;
  const setup = singleton(c, 'SETUP_ENTER'), returned = singleton(c, 'SETUP_RETURN'), closed = singleton(c, 'CLOSED');
  const published = singleton(c, 'HANDLE_PUBLISHED'), sendReturn = singleton(c, 'SEND_RETURN');
  const send = singleton(c, 'SEND_ENTER'), first = singleton(c, 'FIRST_TEXT'), terminal = singleton(c, 'TERMINAL'), cancel = singleton(c, 'CANCEL_SIGNAL');
  const errors = c.constraintPoints.filter(p => p.phase === 'NATIVE_ERROR');
  if (!setup || !returned || !closed || !published || setup.atMs > returned.atMs || returned.atMs > published.atMs || published.atMs > closed.atMs) return false;
  if (Boolean(send) !== Boolean(sendReturn) || (send && (send.atMs < published.atMs || sendReturn.atMs < send.atMs || sendReturn.atMs > closed.atMs)) ||
      ((first || terminal) && !send) || (first && (first.atMs < send.atMs || !terminal || first.atMs > terminal.atMs)) ||
      (terminal && terminal.atMs < send.atMs) || [first, terminal, cancel].filter(Boolean).some(p => p.atMs > closed.atMs)) return false;
  if (c.gemmaLoads?.length !== 1 || setup.atMs < c.gemmaLoads[0]) return false;
  if (positive(c.id)) {
    if (cancel || errors.length || !send || !first || !terminal || c.outcome !== 'STRUCTURALLY_VALID' || c.delivery !== 'ELIGIBLE' ||
      c.validation !== 'STRUCTURALLY_VALID' || c.shapeMatched !== true || !Number.isInteger(c.sdkInputTokens) ||
      c.sdkInputTokens <= 0 || c.sdkInputTokens > entry.inputBound || c.sdkOutputTokens < 1 || c.sdkOutputTokens > 512 ||
      !Number.isInteger(c.sdkOutputTokens) || Buffer.byteLength(c.diagnosticOutput || '') > 1536) return false;
    const raw = JSON.parse(c.diagnosticOutput);
    return c.id === 'cd01' ? isDeepStrictEqual(raw, sentinel) : matchesAdherenceShape(raw, oracle);
  }
  if (c.delivery !== 'REVOKED' || c.validation === 'STRUCTURALLY_VALID') return false;
  if (c.id === 'cd02') return !cancel && !first && !c.diagnosticOutput && send && terminal && c.outcome === 'FAILED' &&
    (c.sdkOutputTokens == null || c.sdkOutputTokens === 0) &&
    errors.length === 1 && errors.some(p => p.diagnostic?.includes('Failed to create LLGuidance constraint:') &&
      p.diagnostic.includes('deliberately-invalid-json-schema-type') && p.atMs >= send.atMs && p.atMs <= terminal.atMs);
  if (['cd04', 'cd05', 'cd06'].includes(c.id)) {
    if (!cancel || c.outcome !== 'CANCELLED') return false;
    if (c.id === 'cd04') return !send && !first && setup.atMs < cancel.atMs && cancel.atMs < returned.atMs;
    if (!terminal) return false;
    return c.id === 'cd05' ? send && send.atMs < cancel.atMs && cancel.atMs < terminal.atMs && (!first || cancel.atMs < first.atMs) :
      first && c.diagnosticOutput?.length > 0 && first.atMs <= cancel.atMs && cancel.atMs < terminal.atMs;
  }
  return c.id === 'cd07' && !cancel && !errors.length && terminal && first && c.outcome === 'INVALID_OUTPUT' &&
    c.validation !== 'NOT_REACHED' && c.sdkOutputTokens === 1 && c.diagnosticOutput?.trimStart().startsWith('{');
}

/** Replay case identities/guards; the unchanged on-device validator remains the strict acceptance authority. */
export function classifyConstrained(evidence, expected) {
  check(expected.plan.protocolId === CD_ID && expected.plan.requests === 9 && expected.plan.profileId === CD_POLICY &&
    evidence?.schema === 2 && evidence.protocolId === CD_ID && evidence.runId === expected.runId &&
    evidence.planSha256 === expected.planSha256 && evidence.executionFreezeSha256 === expected.freezeSha256 &&
    evidence.preparationLockSha256 === CD_LOCK && evidence.policyId === CD_POLICY && evidence.semanticEnablement === false &&
    evidence.finalCleanup === 'CONFIRMED' && integer(evidence.startedAtMs) && evidence.startedAtMs >= expected.startedAtMs &&
    integer(evidence.finishedAtMs) && evidence.finishedAtMs >= evidence.startedAtMs &&
    integer(evidence.startedAtMonotonicMs) && integer(evidence.finishedAtMonotonicMs) &&
    Array.isArray(evidence.cases) && evidence.cases.length >= 1 && evidence.cases.length <= 9, 'Wrong/incomplete constrained evidence');
  let blocked = false; const workers = new Set();
  for (const [i, c] of evidence.cases.entries()) {
    const entry = expected.plan.entries[i];
    check(c.id === entry.id && c.requestId === `${expected.runId}-cd-${i}` && c.packSha256 === entry.packSha256 &&
      c.schemaSha256 === entry.schemaSha256 && c.decodeTokenCap === entry.decodeTokenCap && c.reservedOutputTokens === 512 &&
      c.inputBound === entry.inputBound && c.backend === 'GPU' && c.gemmaInitialState === 'UNLOADED' &&
      c.tokenizerInitialState === 'cold' && c.nativeEncoderInitialState === 'no-resident-worker' &&
      c.responseFormatOptIn === true && c.nativeConstraintType === 2 &&
      c.diagnosticSource === 'NATIVE_CALLBACK_BUFFER' && typeof c.diagnosticTruncated === 'boolean' &&
      integer(c.diagnosticReceivedUtf16Units) && typeof c.diagnosticOutput === 'string' &&
      c.diagnosticReceivedUtf16Units >= c.diagnosticOutput.length && Buffer.byteLength(c.diagnosticOutput) <= 1536 &&
      (c.diagnosticTruncated || c.diagnosticReceivedUtf16Units === c.diagnosticOutput.length) &&
      typeof c.diagnosticUtf16Hex === 'string' && c.diagnosticUtf16Hex === diagnosticUtf16Hex(c.diagnosticOutput) &&
      integer(c.startedAtMonotonicMs) && integer(c.finishedAtMonotonicMs) && c.finishedAtMonotonicMs >= c.startedAtMonotonicMs &&
      c.wallMs === c.finishedAtMonotonicMs - c.startedAtMonotonicMs && c.startedAtMonotonicMs >= evidence.startedAtMonotonicMs &&
      c.finishedAtMonotonicMs <= evidence.finishedAtMonotonicMs && (!i || c.startedAtMonotonicMs >= evidence.cases[i - 1].finishedAtMonotonicMs) &&
      integer(c.decisionMs) && c.decisionMs <= c.wallMs && integer(c.cleanupMs) && c.cleanupMs <= c.wallMs &&
      Array.isArray(c.constraintPoints) && c.constraintPoints.length <= 64 && c.constraintPoints.every(p => integer(p.atMs) &&
        p.atMs >= c.startedAtMonotonicMs && p.atMs <= c.finishedAtMonotonicMs && (!p.diagnostic ||
          (typeof p.diagnostic === 'string' && p.diagnostic.length <= 1024))), 'Uncorrelated constrained case');
    const passed = c.caseGateAccepted === true && !c.diagnosticTruncated && c.wallMs < 30000 && c.runtimeCleanup === 'CONFIRMED' && c.encoderCleanup === 'CONFIRMED' &&
      c.runtimeStatus === 'UNLOADED' && c.telemetryFailed === false && c.allocationValid === true &&
      expectedGuard(c, entry, expected.oracles.controls[i], expected.sentinel);
    if (passed) allocation(c, workers);
    else { check(c.caseGateAccepted !== true, 'Invalid guard was claimed successful');
      check(i === evidence.cases.length - 1, 'Constrained decoding continued after a blocking case'); blocked = true; }
  }
  check(blocked || evidence.cases.length === 9, 'Missing constrained requests');
  return { status: blocked ? 'blocked' : 'passed', completedRequests: evidence.cases.length, notRunRequests: 9 - evidence.cases.length,
    generatedRequests: evidence.cases.filter(c => singleton(c, 'FIRST_TEXT')).length,
    strictValidRequests: evidence.cases.filter(c => c.outcome === 'STRUCTURALLY_VALID').length,
    eligibleStrictValidRequests: evidence.cases.filter(c => c.validation === 'STRUCTURALLY_VALID' && c.delivery === 'ELIGIBLE').length,
    expectedNegativeGuards: evidence.cases.filter(c => c.caseGateAccepted && !positive(c.id)).length,
    positiveGuards: evidence.cases.filter(c => c.caseGateAccepted && positive(c.id)).length,
    cancelledRequests: evidence.cases.filter(c => c.outcome === 'CANCELLED').length,
    exhaustedRequests: evidence.cases.filter(c => c.guardClassification === 'ONE_TOKEN_EXHAUSTION_GUARD').length,
    timedOutRequests: evidence.cases.filter(c => c.outcome === 'TIMED_OUT' || c.error === 'ENCODER_TIMEOUT' || c.wallMs >= 30000).length,
    failedGuardRequests: evidence.cases.filter(c => !c.caseGateAccepted).length,
    semanticQualityQualified: false, semanticEnablement: false };
}
