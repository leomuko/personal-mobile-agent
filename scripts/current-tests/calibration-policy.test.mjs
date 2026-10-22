import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { spawnSync } from 'node:child_process';
import { candidates, fitPolicy, selectPolicy, threshold, measure, intervals, wilson, rankings } from '../calibration-policy.mjs';
import { protocol, protocolId, splitSizes, candidate, jsonHash, readJson, validateDataset,
  validateSeparation, validateReview, batchPlans, validateBatches } from '../calibration-data.mjs';
import { freeze, loadLock, plansFor, fitAndSelect, evaluateOnce, writeOnce } from '../calibration-investigation.mjs';
import { reserveBatch, acceptBatch, releaseBatch } from '../calibration-collection.mjs';
import { loadSemanticFixture, digest, calibrateCutoff } from '../semantic-quality.mjs';

const row = (id, grades, scores) => ({ query: { id, grades }, hits: scores.map(([id, score]) => ({ id, score })) });
const ranked = [row('positive', { a: 3, b: 3 }, [['a', 0.9], ['b', 0.6], ['c', 0.1]]),
  row('negative', {}, [['c', 0.5], ['a', 0.4], ['b', 0.2]])];

test('constrained fitting evaluates lower-ranked boundaries rather than only top-one scores', () => {
  assert.equal(candidates(ranked, 'answerability-f1').some(row => row.value === 0.6), false);
  assert.equal(candidates(ranked, 'constrained-recall').some(row => row.value === 0.6), true);
  assert.deepEqual(fitPolicy(ranked, 'constrained-recall').cutoff, { kind: 'score', value: 0.6 });
  assert.equal(fitPolicy(ranked, 'constrained-recall').calibration.recallAt5, 1);
  assert.equal(fitPolicy(ranked, 'answerability-f1').calibration.recallAt5, 0.5);
});

test('constrained recall reports macro recall, not answerability F1, as its named objective', () => {
  const fit = fitPolicy([row('p', { a: 3, b: 3 }, [['a', 0.9], ['b', 0.4]]),
    row('n', {}, [['a', 0.5], ['b', 0.3]])], 'constrained-recall');
  assert.equal(fit.tp, 1); assert.equal(fit.fn, 0); assert.equal(fit.fp, 0);
  assert.equal(fit.objectiveName, 'macro-recall-at-5');
  assert.equal(fit.objective, 0.5);
  assert.equal(fit.objective, fit.calibration.recallAt5);
  assert.equal(fitPolicy(ranked, 'answerability-f1').objectiveName, 'answerability-f1');
  assert.equal(fitPolicy(ranked, 'answerability-f2').objectiveName, 'answerability-f2');
});

test('F2 uses the frozen formula; all/none are JSON-safe; equality is accepted', () => {
  const fit = fitPolicy([row('p1', { a: 3 }, [['a', 0.8]]), row('p2', { a: 3 }, [['a', 0.4]]),
    row('n', {}, [['a', 0.5]])], 'answerability-f2');
  assert.equal(fit.objective, 10 / 11);
  assert.deepEqual(fit.cutoff, { kind: 'score', value: 0.4 });
  assert.equal(measure(ranked, { kind: 'score', value: 0.9 }).queries[0].recallAt5, 0.5);
  for (const cutoff of [{ kind: 'none' }, { kind: 'all' }]) assert.equal(threshold(JSON.parse(JSON.stringify(cutoff))), threshold(cutoff));
  for (const cutoff of [{ kind: 'score', value: NaN }, { kind: 'score', value: 2 }, { kind: 'none', value: null }])
    assert.throws(() => threshold(cutoff));
});

test('selection is deterministic, uses development gates and never refits to development', () => {
  const calibration = [row('p', { a: 3 }, [['a', 0.9]]), row('n', {}, [['a', 0.2]])];
  const selected = selectPolicy(calibration, calibration);
  assert.equal(selected.selected.policyId, 'answerability-f1');
  const missed = [row('p', { a: 3 }, [['a', 0.7]]), row('n', {}, [['a', 0.2]])];
  const failed = selectPolicy(calibration, missed);
  assert.equal(failed.selected, null);
  assert.deepEqual(failed.policies.map(row => row.cutoff), selected.policies.map(row => row.cutoff));
});

