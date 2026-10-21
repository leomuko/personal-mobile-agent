import test from 'node:test';
import assert from 'node:assert/strict';
import { loadSemanticFixture, validateFixture, cosine, calibrateCutoff, queryMetrics,
  evaluateSemantic, validateQualityCollection, digest } from '../semantic-quality.mjs';

const { fixture, sha256 } = loadSemanticFixture();
const unit = index => Array.from({ length: 100 }, (_, position) => position === index ? 1 : 0);
function idealVectors() {
  const vectors = new Map(fixture.passages.map((row, index) => [row.id, unit(index)]));
  vectors.set('x01', vectors.get('d01')); vectors.set('x02', vectors.get('d07'));
  for (const row of fixture.queries) vectors.set(row.id, vectors.get(Object.keys(row.grades)[0]) || unit(99));
  return vectors;
}

test('frozen fixture binds candidate, thresholds, language scope and held-out judgments', () => {
  assert.equal(fixture.passages.length, 34);
  assert.equal(fixture.queries.length, 60);
  for (const mutate of [value => { value.candidate.headIndex = 1; }, value => { value.thresholds.recallAt5 = 0.5; },
    value => { value.languages.en = 'diagnostic'; }, value => { value.queries[0].grades.missing = 3; },
    value => { value.queries[0].id = value.passages[0].id; }]) {
    const changed = structuredClone(fixture); mutate(changed);
    assert.throws(() => validateFixture(changed));
  }
});

test('cosine rejects nonfinite zero and incompatible vectors', () => {
  assert.equal(cosine([1, 0], [1, 0]), 1);
  assert.equal(cosine([1, 0], [0, 1]), 0);
  assert.equal(cosine([1, 0], [-1, 0]), -1);
  for (const [left, right] of [[[0], [1]], [[NaN], [1]], [[1], [Infinity]], [[1, 2], [1]]])
    assert.throws(() => cosine(left, right));
});

test('graded recall and nDCG preserve relevant contradictory passages without truth claims', () => {
  const query = { id: 'contradiction', grades: { original: 3, conflicting: 3 } };
  const perfect = queryMetrics(query, [{ id: 'original', score: 0.9 }, { id: 'conflicting', score: 0.8 }], 0.5);
  assert.equal(perfect.recallAt5, 1); assert.equal(perfect.ndcgAt10, 1);
  const filtered = queryMetrics(query, [{ id: 'original', score: 0.9 }, { id: 'conflicting', score: 0.4 }], 0.5);
  assert.equal(filtered.recallAt5, 0.5); assert.ok(filtered.ndcgAt10 > 0 && filtered.ndcgAt10 < 1);
  assert.equal(queryMetrics({ id: 'none', grades: {} }, [{ id: 'original', score: 0.9 }], 0.5).falseMatch, true);
});

test('cutoff calibration cannot read held-out query vectors or relevance judgments', () => {
  const original = calibrateCutoff(fixture, idealVectors());
  const changed = structuredClone(fixture); const vectors = idealVectors();
  for (const row of changed.queries.filter(row => row.split === 'evaluation')) {
    row.grades = {}; vectors.set(row.id, unit(97));
  }
  assert.deepEqual(calibrateCutoff(changed, vectors), original);
});

test('perfect synthetic retrieval passes while a constant head fails frozen gates', () => {
  const perfect = evaluateSemantic(fixture, idealVectors());
  assert.equal(perfect.passed, true); assert.equal(perfect.byLanguage.en.recallAt5, 1);
  assert.equal(perfect.byLanguage.en.ndcgAt10, 1); assert.equal(perfect.byLanguage.en.falseMatchRate, 0);
  const constant = new Map([...fixture.passages, ...fixture.queries].map(row => [row.id, unit(0)]));
  const result = evaluateSemantic(fixture, constant);
  assert.equal(result.passed, false); assert.equal(result.byLanguage.en.recallAt5, 0);
  assert.equal(result.byLanguage.en.falseMatchRate, 1); assert.equal(result.maxComponentSpread, 0);
  assert.equal(result.byLanguage.sw.scope, 'diagnostic'); assert.equal(result.productionEnablement, false);
});

const expected = { runId: 'synthetic-run', startedAtMs: 1000, fixtureSha256: sha256, policyId: 'synthetic-policy' };
function collection() {
  const vectors = idealVectors();
  return { schema: 1, ...expected, collectionPassed: true, semanticReady: false, gemmaLoads: 0,
    candidate: fixture.candidate, finishedAtMs: 2000, finalCleanup: 'CONFIRMED',
    safetyCases: ['counting', 'loading', 'encoding'].map(mode => ({ mode, outcome: 'CANCELLED', cleanup: 'CONFIRMED',
      passed: true, confirmedDeath: mode !== 'counting', stopToTerminalMs: 10,
      workerPhases: mode === 'encoding' ? ['CHECKING', 'LOADING', 'ENCODING'] : ['CHECKING', 'LOADING'] })),
    rows: [...fixture.passages.map(row => ({ ...row, kind: 'passage' })), ...fixture.queries.map(row => ({ ...row, kind: 'query' }))]
      .map((row, index) => ({ id: row.id, kind: row.kind, textSha256: digest(Buffer.from(row.text)), vector: vectors.get(row.id),
        cleanup: 'CONFIRMED', confirmedDeath: true, lowMemory: false, thermalStatus: 0, tokenCount: 10,
        totalMs: 20, workerInstance: `worker-${index}`, workerPssKb: 90000 })) };
}

test('collection requires fresh correlated vectors, native cleanup and unchanged identity', () => {
  assert.equal(validateQualityCollection(collection(), expected, fixture).size, 94);
  for (const mutate of [value => { value.runId = 'old'; }, value => { value.startedAtMs = 0; },
    value => { value.fixtureSha256 = 'wrong'; }, value => { value.rows.pop(); },
    value => { value.candidate.headIndex = 1; }, value => { value.finalCleanup = 'UNCERTAIN'; },
    value => { value.rows[0].lowMemory = true; }, value => { value.rows[0].tokenCount = 193; },
    value => { value.rows[0].vector[0] = NaN; }, value => { value.rows[0].textSha256 = 'wrong'; },
    value => { value.rows[1].workerInstance = value.rows[0].workerInstance; }, value => { value.gemmaLoads = 1; },
    value => { value.rows[0].totalMs = 5001; }, value => { value.safetyCases.pop(); },
    value => { value.safetyCases[2].confirmedDeath = false; }, value => { value.safetyCases[2].workerPhases = ['LOADING']; }]) {
    const value = structuredClone(collection()); mutate(value);
    assert.throws(() => validateQualityCollection(value, expected, fixture));
  }
});
