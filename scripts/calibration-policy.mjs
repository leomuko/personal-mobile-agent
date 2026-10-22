import { cosine, queryMetrics } from './semantic-quality.mjs';

export const policyIds = ['answerability-f1', 'answerability-f2', 'constrained-recall'];
export const gates = Object.freeze({ recallAt5: 0.9, ndcgAt10: 0.8, falseMatchRate: 0.05 });
export const uncertainty = Object.freeze({ confidence: 0.95, bootstrapSamples: 10000,
  seed: 20261021, generator: 'xorshift32', quantile: 'nearest-rank',
  population: 'answerable-queries', sampling: 'n-draws-with-replacement-floor(u*n);same-draws-for-both-metrics',
  seedReset: 'each-language-report', intervalProbabilities: [0.025, 0.975],
  proportion: 'two-sided-Wilson-without-continuity-correction', z: 1.959963984540054 });
const check = (ok, message) => { if (!ok) throw new Error(message); };

export function threshold(cutoff) {
  check(cutoff && ['all', 'none', 'score'].includes(cutoff.kind), 'Invalid cutoff');
  check(Object.keys(cutoff).length === (cutoff.kind === 'score' ? 2 : 1), 'Unknown cutoff fields');
  if (cutoff.kind === 'score') check(Number.isFinite(cutoff.value) && Math.abs(cutoff.value) <= 1, 'Invalid score boundary');
  return cutoff.kind === 'all' ? -1 : cutoff.kind === 'none' ? 2 : cutoff.value;
}

export function rankings(dataset, vectors) {
  const expected = [...dataset.passages, ...dataset.queries];
  check(vectors instanceof Map && vectors.size === expected.length && expected.every(row => vectors.has(row.id)), 'Incomplete/foreign vectors');
  return dataset.queries.map(entry => ({ query: { ...entry,
    grades: Object.fromEntries(Object.entries(entry.grades).filter(([, grade]) => grade > 0)) }, hits: dataset.passages.map(passage => ({ id: passage.id,
    score: cosine(vectors.get(entry.id), vectors.get(passage.id)) }))
    .sort((a, b) => b.score - a.score || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0)) }));
}

export function summarize(rows) {
  const positives = rows.filter(row => row.answerable); const negatives = rows.filter(row => !row.answerable);
  check(positives.length > 0 && negatives.length > 0, 'Each reported language needs positives and negatives');
  const recallSum = positives.reduce((sum, row) => sum + row.recallAt5, 0);
  const ndcgSum = positives.reduce((sum, row) => sum + row.ndcgAt10, 0);
  const falseMatches = negatives.filter(row => row.falseMatch).length;
  const metrics = { answerable: positives.length, unanswerable: negatives.length, recallSum, ndcgSum,
    falseMatches, recallAt5: recallSum / positives.length, ndcgAt10: ndcgSum / positives.length,
    falseMatchRate: falseMatches / negatives.length };
  return { ...metrics, passed: metrics.recallAt5 >= gates.recallAt5 && metrics.ndcgAt10 >= gates.ndcgAt10 &&
    metrics.falseMatchRate <= gates.falseMatchRate, queries: rows };
}

export function measure(ranked, cutoff) {
  const value = threshold(cutoff);
  return summarize(ranked.map(({ query, hits }) => queryMetrics(query, hits, value)));
}

export function candidates(ranked, policyId) {
  check(policyIds.includes(policyId), 'Unknown policy');
  const scores = policyId === 'constrained-recall' ? ranked.flatMap(row => row.hits.map(hit => hit.score))
    : ranked.map(row => row.hits[0].score);
  check(scores.every(value => Number.isFinite(value) && Math.abs(value) <= 1), 'Invalid calibration scores');
  return [{ kind: 'all' }, ...[...new Set(scores)].sort((a, b) => a - b).map(value => ({ kind: 'score', value })), { kind: 'none' }];
}

