import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';

export const semanticFixturePath = 'planning/fixtures/semantic-retrieval-v1.json';
export const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const require = (condition, message) => { if (!condition) throw new Error(message); };
const candidate = {
  artifactSha256: '89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749',
  tokenizerSha256: 'ab32418a9a7f2c0c95e3db6233f2fbd8243e2929a84cd5feec9c1b1ca615c420',
  runtime: 'com.google.mediapipe:tasks-text:1.1.1', format: 'USE_RESPONSE_TEXT_SDK_BYTES_V1',
  transport: 'mediapipe-packet-bytes-v1', headIndex: 0, dimensions: 100, normalization: 'SDK_L2', tokenLimit: 192,
};

export function loadSemanticFixture() {
  const raw = readFileSync(semanticFixturePath);
  const lock = JSON.parse(readFileSync('planning/fixtures/semantic-retrieval-v1.lock.json'));
  require(raw.length <= 128 * 1024 && lock.schema === 1 && lock.frozenBeforeNativeEvaluation === true &&
    lock.sha256 === digest(raw), 'Frozen semantic fixture changed');
  const fixture = JSON.parse(raw);
  validateFixture(fixture);
  return { fixture, sha256: lock.sha256, raw };
}

export function validateFixture(fixture) {
  require(fixture.schema === 1 && fixture.id === 'text-retrieval-qualification-v1', 'Unknown semantic fixture');
  require(Object.keys(candidate).every(key => fixture.candidate?.[key] === candidate[key]), 'Candidate identity changed');
  require(fixture.thresholds?.recallAt5 === 0.90 && fixture.thresholds?.ndcgAt10 === 0.80 &&
    fixture.thresholds?.falseMatchRate === 0.05, 'Qualification thresholds changed');
  require(fixture.languages?.en === 'required' && fixture.languages?.sw === 'diagnostic' &&
    fixture.languages?.ja === 'diagnostic', 'Language scope changed');
  require(Array.isArray(fixture.passages) && fixture.passages.length > 10 && fixture.passages.length <= 128 &&
    Array.isArray(fixture.queries) && fixture.queries.length > 20 && fixture.queries.length <= 128, 'Invalid fixture size');
  const ids = new Set();
  for (const entry of [...fixture.passages, ...fixture.queries]) {
    require(/^[a-z][a-z0-9]{1,30}$/.test(entry.id) && !ids.has(entry.id), 'Invalid/duplicate fixture ID');
    ids.add(entry.id);
    require(typeof entry.text === 'string' && entry.text.length > 0 && entry.text.length <= 16384, 'Invalid fixture text');
  }
  const passageIds = new Set(fixture.passages.map(row => row.id));
  for (const query of fixture.queries) {
    require(['calibration', 'evaluation'].includes(query.split) && Object.hasOwn(fixture.languages, query.language), 'Invalid query split/language');
    require(query.grades && !Array.isArray(query.grades) && typeof query.grades === 'object' &&
      Object.entries(query.grades).every(([id, grade]) => passageIds.has(id) && Number.isInteger(grade) && grade >= 1 && grade <= 3), 'Invalid relevance judgments');
    require(query.split !== 'calibration' || query.language === 'en', 'Unexpected calibration language');
  }
  const calibration = fixture.queries.filter(query => query.split === 'calibration');
  require(calibration.some(query => Object.keys(query.grades).length > 0) &&
    calibration.some(query => Object.keys(query.grades).length === 0), 'Calibration requires positives and negatives');
  const required = fixture.queries.filter(query => query.split === 'evaluation' && query.language === 'en');
  require(required.filter(query => Object.keys(query.grades).length > 0).length >= 20 &&
    required.filter(query => Object.keys(query.grades).length === 0).length >= 20, 'Insufficient held-out English queries');
}

