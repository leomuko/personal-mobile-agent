import { readFileSync } from 'node:fs';
import { digest, loadSemanticFixture, validateQualityCollection } from './semantic-quality.mjs';
import { gates, policyIds, uncertainty } from './calibration-policy.mjs';

export const protocolId = 'calibration-policy-qualification-v2';
export const splitSizes = { calibration: 60, development: 40, heldout: 60 };
export const candidate = loadSemanticFixture().fixture.candidate;
export const check = (ok, message) => { if (!ok) throw new Error(message); };
export const jsonHash = value => digest(Buffer.from(JSON.stringify(value)));
export const readJson = path => {
  const raw = readFileSync(path);
  check(raw.length <= 8 * 1024 * 1024, 'JSON exceeds bounded intake');
  return { value: JSON.parse(raw), sha256: digest(raw) };
};

export const protocol = {
  schema: 2, id: protocolId, author: 'implementation-agent', candidate, gates,
  splits: splitSizes, requiredLanguage: 'en', diagnostics: [], maxBatchEntries: 32,
  ranking: 'cosine-descending-passage-id-ascending', acceptance: 'score>=cutoff',
  equivalentCutoffs: 'stable-candidate-order-all-then-ascending-score-then-none',
  judgments: 'complete-0..3-positive-if-grade>0', recall: 'cutoff-filtered-macro-at-5',
  ndcg: 'cutoff-filtered-at-10-gain-2^grade-1-log2(rank+1)',
  policies: {
    'answerability-f1': { candidates: 'distinct-top-one-scores-plus-all-none', objective: '2TP/(2TP+FN+FP)',
      zeroDenominator: 0, ties: ['fewer-false-matches', 'higher-cutoff'] },
    'answerability-f2': { candidates: 'distinct-top-one-scores-plus-all-none', objective: '5TP/(5TP+4FN+FP)',
      zeroDenominator: 0, ties: ['fewer-false-matches', 'higher-cutoff'] },
    'constrained-recall': { candidates: 'all-distinct-query-passage-scores-plus-all-none', falseMatchRate: 0.05,
      objective: 'macro-recall-at-5', ties: ['ndcg-at-10', 'fewer-false-matches', 'higher-cutoff'] },
  },
  selection: { eligible: 'all-three-development-gates', ties: ['recall-at-5', 'ndcg-at-10', 'lower-false-match-rate', ...policyIds] },
  cutoffRepresentation: { all: { kind: 'all' }, none: { kind: 'none' }, score: { kind: 'score', value: 'finite-number-in-[-1,1]' } },
  uncertainty, collection: 'independent-review-before-first-vector;immutable-attempts;complete-disjoint-batches',
  heldout: 'lock-selection-before-access;exposure-record-before-evaluation;one-result-no-overwrite',
  productionEnablement: false,
};

export function validateDataset(dataset, split) {
  check(Object.hasOwn(splitSizes, split) && dataset?.schema === 2 && dataset.protocolId === protocolId && dataset.split === split, 'Invalid split identity');
  check(dataset.language === 'en' && dataset.passages?.length >= 20 && dataset.passages.length <= 256 &&
    dataset.queries?.length === splitSizes[split] * 2, 'Invalid split population');
  const ids = new Set(); const texts = new Set();
  for (const row of [...dataset.passages, ...dataset.queries]) {
    check(typeof row.id === 'string' && /^[a-z][a-z0-9-]{1,60}$/.test(row.id) && !ids.has(row.id), 'Duplicate/invalid dataset ID'); ids.add(row.id);
    check(typeof row.text === 'string' && Buffer.byteLength(row.text) > 0 && Buffer.byteLength(row.text) <= 4096 && !texts.has(row.text), 'Duplicate/invalid text'); texts.add(row.text);
    check(typeof row.sourceFamily === 'string' && row.sourceFamily.length > 0, 'Missing source family');
  }
  const passageIds = dataset.passages.map(row => row.id);
  for (const query of dataset.queries) {
    check(query.grades && !Array.isArray(query.grades) && typeof query.grades === 'object' &&
      Object.keys(query.grades).length === passageIds.length && passageIds.every(id =>
        Number.isInteger(query.grades[id]) && query.grades[id] >= 0 && query.grades[id] <= 3), 'Incomplete/invalid graded judgments');
  }
  check(dataset.queries.filter(row => Object.values(row.grades).some(grade => grade > 0)).length === splitSizes[split], 'Wrong answerable count');
  check(dataset.queries.filter(row => Object.values(row.grades).every(grade => grade === 0)).length === splitSizes[split], 'Wrong unanswerable count');
  return dataset;
}