test('macro metrics and fixed uncertainty are deterministic; point gates remain 3/60 vs 4/60', () => {
  const positives = Array.from({ length: 60 }, (_, index) => ({ id: `p${index}`, answerable: true, recallAt5: 1, ndcgAt10: 1 }));
  const negatives = Array.from({ length: 60 }, (_, index) => ({ id: `n${index}`, answerable: false, falseMatch: index < 3 }));
  const withScores = [...positives.map(q => row(q.id, { a: 3 }, [['a', 1]])),
    ...negatives.map(q => row(q.id, {}, [['a', q.falseMatch ? 1 : 0]]))];
  assert.equal(measure(withScores, { kind: 'score', value: 1 }).passed, true);
  withScores.at(-1).hits[0].score = 1;
  assert.equal(measure(withScores, { kind: 'score', value: 1 }).passed, false);
  const report = intervals([...positives, ...negatives]);
  assert.deepEqual(report, intervals([...positives, ...negatives]));
  assert.deepEqual(report.recallAt5, [1, 1]);
  assert.deepEqual(report.ndcgAt10, [1, 1]);
  assert.ok(report.falseMatchRate[0] < 0.05 && report.falseMatchRate[1] > 0.05);
  assert.deepEqual(wilson(0, 60)[0], 0);
  assert.throws(() => wilson(61, 60));
});

function dataset(split) {
  const passages = Array.from({ length: 20 }, (_, index) => ({ id: `${split}-p${index}`, sourceFamily: split,
    text: `Synthetic host-only ${split} passage ${index}` }));
  const queries = Array.from({ length: splitSizes[split] * 2 }, (_, index) => ({ id: `${split}-q${index}`,
    sourceFamily: split, text: `Synthetic host-only ${split} query ${index}`,
    grades: Object.fromEntries(passages.map((row, p) => [row.id, index < splitSizes[split] && p === index % 20 ? 3 : 0])) }));
  return { schema: 2, protocolId, split, language: 'en', passages, queries };
}
const unit = index => Array.from({ length: 100 }, (_, column) => column === index ? 1 : 0);
function vectorFor(dataset, entry) {
  const passage = dataset.passages.findIndex(row => row.id === entry.id);
  if (passage >= 0) return unit(passage);
  const q = dataset.queries.find(row => row.id === entry.id);
  const positive = Object.entries(q.grades).find(([, grade]) => grade > 0)?.[0];
  return unit(positive ? dataset.passages.findIndex(row => row.id === positive) : 99);
}
const snapshot = { appSha256: 'a'.repeat(64), testSha256: 'b'.repeat(64), sourcesSha256: 'c'.repeat(64) };
function attempts(data, plans) {
  return plans.map(plan => ({ split: data.split, index: plan.index, batchSha256: jsonHash(plan), status: 'collected',
    runId: `${data.split}-${plan.index}`, startedAtMs: 2000, memoryPolicyId: 'memory-baseline-v1', snapshot,
    evidence: { schema: 1, runId: `${data.split}-${plan.index}`, startedAtMs: 2000, finishedAtMs: 3000,
      fixtureSha256: jsonHash(plan), candidate, policyId: 'memory-baseline-v1', finalCleanup: 'CONFIRMED',
      collectionPassed: true, semanticReady: false, gemmaLoads: 0,
      safetyCases: ['counting', 'loading', 'encoding'].map(mode => ({ mode, outcome: 'CANCELLED', cleanup: 'CONFIRMED',
        passed: true, stopToTerminalMs: 1, confirmedDeath: true, workerPhases: ['LOADING', 'ENCODING'] })),
      rows: plan.entries.map(entry => ({ id: entry.id, kind: entry.kind, textSha256: digest(Buffer.from(entry.text)),
        vector: vectorFor(data, entry), cleanup: 'CONFIRMED', confirmedDeath: true, lowMemory: false,
        thermalStatus: 0, tokenCount: 10, totalMs: 20, workerInstance: `${data.split}-${entry.id}`,
        workerPssKb: 90000 })) } }));
}

test('datasets require complete judgments and disjoint families; review cannot be self-certified', () => {
  const cal = dataset('calibration'); const dev = dataset('development');
  validateSeparation({ calibration: cal, development: dev });
  delete cal.queries[0].grades[cal.passages[0].id];
  assert.throws(() => validateDataset(cal, 'calibration'));
  dev.passages[0].sourceFamily = 'calibration';
  assert.throws(() => validateSeparation({ calibration: dataset('calibration'), development: dev }));
  assert.throws(() => validateReview({ reviewer: protocol.author }, 'x', {}));
});