export function cosine(left, right) {
  require(left.length === right.length && left.length > 0, 'Vector dimension mismatch');
  let dot = 0; let a = 0; let b = 0;
  for (let index = 0; index < left.length; index++) {
    require(Number.isFinite(left[index]) && Number.isFinite(right[index]), 'Nonfinite vector');
    dot += left[index] * right[index]; a += left[index] ** 2; b += right[index] ** 2;
  }
  require(a > 0 && b > 0, 'Zero vector');
  return Math.max(-1, Math.min(1, dot / Math.sqrt(a * b)));
}

export function ranked(fixture, vectors, query) {
  return fixture.passages.map(passage => ({ id: passage.id, score: cosine(vectors.get(query.id), vectors.get(passage.id)) }))
    .sort((left, right) => right.score - left.score || (left.id < right.id ? -1 : left.id > right.id ? 1 : 0));
}

// Only calibration rows and their top scores are visible to cutoff selection.
export function calibrateCutoff(fixture, vectors) {
  const samples = fixture.queries.filter(query => query.split === 'calibration').map(query => ({
    answerable: Object.keys(query.grades).length > 0, score: ranked(fixture, vectors, query)[0].score,
  }));
  require(samples.some(row => row.answerable) && samples.some(row => !row.answerable), 'No calibration split');
  const candidates = [...new Set([-1, 1 + Number.EPSILON, ...samples.map(row => row.score)])];
  const fits = candidates.map(cutoff => {
    let tp = 0; let fp = 0; let fn = 0;
    for (const sample of samples) {
      const accepted = sample.score >= cutoff;
      if (accepted && sample.answerable) tp++;
      if (accepted && !sample.answerable) fp++;
      if (!accepted && sample.answerable) fn++;
    }
    return { cutoff, f1: 2 * tp / (2 * tp + fp + fn || 1), falsePositives: fp, positives: tp, misses: fn };
  });
  return fits.sort((left, right) => right.f1 - left.f1 || left.falsePositives - right.falsePositives || right.cutoff - left.cutoff)[0];
}

export function queryMetrics(query, ranking, cutoff) {
  const accepted = ranking.filter(hit => hit.score >= cutoff);
  const relevant = Object.keys(query.grades);
  const recall = relevant.length === 0 ? null : accepted.slice(0, 5).filter(hit => query.grades[hit.id] > 0).length / relevant.length;
  const dcg = grades => grades.reduce((sum, grade, index) => sum + (2 ** grade - 1) / Math.log2(index + 2), 0);
  const ideal = dcg(Object.values(query.grades).sort((a, b) => b - a).slice(0, 10));
  const ndcg = ideal === 0 ? null : dcg(accepted.slice(0, 10).map(hit => query.grades[hit.id] || 0)) / ideal;
  return { id: query.id, answerable: relevant.length > 0, recallAt5: recall, ndcgAt10: ndcg,
    falseMatch: relevant.length === 0 && accepted.length > 0, top: accepted.slice(0, 10).map(hit => hit.id) };
}

