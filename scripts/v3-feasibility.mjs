import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';

export const V3_ROOT = 'planning/fixtures/evidence-sufficiency-v3';
export const V3_FREEZE = 'eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9';
export const sha256 = value => createHash('sha256').update(value).digest('hex');
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const requireThat = (value, message) => { if (!value) throw new Error(message); };

/** Artifact/approval checks happen before any ADB access. No held-out JSON is decoded. */
export function loadFeasibilityPlan(base = '.') {
  const read = path => readFileSync(join(base, path));
  const freezeBytes = read(`${V3_ROOT}/freeze.json`);
  requireThat(sha256(freezeBytes) === V3_FREEZE, 'Unreviewed V3 freeze');
  const freeze = JSON.parse(freezeBytes);
  const review = JSON.parse(read(`${V3_ROOT}/independent-review.json`));
  requireThat(review.verdict === 'PASS' && review.freezeSha256 === V3_FREEZE &&
    review.scope === 'protocol-rubric-full-corpus-and-delivered-pack-judgments' && same(review.files, freeze.files) &&
    review.nativeCollectionAuthorized === false && review.verifierImplementationAuthorized === false,
  'Missing or incompatible independent judgment approval');
  for (const f of freeze.files) {
    const bytes = read(f.path);
    requireThat(bytes.length === f.bytes && sha256(bytes) === f.sha256, `Frozen artifact changed: ${f.path}`);
  }
  const proof = JSON.parse(read('build/encoder-counting/counting-proof.json'));
  requireThat(proof.passed === true && proof.admissionVerified === true &&
    proof.referenceSha256 === sha256(read('build/encoder-counting/reference.tsv')) &&
    Object.entries(proof.sourceSha256).every(([path, sha]) => sha256(read(path)) === sha), 'No current counting proof');
  const data = JSON.parse(read(`${V3_ROOT}/calibration.json`));
  const ids = ['v3k01qc', 'v3k01qm', 'v3k01qs', 'v3k01qt'];
  const entries = [];
  for (const state of ['cold-all-unloaded', 'encoder-fresh-Gemma-unloaded']) for (const id of ids) {
    const q = data.queries.find(q => q.id === id);
    requireThat(q && sha256(q.fixedPack.payload) === freeze.packs[id], 'Fixed evidence pack mismatch');
    entries.push({ id, state, question: q.question, payload: q.fixedPack.payload, packSha256: freeze.packs[id],
      detailIds: q.requestedDetails.map(d => d.i),
      passages: q.fixedPack.deliveredIntervals.map(i => ({ ...i, original: data.passages.find(p => p.id === i.p).text })) });
  }
  // No relevance labels, gold decisions, held-out inputs or vectors enter the device plan.
  return { schema: 3, scope: 'v3-small-supervised-feasibility', freezeSha256: V3_FREEZE,
    deadlineMs: 5000, contextTokens: 2048, outputTokens: 512, semanticEnablement: false, entries };
}

export function classifyFeasibility(evidence, expected) {
  requireThat(evidence?.schema === 3 && evidence.runId === expected.runId &&
    evidence.planSha256 === expected.planSha256 && evidence.policyId === expected.policyId &&
    Number.isSafeInteger(evidence.startedAtMs) && evidence.startedAtMs >= expected.startedAtMs &&
    Number.isSafeInteger(evidence.finishedAtMs) && evidence.finishedAtMs >= evidence.startedAtMs &&
    evidence.finalCleanup === 'CONFIRMED' && Array.isArray(evidence.cases) && evidence.cases.length >= 1 &&
    evidence.cases.length <= 8 && evidence.semanticEnablement === false, 'Uncorrelated/incomplete V3 evidence');
  const plan = expected.plan;
  let blocked = false;
  const workers = new Set();
  for (let i = 0; i < evidence.cases.length; i++) {
    const c = evidence.cases[i], entry = plan.entries[i];
    requireThat(c.id === entry.id && c.state === entry.state && c.packSha256 === entry.packSha256 &&
      c.runtimeCleanup === 'CONFIRMED' && c.encoderCleanup === 'CONFIRMED' && c.runtimeStatus === 'UNLOADED' &&
      Array.isArray(c.gemmaLoads) && Array.isArray(c.workerEvents) &&
      c.backend === 'GPU' && c.lowMemory === false && Number.isInteger(c.thermalStatus) && c.thermalStatus >= 0 && c.thermalStatus < 3 &&
      Number.isSafeInteger(c.decisionMs) && c.decisionMs >= 0 && Number.isSafeInteger(c.cleanupMs) && c.cleanupMs >= 0 &&
      Number.isSafeInteger(c.wallMs) && c.wallMs >= c.decisionMs &&
      ['STRUCTURALLY_VALID', 'INVALID_OUTPUT', 'TIMED_OUT', 'CANCELLED', 'INTERRUPTED', 'REJECTED', 'FAILED'].includes(c.outcome),
    'Unsafe/incomplete V3 request');
    requireThat(['PENDING', 'ELIGIBLE', 'REVOKED'].includes(c.delivery), 'Missing delivery eligibility');
    const complete = c.outcome === 'STRUCTURALLY_VALID' && c.delivery === 'ELIGIBLE' && c.decisionMs < 5000;
    if (complete) {
      requireThat(c.telemetryFailed === false && c.gemmaLoads.length === 1 &&
        typeof c.workerInstance === 'string' && c.workerInstance.length > 0 && !workers.has(c.workerInstance) &&
        c.confirmedWorkerDeath === true && Number.isSafeInteger(c.encoderDeathAtMs) &&
        c.workerEvents.length >= 3 &&
        same(c.workerEvents.slice(0, 3).map(e => e.phase), ['CHECKING', 'LOADING', 'ENCODING']) &&
        c.workerEvents.every((e, n) => Number.isSafeInteger(e.monotonicMs) && e.monotonicMs >= 0 &&
          (n === 0 || e.monotonicMs >= c.workerEvents[n - 1].monotonicMs) && e.monotonicMs <= c.encoderDeathAtMs &&
          Number.isSafeInteger(e.pssKb) && e.pssKb > 0 && e.pssKb <= 384 * 1024 && Number.isSafeInteger(e.pid) && e.pid > 0),
      'Missing or invalid native lifecycle telemetry');
      workers.add(c.workerInstance);
    }
    if (complete) requireThat(Number.isInteger(c.sdkInputTokens) && c.sdkInputTokens > 0 &&
      c.sdkInputTokens + 512 <= 2048 && Number.isInteger(c.sdkOutputTokens) && c.sdkOutputTokens > 0 && c.sdkOutputTokens <= 512,
    'Unverified native token budget');
    if (c.gemmaLoads.length) requireThat(c.confirmedWorkerDeath === true && Number.isSafeInteger(c.encoderDeathAtMs) &&
      c.gemmaLoads.every(t => Number.isSafeInteger(t) && t >= c.encoderDeathAtMs), 'Encoder/Gemma allocation overlap');
    if (!complete) {
      requireThat(i === evidence.cases.length - 1, 'Work continued after a blocking feasibility result');
      blocked = true;
    }
  }
  requireThat(blocked || evidence.cases.length === 8, 'Missing planned requests');
  return { status: blocked ? 'blocked' : 'passed', largeCampaignAllowed: false,
    // Even a feasibility pass does not pass development quality/performance gates.
    semanticEnablement: false, completedRequests: evidence.cases.length, notRunRequests: 8 - evidence.cases.length };
}