export function validateSeparation(datasets) {
  const families = new Map(); const texts = new Set();
  const v1Texts = new Set([...loadSemanticFixture().fixture.passages, ...loadSemanticFixture().fixture.queries].map(row => row.text));
  for (const [split, dataset] of Object.entries(datasets)) {
    validateDataset(dataset, split);
    for (const row of [...dataset.passages, ...dataset.queries]) {
      check(!v1Texts.has(row.text) && !texts.has(row.text), 'Repeated text across splits/v1'); texts.add(row.text);
      check(!families.has(row.sourceFamily) || families.get(row.sourceFamily) === split, 'Source-family leakage'); families.set(row.sourceFamily, split);
    }
  }
}

export function validateReview(review, lockSha256, lock) {
  check(review?.schema === 2 && review.protocolId === protocolId && review.lockSha256 === lockSha256 &&
    review.independent === true && typeof review.reviewer === 'string' && review.reviewer.trim().length >= 3 &&
    review.reviewer !== protocol.author && review.judgmentsReviewed === true && review.separationReviewed === true &&
    review.noScoresInspected === true && Number.isSafeInteger(review.reviewedAtMs) && review.reviewedAtMs > 0 &&
    jsonHash(review.datasets) === jsonHash(lock.datasets), 'Independent pre-collection review is missing/incompatible');
}

export function batchPlans(dataset, binding) {
  validateDataset(dataset, dataset.split);
  const entries = [...dataset.passages.map(row => ({ id: row.id, text: row.text, kind: 'passage' })),
    ...dataset.queries.map(row => ({ id: row.id, text: row.text, kind: 'query' }))];
  const batches = [];
  for (let offset = 0; offset < entries.length; offset += protocol.maxBatchEntries) {
    batches.push({ schema: 2, protocolId, candidate, ...binding, split: dataset.split, index: batches.length,
      entries: entries.slice(offset, offset + protocol.maxBatchEntries) });
  }
  return batches;
}

export function validateBatches(dataset, plans, attempts, reviewAtMs) {
  check(attempts.length === plans.length && plans.length > 0, 'Incomplete batch collection');
  const usedRuns = new Set(); const usedWorkers = new Set(); const vectors = new Map(); let snapshot;
  const ordered = [...attempts].sort((a, b) => a.index - b.index);
  for (const [index, attempt] of ordered.entries()) {
    const plan = plans[index]; const expectedSha = jsonHash(plan);
    check(attempt.index === index && attempt.batchSha256 === expectedSha && attempt.status === 'collected' &&
      typeof attempt.runId === 'string' && attempt.runId.length > 0 && !usedRuns.has(attempt.runId) &&
      Number.isSafeInteger(attempt.startedAtMs) && attempt.startedAtMs >= reviewAtMs, 'Incompatible/duplicate/unreviewed batch');
    usedRuns.add(attempt.runId);
    check(attempt.snapshot && ['appSha256', 'testSha256', 'sourcesSha256'].every(key => /^[a-f0-9]{64}$/.test(attempt.snapshot[key])), 'Missing APK/source snapshot');
    if (!snapshot) snapshot = attempt.snapshot;
    check(jsonHash(snapshot) === jsonHash(attempt.snapshot), 'Mixed collection implementations');
    const fixture = { candidate, passages: plan.entries.filter(row => row.kind === 'passage'), queries: plan.entries.filter(row => row.kind === 'query') };
    const batchVectors = validateQualityCollection(attempt.evidence, { runId: attempt.runId,
      startedAtMs: attempt.startedAtMs, fixtureSha256: expectedSha, policyId: attempt.memoryPolicyId }, fixture);
    check(['memory-baseline-v1', 'memory-android-pressure-test-v1'].includes(attempt.memoryPolicyId), 'Unknown memory profile');
    for (const row of attempt.evidence.rows) {
      check(!vectors.has(row.id) && !usedWorkers.has(row.workerInstance), 'Duplicate row/worker across batches');
      vectors.set(row.id, batchVectors.get(row.id)); usedWorkers.add(row.workerInstance);
    }
  }
  check(vectors.size === dataset.passages.length + dataset.queries.length, 'Missing split vectors');
  return { vectors, snapshot };
}