export function validateQualityCollection(evidence, expected, fixture) {
  require(evidence?.schema === 1 && evidence.runId === expected.runId && evidence.fixtureSha256 === expected.fixtureSha256 &&
    evidence.collectionPassed === true && evidence.semanticReady === false && evidence.gemmaLoads === 0 &&
    evidence.policyId === expected.policyId && evidence.startedAtMs >= expected.startedAtMs &&
    Number.isSafeInteger(evidence.startedAtMs) && Number.isSafeInteger(evidence.finishedAtMs) &&
    evidence.finishedAtMs >= evidence.startedAtMs && evidence.finalCleanup === 'CONFIRMED', 'No fresh complete quality collection');
  require(Object.keys(candidate).every(key => evidence.candidate?.[key] === candidate[key]), 'Collection candidate mismatch');
  require(Array.isArray(evidence.safetyCases) && evidence.safetyCases.length === 3 &&
    evidence.safetyCases.every((row, index) => row.mode === ['counting', 'loading', 'encoding'][index] &&
      row.outcome === 'CANCELLED' && row.cleanup === 'CONFIRMED' && row.passed === true &&
      Number.isSafeInteger(row.stopToTerminalMs) && row.stopToTerminalMs >= 0 && row.stopToTerminalMs <= 2000 &&
      (index === 0 || (row.confirmedDeath === true && Array.isArray(row.workerPhases) &&
        row.workerPhases.includes(index === 1 ? 'LOADING' : 'ENCODING')))), 'Missing native cancellation evidence');
  const entries = [...fixture.passages.map(row => ({ ...row, kind: 'passage' })), ...fixture.queries.map(row => ({ ...row, kind: 'query' }))];
  require(Array.isArray(evidence.rows) && evidence.rows.length === entries.length, 'Incomplete vector collection');
  const vectors = new Map(); const instances = new Set();
  for (const [index, entry] of entries.entries()) {
    const row = evidence.rows[index];
    require(row.id === entry.id && row.kind === entry.kind && row.textSha256 === digest(Buffer.from(entry.text)) &&
      row.cleanup === 'CONFIRMED' && row.confirmedDeath === true && row.lowMemory === false &&
      Number.isInteger(row.thermalStatus) && row.thermalStatus >= 0 && row.thermalStatus < 3 &&
      Number.isInteger(row.tokenCount) && row.tokenCount >= 2 && row.tokenCount <= 192 &&
      Number.isInteger(row.totalMs) && row.totalMs >= 0 && row.totalMs <= 5000 &&
      typeof row.workerInstance === 'string' && !instances.has(row.workerInstance) &&
      Number.isInteger(row.workerPssKb) && row.workerPssKb > 0 && row.workerPssKb <= 384 * 1024,
    'Invalid or stale vector/cleanup metadata: ' + entry.id);
    require(Array.isArray(row.vector) && row.vector.length === 100 && row.vector.every(Number.isFinite) &&
      Math.abs(Math.sqrt(row.vector.reduce((sum, value) => sum + value * value, 0)) - 1) <= 0.01, 'Invalid vector values');
    vectors.set(entry.id, row.vector); instances.add(row.workerInstance);
  }
  return vectors;
}

export function evaluateSemantic(fixture, vectors) {
  validateFixture(fixture);
  const calibration = calibrateCutoff(fixture, vectors);
  const byLanguage = {};
  for (const [language, scope] of Object.entries(fixture.languages)) {
    const queries = fixture.queries.filter(query => query.split === 'evaluation' && query.language === language)
      .map(query => queryMetrics(query, ranked(fixture, vectors, query), calibration.cutoff));
    const positives = queries.filter(row => row.answerable); const negatives = queries.filter(row => !row.answerable);
    require(positives.length > 0 && negatives.length > 0, 'Missing per-language evaluation examples');
    const recallAt5 = positives.reduce((sum, row) => sum + row.recallAt5, 0) / positives.length;
    const ndcgAt10 = positives.reduce((sum, row) => sum + row.ndcgAt10, 0) / positives.length;
    const falseMatchRate = negatives.filter(row => row.falseMatch).length / negatives.length;
    const passed = recallAt5 >= fixture.thresholds.recallAt5 && ndcgAt10 >= fixture.thresholds.ndcgAt10 && falseMatchRate <= fixture.thresholds.falseMatchRate;
    byLanguage[language] = { scope, answerable: positives.length, unanswerable: negatives.length,
      recallAt5, ndcgAt10, falseMatchRate, passed, queries };
  }
  const first = vectors.get(fixture.passages[0].id);
  const maxComponentSpread = Math.max(...fixture.passages.map(row => Math.max(...vectors.get(row.id).map((value, index) => Math.abs(value - first[index])))));
  return { schema: 1, fixtureId: fixture.id, candidate: fixture.candidate, thresholds: fixture.thresholds, calibration,
    byLanguage, maxComponentSpread, passed: Object.values(byLanguage).filter(row => row.scope === 'required').every(row => row.passed),
    fullRetrievalVerified: false, productionEnablement: false };
}