export function fitPolicy(ranked, policyId) {
  const fits = candidates(ranked, policyId).map(cutoff => {
    const metrics = measure(ranked, cutoff);
    let tp = 0; let fn = 0;
    for (const { query, hits } of ranked) {
      if (Object.keys(query.grades).length === 0) continue;
      if (hits[0].score >= threshold(cutoff)) tp++; else fn++;
    }
    const fp = metrics.falseMatches;
    const denominator = policyId === 'answerability-f2' ? 5 * tp + 4 * fn + fp : 2 * tp + fn + fp;
    const objective = policyId === 'constrained-recall' ? metrics.recallAt5
      : denominator === 0 ? 0 : (policyId === 'answerability-f2' ? 5 : 2) * tp / denominator;
    return { cutoff, objective, tp, fn, fp, metrics };
  });
  const eligible = policyId === 'constrained-recall' ? fits.filter(row => row.metrics.falseMatchRate <= gates.falseMatchRate) : fits;
  eligible.sort((a, b) => policyId === 'constrained-recall'
    ? b.metrics.recallAt5 - a.metrics.recallAt5 || b.metrics.ndcgAt10 - a.metrics.ndcgAt10 || a.fp - b.fp || threshold(b.cutoff) - threshold(a.cutoff)
    : b.objective - a.objective || a.fp - b.fp || threshold(b.cutoff) - threshold(a.cutoff));
  const best = eligible[0];
  return { policyId, cutoff: best.cutoff, objectiveName: policyId === 'constrained-recall' ? 'macro-recall-at-5' : policyId,
    objective: best.objective, tp: best.tp, fn: best.fn, fp: best.fp,
    calibration: best.metrics };
}

// No held-out argument: selection cannot accidentally score a third split.
export function selectPolicy(calibrationRanked, developmentRanked) {
  const policies = policyIds.map(id => {
    const fit = fitPolicy(calibrationRanked, id);
    return { ...fit, development: measure(developmentRanked, fit.cutoff) };
  });
  const eligible = policies.filter(row => row.development.passed).sort((a, b) =>
    b.development.recallAt5 - a.development.recallAt5 || b.development.ndcgAt10 - a.development.ndcgAt10 ||
    a.development.falseMatchRate - b.development.falseMatchRate || policyIds.indexOf(a.policyId) - policyIds.indexOf(b.policyId));
  return { policies, selected: eligible[0] ? { policyId: eligible[0].policyId, cutoff: eligible[0].cutoff } : null,
    status: eligible.length ? 'selected' : 'quality-failed', productionEnablement: false };
}

export function wilson(successes, trials) {
  check(Number.isSafeInteger(successes) && Number.isSafeInteger(trials) && trials > 0 && successes >= 0 && successes <= trials, 'Invalid proportion');
  const z = uncertainty.z; const p = successes / trials; const divisor = 1 + z * z / trials;
  const center = (p + z * z / (2 * trials)) / divisor;
  const half = z * Math.sqrt(p * (1 - p) / trials + z * z / (4 * trials * trials)) / divisor;
  return [Math.max(0, center - half), Math.min(1, center + half)];
}

export function intervals(rows) {
  const positives = rows.filter(row => row.answerable); const negatives = rows.filter(row => !row.answerable);
  check(positives.length && negatives.length, 'Empty uncertainty population');
  let state = uncertainty.seed >>> 0;
  const next = () => { state ^= state << 13; state ^= state >>> 17; state ^= state << 5; return (state >>> 0) / 4294967296; };
  const recall = []; const ndcg = [];
  for (let sample = 0; sample < uncertainty.bootstrapSamples; sample++) {
    let r = 0; let n = 0;
    for (let index = 0; index < positives.length; index++) {
      const row = positives[Math.floor(next() * positives.length)]; r += row.recallAt5; n += row.ndcgAt10;
    }
    recall.push(r / positives.length); ndcg.push(n / positives.length);
  }
  const bounds = samples => {
    samples.sort((a, b) => a - b);
    return [samples[Math.ceil(0.025 * samples.length) - 1], samples[Math.ceil(0.975 * samples.length) - 1]];
  };
  return { method: uncertainty, descriptiveOnly: true, recallAt5: bounds(recall), ndcgAt10: bounds(ndcg),
    falseMatchRate: wilson(negatives.filter(row => row.falseMatch).length, negatives.length) };
}