test('bounded batches reject missing duplicate stale unsafe and mixed-implementation evidence', () => {
  const data = dataset('calibration'); const plans = batchPlans(data, {}); const good = attempts(data, plans);
  assert.ok(plans.every(plan => plan.entries.length <= 32));
  assert.equal(validateBatches(data, plans, good, 1000).vectors.size, 140);
  for (const mutate of [rows => rows.pop(), rows => { rows[1] = rows[0]; },
    rows => { rows[0].startedAtMs = 999; }, rows => { rows[0].evidence.rows[0].lowMemory = true; },
    rows => { rows[1].snapshot = { ...snapshot, appSha256: 'd'.repeat(64) }; },
    rows => { rows[0].evidence.rows[0].vector.pop(); }, rows => { rows[0].evidence.finalCleanup = 'UNCERTAIN'; }]) {
    const broken = structuredClone(good); mutate(broken);
    assert.throws(() => validateBatches(data, plans, broken, 1000));
  }
});

function campaign() {
  const root = mkdtempSync(join(tmpdir(), 'calibration-policy-test-')); const state = join(root, 'state');
  writeOnce(join(root, 'protocol.json'), protocol);
  for (const split of Object.keys(splitSizes)) writeOnce(join(root, `${split}.json`), dataset(split));
  freeze(root); const lock = loadLock(root);
  const review = { schema: 2, protocolId, lockSha256: lock.sha256, datasets: lock.value.datasets,
    independent: true, reviewer: 'synthetic-test-reviewer-not-a-real-approval', judgmentsReviewed: true,
    separationReviewed: true, noScoresInspected: true, reviewedAtMs: 1000 };
  const approve = () => writeOnce(join(root, 'independent-review.json'), review);
  const collect = (split, mutate = () => {}) => {
    const plans = plansFor(root, split); const data = dataset(split);
    const path = join(root, `${split}-collection.json`);
    const rows = attempts(data, plans);
    mutate(rows);
    for (const [index, attempt] of rows.entries()) {
      const file = join(root, `${split}-attempt-${index}.json`); writeOnce(file, attempt);
      const lease = reserveBatch(root, plans[index], attempt.runId, root); acceptBatch(lease, file); releaseBatch(lease);
    }
    writeOnce(path, { schema: 2, split, lockSha256: lock.sha256, attempts: rows });
    return path;
  };
  return { root, state, approve, collect };
}

test('missing review blocks planning before any ADB access and frozen changes fail closed', () => {
  const c = campaign();
  try {
    assert.throws(() => plansFor(c.root, 'calibration'), /independent-review/);
    const wrapper = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--calibration-root', c.root,
      '--calibration-batch', 'absent.json'], { env: { ...process.env, ADB: '/no-device-access', ANDROID_SERIAL: 'synthetic' }, encoding: 'utf8' });
    assert.notEqual(wrapper.status, 0); assert.match(wrapper.stderr, /independent-review/);
    c.approve(); assert.ok(plansFor(c.root, 'calibration').length > 1);
    writeFileSync(join(c.root, 'calibration.json'), '{}');
    assert.throws(() => plansFor(c.root, 'calibration'), /changed/);
  } finally { rmSync(c.root, { recursive: true, force: true }); }
});

test('fit/select never reads held-out file and cannot be rerun in another output directory', () => {
  const c = campaign();
  try {
    c.approve(); const cal = c.collect('calibration'); const dev = c.collect('development');
    writeFileSync(join(c.root, 'heldout.json'), 'not-json-heldout-must-not-be-read');
    const result = fitAndSelect(c.root, c.state, cal, dev);
    assert.equal(result.status, 'selected');
    assert.equal(JSON.stringify(result).includes('heldout-q'), false);
    assert.throws(() => fitAndSelect(c.root, join(c.root, 'another-state'), cal, dev), /already/);
  } finally { rmSync(c.root, { recursive: true, force: true }); }
});

test('held-out plans and collector reject missing failed and tampered selections before ADB access', () => {
  for (const mode of ['missing', 'failed', 'tampered-manifest', 'tampered-seal']) {
    const c = campaign();
    try {
      c.approve();
      if (mode !== 'missing') {
        const cal = c.collect('calibration');
        const dev = c.collect('development', rows => {
          if (mode === 'failed') for (const attempt of rows) for (const entry of attempt.evidence.rows)
            if (entry.kind === 'query') entry.vector = unit(99);
        });
        const selected = fitAndSelect(c.root, c.state, cal, dev);
        assert.equal(selected.status, mode === 'failed' ? 'quality-failed' : 'selected');
        if (mode === 'tampered-manifest') {
          selected.selected.cutoff = { kind: 'all' };
          writeFileSync(join(c.state, 'selection.json'), JSON.stringify(selected));
        } else if (mode === 'tampered-seal') {
          const seal = readJson(join(c.root, 'selection-seal.json')).value;
          seal.selectionSha256 = '0'.repeat(64);
          writeFileSync(join(c.root, 'selection-seal.json'), JSON.stringify(seal));
        }
      }
      assert.throws(() => plansFor(c.root, 'heldout'), /selection|selected|seal/i, mode);
      const lock = loadLock(c.root); const review = readJson(join(c.root, 'independent-review.json'));
      const batch = batchPlans(dataset('heldout'), { lockSha256: lock.sha256, reviewSha256: review.sha256,
        datasetSha256: lock.value.datasets.heldout, protocolSha256: lock.value.protocolSha256 })[0];
      const batchPath = join(c.root, 'previously-exported-heldout-batch.json'); writeOnce(batchPath, batch);
      const marker = join(c.root, 'adb-called'); const adb = join(c.root, 'adb');
      writeFileSync(adb, '#!/bin/sh\nprintf called > "$ADB_CALL_MARKER"\nexit 99\n', { mode: 0o700 });
      const result = spawnSync(process.execPath, ['scripts/run-p2-embedding-gate.mjs', '--calibration-root', c.root,
        '--calibration-batch', batchPath], { env: { ...process.env, ADB: adb, ADB_CALL_MARKER: marker,
          ANDROID_SERIAL: 'synthetic-no-device', P2_ENCODER_MODEL: '/not-a-model' }, encoding: 'utf8' });
      assert.notEqual(result.status, 0, mode);
      assert.match(result.stderr, /selection|selected|seal/i, mode);
      assert.equal(existsSync(marker), false, mode);
    } finally { rmSync(c.root, { recursive: true, force: true }); }
  }
});

test('successful held-out plans bind the exact selected manifest and stop after exposure', () => {
  const c = campaign();
  try {
    c.approve(); const cal = c.collect('calibration'); const dev = c.collect('development');
    fitAndSelect(c.root, c.state, cal, dev);
    const selected = readJson(join(c.state, 'selection.json'));
    const plans = plansFor(c.root, 'heldout');
    assert.ok(plans.every(plan => plan.selectionSha256 === selected.sha256));
    const seal = readFileSync(join(c.root, 'selection-seal.json'));
    rmSync(join(c.root, 'selection-seal.json'));
    assert.throws(() => plansFor(c.root, 'heldout'), /Missing sealed selection/);
    writeFileSync(join(c.root, 'selection-seal.json'), seal);
    writeOnce(join(c.root, 'exposed.json'), { selectionSha256: selected.sha256 });
    assert.throws(() => plansFor(c.root, 'heldout'), /already exposed/);
  } finally { rmSync(c.root, { recursive: true, force: true }); }
});

test('evaluation requires unmodified sealed selection and preserves the first exposed result', () => {
  const c = campaign();
  try {
    c.approve(); const cal = c.collect('calibration'); const dev = c.collect('development');
    assert.throws(() => evaluateOnce(c.root, c.state, 'heldout-collection-not-yet-admitted.json'));
    fitAndSelect(c.root, c.state, cal, dev);
    const held = c.collect('heldout');
    const path = join(c.state, 'selection.json'); const bytes = readFileSync(path);
    const changed = JSON.parse(bytes); changed.selected.cutoff = { kind: 'all' };
    writeFileSync(path, JSON.stringify(changed));
    assert.throws(() => evaluateOnce(c.root, c.state, held), /seal/);
    writeFileSync(path, bytes);
    const result = evaluateOnce(c.root, c.state, held);
    assert.equal(result.status, 'quality-passed'); assert.equal(result.productionEnablement, false);
    const saved = readFileSync(join(c.state, 'result.json'));
    assert.throws(() => evaluateOnce(c.root, c.state, held));
    assert.deepEqual(readFileSync(join(c.state, 'result.json')), saved);
    assert.throws(() => fitAndSelect(c.root, join(c.root, 'elsewhere'), cal, dev));
  } finally { rmSync(c.root, { recursive: true, force: true }); }
});

test('failure after exposure is retained and cannot be retried as a fresh held-out evaluation', () => {
  const c = campaign();
  try {
    c.approve(); const cal = c.collect('calibration'); const dev = c.collect('development');
    fitAndSelect(c.root, c.state, cal, dev);
    const held = c.collect('heldout');
    const broken = readJson(held).value; broken.attempts.pop(); writeFileSync(held, JSON.stringify(broken));
    assert.throws(() => evaluateOnce(c.root, c.state, held), /Incomplete/);
    assert.equal(readJson(join(c.state, 'evaluation-failure.json')).value.status, 'infrastructure-failed-after-exposure');
    assert.throws(() => evaluateOnce(c.root, c.state, held), /EEXIST/);
  } finally { rmSync(c.root, { recursive: true, force: true }); }
});

test('batch ownership blocks overlap, allows infrastructure retry, and never replaces successful vectors', () => {
  const c = campaign();
  try {
    c.approve(); const plan = plansFor(c.root, 'calibration')[0];
    const first = reserveBatch(c.root, plan, 'first', c.root);
    assert.throws(() => reserveBatch(c.root, plan, 'second', c.root), /EEXIST/);
    releaseBatch(first);
    const retry = reserveBatch(c.root, plan, 'retry', c.root);
    const attempt = attempts(dataset('calibration'), [plan])[0]; attempt.runId = 'retry';
    const path = join(c.root, 'attempt.json'); writeOnce(path, attempt);
    acceptBatch(retry, path); releaseBatch(retry);
    assert.throws(() => reserveBatch(c.root, plan, 'third', c.root), /already sealed/);
  } finally { rmSync(c.root, { recursive: true, force: true }); }
});

test('versioned fixtures retain their identities and v1 fitter remains unchanged', () => {
  const datasets = Object.fromEntries(Object.keys(splitSizes).map(split => [split,
    readJson(`planning/fixtures/calibration-policy-v2/${split}.json`).value]));
  validateSeparation(datasets);
  const old = loadSemanticFixture();
  assert.equal(old.sha256, '32f9d6d231fb5f5acca593eb27e128bc0f09266e311375db82cff100b6325abe');
  const vectors = new Map([...old.fixture.passages, ...old.fixture.queries].map(row => [row.id, unit(0)]));
  assert.equal(calibrateCutoff(old.fixture, vectors).cutoff, 1);
  const d = dataset('calibration'); const map = new Map([...d.passages, ...d.queries].map(row => [row.id, vectorFor(d, row)]));
  assert.equal(rankings(d, map)[0].query.grades[d.passages[1].id], undefined, 'Grade zero must not become relevant');
});

test('renewed depth freeze preserves rejected bytes and changes only the requested question', () => {
  const root = 'planning/fixtures/calibration-policy-v2';
  const archive = `${root}/rejected/2026-10-22-depth-ambiguity`;
  const snapshot = readJson(`${archive}/snapshot.json`).value;
  assert.equal(snapshot.status, 'rejected-before-collection');
  for (const [path, sha] of Object.entries(snapshot.files)) assert.equal(digest(readFileSync(`${archive}/${path}`)), sha);
  const previous = readFileSync(`${archive}/development.json`, 'utf8');
  const current = readFileSync(`${root}/development.json`, 'utf8');
  const before = 'Borage stand: How deep are the oil cups?';
  const after = 'Borage stand: What is the oil cup depth in millimetres?';
  assert.equal(previous.split(before).length, 2);
  assert.equal(current, previous.replace(before, after));
  const oldDataset = JSON.parse(previous); const dataset = JSON.parse(current);
  const oldQuery = oldDataset.queries.find(row => row.id === 'dev020n1');
  const query = dataset.queries.find(row => row.id === 'dev020n1');
  assert.deepEqual(query, { ...oldQuery, text: after });
  assert.ok(Object.values(query.grades).every(grade => grade === 0));
  for (const path of ['calibration.json', 'heldout.json', 'protocol.json'])
    assert.deepEqual(readFileSync(`${root}/${path}`), readFileSync(`${archive}/${path}`));
  assert.deepEqual(readFileSync('docs/P2_CALIBRATION_JUDGMENT_REVIEW.md'), readFileSync(`${archive}/review.md`));
  const oldLock = readJson(`${archive}/lock.json`).value; const lock = readJson(`${root}/lock.json`);
  assert.throws(() => loadLock(root), /Stale\/unfrozen investigation/, 'Historical runtime must not be admitted as current code');
  assert.deepEqual(lock.value, { ...oldLock, datasets: { ...oldLock.datasets, development: digest(Buffer.from(current)) } });
});
