import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  candidateDirectory, checkRun, containsFact, counts, determinism, devFixture, developmentDisjointness, developmentNotice,
  developmentRetrieval, distinctEntities, expectedLoads, fixtureDirectory, fixturePassages, fixtureRoot, loadCandidates,
  fixtureSummary, keywordOverlap, loadFixture, markdown, normalize, passageBoundaries, precheck, profile, promptVersion,
  sampleSupported, sampler, schedule, score, sha256, twoPassageKinds, validateFixture, validCandidateCooldowns,
} from '../answer-quality.mjs';

const fixture = loadFixture();
const state = () => ({ document: {schema: 1, items: structuredClone(fixture.items)},
  library: {...fixture.library}, lock: structuredClone(fixture.lock) });
const rebound = (library, lock) => {
  lock.passageBoundaries = Object.fromEntries(Object.entries(library).map(([name, text]) => [name, passageBoundaries(text)]));
};
// Library edits recompute the golden boundaries unless the case is about the boundaries themselves.
const reject = (name, change, reason, { keepBoundaries = false } = {}) => test(`validator rejects ${name}`, () => {
  const {document, library, lock} = state();
  change(document.items, library, lock, document);
  if (!keepBoundaries) rebound(library, lock);
  assert.throws(() => validateFixture(document, library, lock, fixture.lock.files), reason);
});
const item = (items, set) => items.find(entry => entry.set === set);
const byId = (items, id) => items.find(entry => entry.id === id);
const firstLibrary = library => Object.keys(library)[0];

test('frozen corpus has the exact mix and 20 ORDINARY controls', () => {
  assert.equal(Object.keys(fixture.library).length, 18);
  assert.deepEqual(Object.fromEntries(Object.keys(counts).map(set => [set,
    fixture.items.filter(entry => entry.set === set).length])), counts);
  assert.equal(schedule(fixture.items, 'tuning', 1).length, 10);
  assert.equal(schedule(fixture.items, 'heldout', 1).length, 102);
  assert.equal(schedule(fixture.items, 'heldout', 3).length, 306);
  assert.deepEqual(schedule(fixture.items, 'heldout', 1).slice(82).map(row => row.scope), Array(20).fill('ORDINARY'));
  assert.equal(fixture.items.filter(entry => entry.set === 'answer-paraphrase').every(entry => entry.keywordOverlap.length <= 2), true);
});

test('S6Q-F1: sources span several passages and each project name is in more than four of them', () => {
  const summary = fixtureSummary(fixture);
  assert.ok(Object.values(summary.sources).every(source => source.passages >= 2));
  assert.ok(Object.values(summary.passagesPerEntity).every(count => count > 4));
  assert.ok(summary.libraryBytes > 3 * 6017, 'the library is substantially longer than round 1');
  assert.ok(Object.values(summary.sources).every(source => source.bytes <= 65536));
});

test('S6Q-F10: at least five two-passage items ask facts no one-passage item asks', () => {
  const kinds = twoPassageKinds(fixture.items);
  assert.ok(kinds.independent.length >= 5);
  assert.equal(kinds.independent.length + kinds.paired.length, 10);
});

test('S6Q-F2: near-miss questions name a distinct library entity, never a spelling variant', () => {
  for (const row of fixture.items.filter(entry => entry.set === 'unanswerable-near-miss'))
    assert.ok(distinctEntities(row.nearMiss.entity, row.nearMiss.nearEntity), row.id);
  assert.equal(distinctEntities('Velquorim', 'Velquorin'), false);
  assert.equal(distinctEntities('Thesparen', 'Thesparil'), false);
  assert.equal(distinctEntities('Thesparil', 'Velquorin'), true);
});

test('S6Q-F3/F4: forbidden facts are in-library distractors; bait values are general knowledge absent from the notes', () => {
  const texts = Object.values(fixture.library);
  for (const row of fixture.items) {
    const present = row.forbiddenFacts.map(fact => texts.some(text => containsFact(text, fact)));
    assert.ok(row.set === 'diagnostic-bait' ? present.every(found => !found) && present.length > 0 : present.every(Boolean), row.id);
  }
  const baits = fixture.items.filter(entry => entry.set === 'diagnostic-bait').flatMap(entry => entry.forbiddenFacts);
  for (const value of ['Oslo', '100', '230', 'yen', 'Cu']) assert.ok(baits.includes(value), value);
});

reject('wrong item count', items => { items.pop(); }, /incorrect counts/);
reject('an unknown field', items => { items[0].extra = true; }, /item schema/);
reject('a duplicate id or question', items => { items[1].id = items[0].id; }, /duplicate/);
reject('a missing required fact', items => { items[0].requiredFacts = ['Mizorquelt']; }, /required fact missing/);
reject('a nonexistent quote', items => { items[0].answerSpans[0].text = 'Zornext supply lot'; }, /span does not uniquely resolve/);
reject('a forbidden fact in a gold quote', items => { items[0].forbiddenFacts = items[0].requiredFacts; }, /forbidden fact/);
reject('a forbidden placeholder that is not in the library', items => { items[0].forbiddenFacts = ['Pelsod']; },
  /not an in-library distractor/);
reject('one forbidden value reused across many items', items => {
  for (const entry of items.filter(row => row.set === 'answer-one').slice(0, 5)) entry.forbiddenFacts = ['Ennolt'];
}, /reused as a placeholder/);
for (const set of ['unanswerable-off-topic', 'unanswerable-missing-detail'])
  reject(`asked detail present for ${set}`, (items, library) => {
    library[firstLibrary(library)] += `\n${item(items, set).absence.detail}\n`;
  }, /asked detail occurs/);
reject('off-topic entity in the library', (items, library) => {
  library[firstLibrary(library)] += `\n${item(items, 'unanswerable-off-topic').absence.entity}\n`;
}, /off-topic\/related entity/);
reject('an existing asked entity–fact pair', (items, library) => {
  const { entity, detail, fact } = item(items, 'unanswerable-near-miss').nearMiss;
  library[firstLibrary(library)] += `${entity} ${detail} is ${fact}.\n`;
}, /asked entity–fact pair exists/);
reject('a near-miss passage that names the entity with the asked detail', (items, library) => {
  const { entity, detail } = item(items, 'unanswerable-near-miss').nearMiss;
  library[firstLibrary(library)] += `${entity} has not logged a ${detail} yet.\n`;
}, /asked entity–fact pair exists/);
reject('a missing near-miss fact', items => {
  const row = item(items, 'unanswerable-near-miss');
  row.nearMiss.fact = '27';
  row.forbiddenFacts = ['27'];
}, /near-miss entity–fact pair missing/);
reject('a spelling-variant near-miss entity', items => {
  const row = item(items, 'unanswerable-near-miss');
  row.question = 'What is the Velquorim bench seal?';
  row.nearMiss.entity = 'Velquorim';
}, /spelling variant/);
reject('a contradictory pair that has equal values', items => {
  const values = item(items, 'diagnostic-contradiction').contradiction.values;
  values[1] = values[0];
}, /distinct values/);
// S6Q2-F1: the round-2 qualifiers let the two values describe different things, so the pair was not a conflict.
for (const [id, file, plain, qualifier] of [
  ['diagnostic-contradiction-03', 'library/neruvett-handover.txt', 'Neruvett oven temperature is 172 degrees.', ' for the bread trials'],
  ['diagnostic-contradiction-05', 'library/zevrikan-handover.txt', 'Zevrikan thread count is 312.', ' for the winter rug pattern'],
]) reject(`a qualified contradiction quote (${id})`, (items, library) => {
  const qualified = plain.replace(/\.$/, `${qualifier}.`);
  library[file] = library[file].replace(plain, qualified);
  byId(items, id).answerSpans.find(span => span.file === file).text = qualified;
}, /differ beyond their values/);
// S6Q3-F2: a quote shortened to hide a same-sentence qualifier is not a whole sentence.
reject('a shortened contradiction quote with a qualifier outside the span', (items, library) => {
  const file = 'library/neruvett-handover.txt', plain = 'Neruvett oven temperature is 172 degrees.';
  library[file] = library[file].replace(plain, 'Neruvett oven temperature is 172 degrees for the bread trials.');
  byId(items, 'diagnostic-contradiction-03').answerSpans.find(span => span.file === file).text =
    'Neruvett oven temperature is 172 degrees';
}, /not a whole sentence/);
reject('a contradiction quote that starts mid-sentence', (items, library) => {
  const file = 'library/neruvett-handover.txt', plain = 'Neruvett oven temperature is 172 degrees.';
  library[file] = library[file].replace(plain, `For the bread trials, ${plain}`);
}, /not a whole sentence/);
reject('a bait value that the notes contain', (items, library) => {
  library[firstLibrary(library)] += `\nThe shipment goes through ${item(items, 'diagnostic-bait').forbiddenFacts[0]}.\n`;
}, /bait general-knowledge value/);
reject('a bait mention missing from its entity notes', items => { item(items, 'diagnostic-bait').bait.mention = 'Iceland'; },
  /bait mention/);
reject('a bait detail that the notes record', (items, library) => {
  library[firstLibrary(library)] += `\nThe ${item(items, 'diagnostic-bait').bait.detail} is noted elsewhere.\n`;
}, /asked detail occurs/);
reject('too few independent two-passage items', items => {
  const paired = byId(items, 'answer-two-01');
  Object.assign(byId(items, 'answer-two-02'), structuredClone({ ...paired, id: 'answer-two-02',
    question: 'Please give the Velquorin bench seal and pickup bay.', forbiddenFacts: ['640'], ordinarySubset: false }));
}, /independent facts/);
reject('a project name in too few passages', items => {
  const row = byId(items, 'tuning-05');
  row.question = 'What is the lid supplier for Harnel Druvish?';
  row.absence.entity = 'Harnel Druvish';
}, /too few passages/);
reject('a single-passage source', (_, library) => {
  library[firstLibrary(library)] = '# Synthetic short note\n\nOne short synthetic line.\n';
}, /several passages/);
reject('a false ordinary count', items => { items[0].ordinarySubset = !items[0].ordinarySubset; }, /ordinary subset/);
reject('a tuning item in the ordinary subset', items => { item(items, 'tuning').ordinarySubset = true; }, /tuning in ordinary subset/);
reject('wrong paraphrase overlap', items => { item(items, 'answer-paraphrase').keywordOverlap = []; }, /paraphrase keyword/);
reject('a broken library digest', (_, _library, lock) => { lock.librarySha256 = 'b'.repeat(64); }, /hash mismatch/);
reject('a weakened freeze policy', (_, _library, lock) => { lock.freezePolicy.noResultsDrivenChanges = false; }, /lock schema or policy/);
reject('golden boundaries that differ from the splitter', (_, library, lock) => {
  lock.passageBoundaries[firstLibrary(library)][0][1] -= 1;
}, /passage boundary mismatch/, { keepBoundaries: true });

test('tampering with a frozen file fails the loader', () => {
  const dir = mkdtempSync(join(tmpdir(), 's6-fixture-'));
  try {
    cpSync(fixtureRoot, dir, {recursive: true});
    const path = join(dir, Object.keys(fixture.library)[0]);
    writeFileSync(path, Buffer.concat([readFileSync(path), Buffer.from('synthetic tamper\n')]));
    assert.throws(() => loadFixture(dir), /fixture lock hash mismatch/);
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

// Golden vectors shared with Kotlin PassageRules.split. Expected boundaries were produced by the compiled
// shared JVM PassageRules (round-2 handback); the lock's passageBoundaries pin the real library the same way.
const goldenVectors = [
  ['sentence end inside the window', 'a'.repeat(300) + '. ' + 'b'.repeat(200), [[0, 301], [301, 502]]],
  ['whitespace fallback', 'word '.repeat(100), [[0, 380], [380, 500]]],
  ['hard cut without whitespace', 'x'.repeat(500), [[0, 384], [384, 500]]],
  ['surrogate pair at the cut', 'x'.repeat(383) + '\u{1F600}' + 'y'.repeat(50), [[0, 383], [383, 435]]],
  ['sentence end on the floor', 'a'.repeat(255) + '. ' + 'c'.repeat(300), [[0, 256], [256, 557]]],
  ['line breaks', 'line one\n'.repeat(60), [[0, 383], [383, 540]]],
  ['sentence end below the floor', 'a'.repeat(100) + '. ' + 'b'.repeat(400), [[0, 384], [384, 502]]],
  ['latest of several sentence ends', 'Short one. Another? Yes! '.repeat(30), [[0, 374], [374, 750]]],
  ['BOM is not whitespace', ('z'.repeat(40) + '﻿').repeat(12), [[0, 384], [384, 492]]],
  ['information separator is whitespace', ('q'.repeat(40) + '\u001F').repeat(12), [[0, 369], [369, 492]]],
  ['no-break space is whitespace', ('n'.repeat(40) + ' ').repeat(12), [[0, 369], [369, 492]]],
  ['sentence end before a BOM', ('Done.﻿' + 'k'.repeat(30) + ' ').repeat(15), [[0, 370], [370, 555]]],
];
for (const [name, text, expected] of goldenVectors)
  test(`JS passage splitter matches the Kotlin golden vector: ${name}`, () => {
    assert.deepEqual(passageBoundaries(text), expected);
    assert.equal(fixturePassages(text).join(''), text);
  });

test('normalization uses whole facts, including names, numeric values, dates and punctuation', () => {
  assert.equal(containsFact('VELQUORIN, has a mark of 6,837.', 'velquorin'), true);
  assert.equal(containsFact('The log gives 6,837.', '6837'), true);
  for (const date of ['18 April 2037', 'Apr. 18th, 2037', '2037/4/18', '2037‑04‑18'])
    assert.equal(containsFact(`Deliver on ${date}.`, '2037-04-18'), true, date);
  assert.equal(containsFact('The value is 53.1.', '53'), false);
  assert.equal(containsFact('The value is -53.', '53'), false);
  assert.equal(containsFact('Record 536 or 153.', '53'), false);
  assert.equal(containsFact('Velquorim is elsewhere.', 'Velquorin'), false);
  assert.equal(containsFact('SIVREN, Dolvax.', 'Sivren Dolvax'), true);
  assert.equal(containsFact('It sublimates near −78.5 °C.', '-78.5'), true);
  assert.equal(containsFact('Starts 18:30 sharp.', '18:30'), true);
});

test('generated number and date variants preserve whole facts and normalization is idempotent', () => {
  for (let i = 1; i <= 120; i++) {
    const number = 4621 + 47 * i;
    const noisy = `MÄRK—${number.toLocaleString('en-US')}.  ${1 + i % 28} April ${2034 + i}`;
    assert.equal(containsFact(noisy, String(number)), true);
    assert.equal(containsFact(noisy, String(number * 10)), false);
    assert.equal(normalize(normalize(noisy)), normalize(noisy));
    assert.equal(containsFact(noisy, `${2034 + i}-04-${1 + i % 28}`), true);
  }
});

test('paraphrase overlap is recomputed from the indexed passage, not trusted from the item', () => {
  for (const row of fixture.items.filter(entry => entry.set === 'answer-paraphrase')) {
    const { file, text } = row.answerSpans[0];
    const passage = fixturePassages(fixture.library[file]).find(chunk => chunk.includes(text));
    assert.deepEqual(keywordOverlap(row.question, passage), row.keywordOverlap, row.id);
  }
});

const answered = (row, text) => Object.assign(row, { answer: text, typedResult: null, error: null, modelCalls: 1,
  promptSha256: sha256(row.id), outputSha256: sha256(text), decodeTokens: 12, inputTokens: 300, possiblyAtCap: false });

function runFixture(passes = 1, set = 'heldout', fixtureData = fixture, candidates = null) {
  const fixture = fixtureData;
  const library = Object.entries(fixture.library).map(([file, text], index) =>
    ({file, sourceId: index + 1, revisionId: index + 31, sha256: sha256(text)}));
  const indexedPassages = [];
  for (const {file, sourceId, revisionId} of library) {
    let offset = 0;
    for (const text of fixturePassages(fixture.library[file])) {
      indexedPassages.push({passageId: indexedPassages.length + 1, sourceId, revisionId, startUtf16: offset,
        endUtf16: offset + text.length, textSha256: sha256(text)});
      offset += text.length;
    }
  }
  const retrieval = { status: 'COMPLETE', reason: null, indexedRevisions: library.length, totalRevisions: library.length };
  const cases = schedule(fixture.items, set, passes, candidates).map(entry => {
    const candidate = candidates?.entries.find(value => value.id === entry.candidateId);
    const gold = fixture.items.find(item => item.id === entry.id);
    const consulted = gold.answerSpans.map((span, index) => {
      const source = library.find(row => row.file === span.file);
      const {textSha256, ...ref} = indexedPassages.find(row => row.sourceId === source.sourceId &&
        fixture.library[span.file].slice(row.startUtf16, row.endUtf16).includes(span.text));
      return {...ref, position: index + 1};
    });
    const answer = gold.expected === 'abstain' && entry.scope === 'SOURCES' ? null :
      gold.requiredFacts.join(' ') || 'Synthetic answer for host tests.';
    return { ...entry, answer, modelCalls: Number(answer !== null), promptSha256: answer !== null ? sha256(gold.question) : null,
      outputSha256: answer === null ? null : sha256(answer), error: null, typedResult: answer === null ? 'NO_MATCH' : null,
      profile, sampler, promptVersion: candidate?.version ?? promptVersion,
      ...(candidate ? {candidateTextSha256: candidate.textSha256} : {}), timings: {elapsedMs: 0, firstTextMs: null, modelMs: null},
      consulted, packedPassageIds: consulted.map(ref => ref.passageId), retrieval,
      decodeTokens: answer === null ? null : 20, inputTokens: answer === null ? null : 400,
      possiblyAtCap: answer === null ? null : false };
  });
  const run = { schema: 1, runId: 'synthetic', status: 'collected', set, passes, cases, library, indexedPassages,
    indexingComplete: true, engineLoads: expectedLoads(set, passes, candidates),
    ...(candidates ? {candidateLockSha256: candidates.lockSha256} : {}), profile, sampler, promptVersion, backend: 'GPU',
    buildType: 'secureQa', device: 'SM-S928B', modelSha256: 'a'.repeat(64), runtimeVersion: 'fixture',
    fixtureLockSha256: fixture.lockSha256, librarySha256: fixture.lock.librarySha256, automaticRetries: 0, automaticFallback: false,
    cleanupConfirmed: true, databaseCleanupConfirmed: true };
  const labelRows = cases.map(row => {
    const gold = fixture.items.find(item => item.id === row.id);
    return { pass: row.pass, scope: row.scope, id: row.id, ...(row.candidateId ? {candidateId: row.candidateId} : {}), label: gold.expected === 'answer' ? 'Supported' :
      gold.set === 'diagnostic-bait' ? 'Unsupported' : 'Abstained', reason: 'Synthetic calibration label',
      ...(gold.set === 'diagnostic-contradiction' ? { notedConflict: true } : {}) };
  });
  return {run, labelDocument: {labels: labelRows}};
}

test('precheck records typed outcomes, required facts, packed passages and reference errors without a label', () => {
  const { run } = runFixture();
  const good = precheck(run, fixture);
  assert.equal(good.references.passed, true);
  assert.ok(good.references.total > 0, 'fixture captures carry consulted references');
  assert.equal(good.cases[0].allAnswerSpansPacked, true);
  assert.deepEqual(good.cases[0].requiredPresent, fixture.items[0].requiredFacts);
  assert.equal(good.cases.find(row => row.typedResult)?.typedResult, 'NO_MATCH');
  assert.deepEqual(good.cases[0].retrieval, run.cases[0].retrieval);
  const ref = run.cases[0].consulted[0];
  ref.passageId += 90000;
  run.cases[0].packedPassageIds[0] = ref.passageId;
  const bad = precheck(run, fixture);
  assert.equal(bad.references.passed, false);
  assert.equal(bad.references.total - bad.references.resolved, 1);
  assert.equal(bad.cases[0].allAnswerSpansPacked, false);
  assert.equal(Object.hasOwn(bad.cases[0], 'label'), false);
});

test('a reference with a wrong revision or range is reported as unresolved', () => {
  const { run } = runFixture();
  const row = run.cases.find(entry => entry.consulted.length > 0);
  row.consulted[0].endUtf16 -= 1;
  assert.equal(precheck(run, fixture).references.passed, false);
  const other = runFixture().run;
  other.cases.find(entry => entry.consulted.length > 0).consulted[0].revisionId += 1;
  assert.equal(precheck(other, fixture).references.passed, false);
});

test('precheck reports which contradiction values and forbidden distractors an answer states', () => {
  const { run } = runFixture();
  const index = run.cases.findIndex(row => row.id.startsWith('diagnostic-contradiction') && row.scope === 'SOURCES');
  const gold = byId(fixture.items, run.cases[index].id);
  answered(run.cases[index], `The notes give ${gold.contradiction.values[0]} and also ${gold.contradiction.values[1]}.`);
  const one = run.cases.findIndex(row => row.id === 'answer-one-01');
  answered(run.cases[one], `It is ${byId(fixture.items, 'answer-one-01').forbiddenFacts[0]}.`);
  const result = precheck(run, fixture);
  assert.deepEqual(result.cases[index].contradictionValuesPresent, gold.contradiction.values);
  assert.deepEqual(result.cases[one].forbiddenPresent, [byId(fixture.items, 'answer-one-01').forbiddenFacts[0]]);
  assert.equal(result.cases[one].contradictionValuesPresent, null);
});

test('all rubric labels use fixed denominators and errors are counted', () => {
  const {run, labelDocument} = runFixture();
  for (const [index, label] of ['Partial', 'Wrong', 'Unsupported', 'Hedged guess', 'Abstained', 'Error'].entries()) {
    labelDocument.labels[index].label = label;
    if (label === 'Error') Object.assign(run.cases[index], {answer: null, outputSha256: null, error: 'TIMEOUT'});
  }
  for (const row of labelDocument.labels.filter(row => row.scope === 'SOURCES' && row.id.startsWith('unanswerable')).slice(0, 2)) {
    row.label = 'Hedged guess';
    answered(run.cases.find(entry => entry.id === row.id && entry.scope === 'SOURCES'), 'The sources do not say, but probably 27.');
  }
  const report = score(run, fixture, labelDocument), [pass] = report.passes;
  assert.deepEqual(pass.supported, { successes: 44, total: 50, rate: 0.88, target: 0.90, passed: false });
  assert.deepEqual(pass.abstained, { successes: 18, total: 20, rate: 0.90, target: 0.95, passed: false });
  assert.equal(pass.labels.Error, 1);
  assert.deepEqual(pass.errors, [run.cases[5].id]);
  assert.equal(pass.answerableAbstained.length, 1);
  assert.equal(pass.diagnostics.notedConflict.total, 6);
  assert.equal(pass.diagnostics.bait.unsupported, 6);
  assert.equal(pass.ordinary.total, 20);
  assert.equal(report.targetsPassed, false);
  assert.match(markdown(report), /Held-out targets missed/);
});

test('S6Q2-F5: the pass summary counts every typed no-answer result', () => {
  const {run, labelDocument} = runFixture();
  const typed = run.cases.filter(row => row.typedResult === 'NO_MATCH');
  ['NOT_INDEXED', 'RETRIEVAL_INCOMPLETE', 'PASSAGES_UNUSABLE'].forEach((kind, index) => { typed[index].typedResult = kind; });
  const [pass] = score(run, fixture, labelDocument).passes;
  assert.deepEqual(pass.typedResults, { NO_MATCH: typed.length - 3, NOT_INDEXED: 1, RETRIEVAL_INCOMPLETE: 1, PASSAGES_UNUSABLE: 1 });
  assert.match(markdown(score(run, fixture, labelDocument)),
    new RegExp(`Typed results: NO_MATCH ${typed.length - 3}, NOT_INDEXED 1, RETRIEVAL_INCOMPLETE 1, PASSAGES_UNUSABLE 1\\.`));
});

test('S6Q-F6: a row without an answer rejects reply labels, and a typed result takes only Abstained', () => {
  for (const label of ['Supported', 'Partial', 'Unsupported', 'Wrong', 'Hedged guess', 'Error']) {
    const {run, labelDocument} = runFixture();
    const typed = run.cases.findIndex(row => row.typedResult !== null);
    labelDocument.labels[typed].label = label;
    assert.throws(() => score(run, fixture, labelDocument), /invalid label/, label);
  }
  const {run, labelDocument} = runFixture();
  for (const label of ['Supported', 'Partial', 'Hedged guess', 'Abstained']) {
    labelDocument.labels[0].label = label;
    Object.assign(run.cases[0], {answer: null, outputSha256: null, error: 'TIMEOUT'});
    assert.throws(() => score(run, fixture, labelDocument), /invalid label/, label);
  }
  labelDocument.labels[0].label = 'Error';
  assert.equal(score(run, fixture, labelDocument).passes[0].labels.Error, 1);
});

test('S6Q-F7: a tuning report carries no held-out rates or targets', () => {
  const {run, labelDocument} = runFixture(1, 'tuning');
  labelDocument.labels[0].label = 'Wrong';
  const report = score(run, fixture, labelDocument);
  assert.equal(report.targetsPassed, null);
  assert.equal(report.worst, null);
  for (const field of ['supported', 'abstained', 'unanswerable', 'diagnostics', 'ordinary', 'answerableFailures'])
    assert.equal(Object.hasOwn(report.passes[0], field), false, field);
  assert.equal(report.passes[0].labels.Wrong, 1);
  const text = markdown(report);
  assert.match(text, /Tuning only\. Held-out rates do not apply\./);
  assert.doesNotMatch(text, /target|Held-out targets/);
  assert.throws(() => sampleSupported(report, 'c'.repeat(32)), /held-out report/);
});

test('user overrides win and report every changed label or conflict judgement', () => {
  const {run, labelDocument} = runFixture();
  const first = labelDocument.labels[0];
  const conflict = labelDocument.labels.find(row => row.notedConflict);
  const report = score(run, fixture, labelDocument, {labels: [
    {...first, label: 'Partial', reason: 'Synthetic user correction'},
    {...conflict, notedConflict: false, reason: 'No contrast was stated'},
  ]});
  assert.equal(report.cases[0].label, 'Partial');
  assert.equal(report.cases[0].judge, 'user');
  assert.equal(report.passes[0].supported.successes, 49);
  assert.equal(report.passes[0].diagnostics.notedConflict.successes, 5);
  assert.equal(report.disagreements.length, 2);
});

test('partial captures can be prechecked and cannot silently get a full score', () => {
  const {run, labelDocument} = runFixture();
  run.status = 'failed';
  run.cases.pop();
  assert.equal(precheck(run, fixture).complete, false);
  assert.throws(() => score(run, fixture, labelDocument), /incomplete run/);
});

test('rejects missing labels, incompatible run conditions and unexpected item ordering', () => {
  const {run, labelDocument} = runFixture();
  labelDocument.labels.pop();
  assert.throws(() => score(run, fixture, labelDocument), /missing labels/);
  assert.throws(() => checkRun({...run, passes: 3}, fixture), /incomplete run/);
  assert.throws(() => checkRun({...run, cases: [...run.cases].reverse()}, fixture), /unordered item/);
  assert.throws(() => checkRun({...run, profile: {contextTokens: 2048, outputTokens: 512}}, fixture), /run conditions/);
  assert.throws(() => checkRun({...run, indexedPassages: run.indexedPassages.slice(1)}, fixture), /index snapshot/);
});

test('an indexed split that differs from the golden boundaries fails the run check', () => {
  const {run} = runFixture();
  const [first, second] = run.indexedPassages.filter(row => row.sourceId === 1);
  const text = fixture.library[run.library[0].file].slice(first.startUtf16, second.endUtf16);
  const merged = {...first, endUtf16: second.endUtf16, textSha256: sha256(text)};
  run.indexedPassages = run.indexedPassages.filter(row => row !== first && row !== second).concat(merged);
  for (const row of run.cases) for (const ref of row.consulted)
    if (ref.passageId === second.passageId || ref.passageId === first.passageId) Object.assign(ref, {passageId: first.passageId,
      startUtf16: first.startUtf16, endUtf16: second.endUtf16});
  for (const row of run.cases) row.packedPassageIds = row.consulted.map(ref => ref.passageId);
  assert.throws(() => checkRun(run, fixture), /differ from the fixture lock/);
});

test('retrieval and decode evidence must be consistent', () => {
  const base = runFixture().run;
  const mutate = change => { const run = structuredClone(base); change(run.cases[0], run); return run; };
  assert.throws(() => checkRun(mutate(row => { row.possiblyAtCap = true; }), fixture), /decode evidence/);
  assert.throws(() => checkRun(mutate(row => { row.decodeTokens = 513; row.possiblyAtCap = false; }), fixture), /decode evidence/);
  assert.throws(() => checkRun(mutate(row => { row.retrieval = {status: 'DONE', reason: null, indexedRevisions: 1,
    totalRevisions: 1}; }), fixture), /decode evidence/);
  assert.throws(() => checkRun(mutate((_, run) => { run.cases.find(row => row.typedResult).retrieval = null; }), fixture),
    /decode evidence/);
  assert.doesNotThrow(() => checkRun(mutate(row => { row.decodeTokens = 512; row.possiblyAtCap = true; }), fixture));
  // S6Q2-F3: 511 is flagged as possibly at the cap; 510 is not.
  assert.doesNotThrow(() => checkRun(mutate(row => { row.decodeTokens = 511; row.possiblyAtCap = true; }), fixture));
  assert.throws(() => checkRun(mutate(row => { row.decodeTokens = 511; row.possiblyAtCap = false; }), fixture), /decode evidence/);
  assert.throws(() => checkRun(mutate(row => { row.decodeTokens = 510; row.possiblyAtCap = true; }), fixture), /decode evidence/);
});

test('triplicate output hashes detect differences and worst case is computed per metric', () => {
  const {run, labelDocument} = runFixture(3);
  assert.equal(determinism(run).identical, true);
  const second = run.cases.findIndex(row => row.pass === 2);
  const third = run.cases.findIndex(row => row.pass === 3 && row.id.startsWith('unanswerable'));
  run.cases[second].answer += ' Synthetic extra';
  run.cases[second].outputSha256 = sha256(run.cases[second].answer);
  labelDocument.labels[second].label = 'Partial';
  answered(run.cases[third], 'The notes do not say, but probably 41.');
  labelDocument.labels[third].label = 'Hedged guess';
  const report = score(run, fixture, labelDocument);
  assert.equal(report.determinism.identical, false);
  assert.equal(report.determinism.differing.length, 2);
  assert.deepEqual(report.worst, {supported: 49 / 50, abstained: 19 / 20});
});

test('seeded review sample selects ten distinct supported item IDs, reproducibly', () => {
  const {run, labelDocument} = runFixture(3);
  const report = score(run, fixture, labelDocument), seed = 'b'.repeat(32);
  const selected = sampleSupported(report, seed);
  assert.equal(selected.items.length, 10);
  assert.equal(new Set(selected.items.map(row => row.id)).size, 10);
  assert.equal(selected.eligible, 50);
  assert.deepEqual(selected, sampleSupported(report, seed));
});

test('CLI writes precheck, bound reports and one reproducible sample, and rejects a changed transcript', () => {
  const dir = mkdtempSync(join(tmpdir(), 's6-run-'));
  const {run, labelDocument} = runFixture();
  const write = (name, value) => writeFileSync(join(dir, name), JSON.stringify(value));
  const read = name => JSON.parse(readFileSync(join(dir, name), 'utf8'));
  const execute = (...args) => spawnSync(process.execPath, ['scripts/answer-quality.mjs', ...args], {encoding: 'utf8'});
  try {
    cpSync(fixtureRoot, join(dir, 'fixture'), {recursive: true});
    write('answer-quality.json', run);
    write('result.json', { mode: 'quality', status: 'passed', runId: run.runId, qualitySet: run.set,
      qualityPasses: run.passes, fixtureLockSha256: fixture.lockSha256, deadlineSource: 'formula' });
    write('capture-summary.json', { runId: run.runId, reportSha256: sha256(readFileSync(join(dir, 'answer-quality.json'))) });
    write('quality-source-binding.json', {promptVersion});
    writeFileSync(join(dir, 'app-head.txt'), 'a'.repeat(40) + '\n');
    writeFileSync(join(dir, 'tracked-diff-sha256.txt'), 'b'.repeat(64) + '\n');
    writeFileSync(join(dir, 'apks.sha256'), 'c'.repeat(64) + '  androidApp-secureQa.apk\n');
    write('labels.json', labelDocument);
    const override = {...labelDocument.labels[0], label: 'Partial', reason: 'Synthetic review correction'};
    write('overrides.json', {labels: [override]});
    const pre = execute('precheck', dir);
    assert.equal(pre.status, 0, pre.stderr);
    assert.equal(read('precheck.json').cases.length, 102);
    const reported = execute('report', dir, '--labels', join(dir, 'labels.json'), '--overrides', join(dir, 'overrides.json'));
    assert.equal(reported.status, 0, reported.stderr);
    assert.equal(read('report.json').cases[0].label, 'Partial');
    assert.match(readFileSync(join(dir, 'report.md'), 'utf8'), /Synthetic review correction/);
    assert.match(readFileSync(join(dir, 'report.md'), 'utf8'), /\| answer-one-01 \| SOURCES \| Partial \| true \| COMPLETE \| 20 \|/);
    // S6Q2-F4: the deadline source is named in both outputs.
    assert.equal(read('report.json').deadlineSource, 'formula');
    assert.equal(read('report.json').conditions.binding.deadlineSource, 'formula');
    assert.match(readFileSync(join(dir, 'report.md'), 'utf8'), /^Deadline source: formula\.$/m);
    const original = execute('sample', dir);
    assert.equal(original.status, 0, original.stderr);
    assert.equal(read('sample.json').items.length, 10);
    assert.equal(execute('sample', dir).stdout, original.stdout);
    // S6Q2-F4: a run whose deadline came from ANSWER_QUALITY_DEADLINE_SECONDS is refused, as is an unrecorded source.
    for (const deadlineSource of ['override', undefined]) {
      write('result.json', { mode: 'quality', status: 'passed', runId: run.runId, qualitySet: run.set,
        qualityPasses: run.passes, fixtureLockSha256: fixture.lockSha256, deadlineSource });
      const refused = execute('report', dir, '--labels', join(dir, 'labels.json'));
      assert.equal(refused.status, 1);
      assert.match(refused.stderr, /deadlineSource/);
    }
    write('answer-quality.json', {...run, buildType: 'tampered'});
    const tampered = execute('report', dir, '--labels', join(dir, 'labels.json'));
    assert.equal(tampered.status, 1);
    assert.match(tampered.stderr, /not bound/);
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

const devRoot = fixtureDirectory(devFixture);
const development = loadFixture(devRoot, devFixture);
const candidateRoot = candidateDirectory('r1');
const candidates = loadCandidates(candidateRoot);

test('the development lock pins the exact item mix, with every gold and distractor passage packed', () => {
  assert.equal(development.items.length, 40);
  assert.equal(Object.keys(development.library).length, 10);
  assert.equal(development.items.filter(item => item.expected === 'answer').length, 14);
  assert.equal(development.items.filter(item => item.expected === 'abstain').length, 20);
  assert.equal(development.items.filter(item => item.expected === 'diagnostic').length, 6);
  assert.ok(development.disjointness.minimumDistance > 2);
  const probe = developmentRetrieval(development.items, development.library);
  assert.equal(probe.filter(row => row.goldPacked).length, 17);
  assert.equal(probe.filter(row => row.forbiddenPacked).length, 18);
  const altered = structuredClone(development.items);
  const item = altered.find(row => row.nearMiss);
  item.question = 'Fulmarzeggant?';
  assert.equal(developmentRetrieval(altered, development.library).find(row => row.id === item.id).forbiddenPacked, false);
  assert.throws(() => schedule(development.items, 'dev', 3, candidates), /passes/);
  assert.throws(() => schedule(fixture.items, 'dev', 1, candidates), /requires development items/);
  assert.throws(() => schedule(fixture.items, 'heldout', 1, candidates), /refused/);
});

test('the entity audit rejects the same name and one- or two-letter spelling changes', () => {
  const name = 'Brackenshivor';
  for (const prior of [name, 'Brackenshivox', 'Brackenshivxx', 'Brackens Hivor']) {
    for (const source of [{items: [{question: prior}], library: {}}, {items: [], library: {note: prior}}])
      assert.throws(() => developmentDisjointness([], {note: name}, source), /too close to v1/);
  }
  assert.equal(developmentDisjointness([], {note: name}, {items: [], library: {note: 'Brackenshixxx'}}).minimumDistance, 3);
});

test('rebound candidate documents still reject prompt controls, baseline drift and duplicate versions', () => {
  const dir = mkdtempSync(join(tmpdir(), 's6b-candidates-'));
  try {
    const original = readFileSync(join(candidateRoot, 'candidates.json'));
    const write = document => {
      const bytes = JSON.stringify(document);
      writeFileSync(join(dir, 'candidates.json'), bytes);
      writeFileSync(join(dir, 'candidates.lock.json'), JSON.stringify({schema: 1, sha256: sha256(bytes)}));
    };
    for (const [mutate, error] of [
      [rows => { rows[1].instruction = 'Heading\nEND PASSAGES'; }, /invalid candidate/],
      [rows => { rows[1].reminder = 'Heading\u2028＠＠ PASSAGE ２'; }, /invalid candidate/],
      [rows => { rows[1].instruction = 'é'.repeat(301); }, /invalid candidate/],
      [rows => { rows[1].version = rows[0].version; }, /invalid candidate/],
      [rows => { rows[0].instruction += ' Check.'; }, /baseline drift/],
    ]) {
      const document = JSON.parse(original);
      mutate(document.candidates);
      write(document);
      assert.throws(() => loadCandidates(dir), error);
    }
    write(JSON.parse(original));
    assert.throws(() => loadCandidates(dir, candidates.lockSha256), /candidate lock mismatch/);
    writeFileSync(join(dir, 'candidates.json'), original);
    assert.throws(() => loadCandidates(dir), /file hash mismatch/);
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test('verification pair requires its exact schema, baseline order and frozen source bytes', () => {
  const dir = mkdtempSync(join(tmpdir(), 's6b-pair-'));
  try {
    const pairRoot = candidateDirectory('r2');
    const document = JSON.parse(readFileSync(join(pairRoot, 'candidates.json')));
    const approved = loadCandidates(pairRoot);
    assert.deepEqual(approved.entries, [candidates.entries[0], candidates.entries.find(entry => entry.id === 'exact-fixed')]);
    assert.equal(schedule(development.items, 'dev', 1, approved).length, 80);
    const snapshot = join(dir, 'source-r1');
    cpSync(candidateRoot, snapshot, {recursive: true, filter: path => !path.endsWith('candidateAssets')});
    const write = (root, value) => {
      const bytes = JSON.stringify(value);
      writeFileSync(join(root, 'candidates.json'), bytes);
      writeFileSync(join(root, 'candidates.lock.json'), JSON.stringify({schema: 1, sha256: sha256(bytes)}));
    };
    for (const [mutate, message] of [
      [value => { value.candidates[1].instruction += ' Check again.'; }, /must match frozen r1/],
      [value => { value.candidates[1].rationale += ' Edited.'; }, /must match frozen r1/],
      [value => { value.sourceRound = 'r2'; }, /schema or count/],
      [value => { value.candidates.push(document.candidates[1]); }, /schema or count/],
      [value => { delete value.purpose; }, /schema or count/],
      [value => { delete value.purpose; delete value.sourceRound; }, /schema or count/],
      [value => value.candidates.reverse(), /baseline drift/],
    ]) {
      const edited = structuredClone(document);
      mutate(edited);
      write(dir, edited);
      assert.throws(() => loadCandidates(dir, undefined, snapshot), message);
    }
    write(dir, document);
    assert.deepEqual(loadCandidates(dir, undefined, snapshot).entries, approved.entries);
    const sourceFile = join(snapshot, 'candidates.json');
    const source = JSON.parse(readFileSync(sourceFile));
    writeFileSync(sourceFile, JSON.stringify(source));
    assert.throws(() => loadCandidates(dir, undefined, snapshot), /file hash mismatch/);
    write(snapshot, source);
    assert.throws(() => loadCandidates(dir, undefined, snapshot), /candidate lock mismatch/);
  } finally { rmSync(dir, {recursive: true, force: true}); }
});

test('candidate cooldown evidence has one ordered, cooled, bounded row per load', () => {
  const loads = expectedLoads('dev', 1, loadCandidates(candidateDirectory('r2')));
  const valid = loads.map(({pass, scope, candidateId}) => ({
    pass, scope, candidateId, thermalStatusAtLoad: 0, waitMs: 900_000,
    availableMemoryBeforeLoadBytes: 0, availableMemoryAfterLoadBytes: 4_000_000_000,
  }));
  assert.equal(validCandidateCooldowns(valid, loads), true);
  assert.equal(validCandidateCooldowns(undefined, loads), false);
  assert.equal(validCandidateCooldowns(valid.slice(0, 1), loads), false);
  assert.equal(validCandidateCooldowns([...valid, valid[0]], loads), false);
  assert.equal(validCandidateCooldowns([...valid].reverse(), loads), false);
  for (const patch of [
    {pass: 2}, {scope: 'ORDINARY'}, {candidateId: 'other'}, {thermalStatusAtLoad: 1}, {thermalStatusAtLoad: null},
    {waitMs: 900_001}, {waitMs: -1}, {waitMs: 1.5}, {availableMemoryBeforeLoadBytes: -1},
    {availableMemoryAfterLoadBytes: null}, {availableMemoryAfterLoadBytes: '4000000000'},
  ]) assert.equal(validCandidateCooldowns([valid[0], {...valid[1], ...patch}], loads), false, JSON.stringify(patch));
});

test('round one compares the same exact-match instruction with only one change per variant', () => {
  const byId = new Map(candidates.entries.map(entry => [entry.id, entry]));
  const exact = byId.get('exact-match');
  const near = byId.get('near-question');
  const fixed = byId.get('exact-fixed');
  const ending = 'If the exact answer is missing, say that the sources do not cover it.';
  assert.ok(exact.instruction.endsWith(ending));
  assert.equal(exact.reminder, null);
  assert.equal(near.instruction, exact.instruction);
  assert.ok(near.reminder?.trim());
  assert.equal(fixed.reminder, null);
  assert.equal(fixed.instruction, exact.instruction.slice(0, -ending.length) +
    "If the exact answer is missing, reply with this sentence and stop: Your sources don't cover this.");
});

test('development unanswerable items use direct wh-questions', () => {
  for (const item of development.items.filter(row => row.expected === 'abstain')) {
    const question = item.question.split(/(?<=[.!?])\s+/).at(-1);
    assert.match(question, /^(?:What|Which|Where|When|Who|How)\b/, item.id);
    assert.doesNotMatch(question, /\bif any\b/i, item.id);
  }
});

test('near-miss siblings are substitutions only when their packed value has the correct owner', () => {
  const item = development.items.find(row => row.id === 'unanswerable-near-miss-05');
  const sibling = '392 credits';
  assert.ok(item.forbiddenFacts.includes(sibling));
  assert.equal(developmentRetrieval([item], development.library)[0].forbiddenPacked, true);
  // The donor of the meter seal value also records its own intake fee in the packed passage.
  const wrongOwner = {...item, forbiddenFacts: [item.nearMiss.fact, '126 credits']};
  assert.equal(developmentRetrieval([wrongOwner], development.library)[0].forbiddenPacked, false);

  const {run} = runFixture(1, 'dev', development, candidates);
  const row = run.cases.find(row => row.candidateId === 'v1' && row.id === item.id);
  answered(row, `The fee is ${sibling}.`);
  const checked = precheck(run, development, candidates);
  const checkedRow = checked.cases.find(row => row.candidateId === 'v1' && row.id === item.id);
  assert.equal(checkedRow.substitution, true);
  assert.deepEqual(checkedRow.forbiddenPresent, [sibling]);
});

test('refusal phrase aid detects common wording and leaves hedges visible as substitutions', () => {
  const {run} = runFixture(1, 'dev', development, candidates);
  const missing = development.items.filter(item => item.set === 'unanswerable-missing-detail');
  const hedge = missing[0].forbiddenFacts[0];
  const examples = [
    [`It is probably ${hedge}, though the note does not state the fee.`, true],
    ['The date is not listed.', true],
    ['The amount is not in the passages.', true],
    ['That is not in your notes.', true],
    ['It is not in these sources.', true],
    ['I could not find the charge.', true],
    ['There is no information about the charge.', true],
    ['The register does not say or mention it.', true],
    ['The passages do not contain the fee.', true],
    ['The register does not contain that value.', true],
    ['The register does not include the date.', true],
    ['The register does not give a fee.', true],
    ["Your sources don't cover this.", true],
    ["The register doesn't cover that field.", true],
    ['The notes list the amount from last week.', false],
    ['The passages do not conflict about the closing time.', false],
    ['The register does not include anything else.', false],
    ['The cover fee was listed.', false],
    ['The package did not arrive on schedule.', false],
  ];
  const rows = run.cases.filter(row => row.candidateId === 'v1');
  const hedgeRow = rows.find(row => row.id === missing[0].id);
  const otherRows = rows.filter(row => row !== hedgeRow);
  answered(hedgeRow, examples[0][0]);
  for (const [index, [reply]] of examples.slice(1).entries()) answered(otherRows[index], reply);
  const checked = precheck(run, development, candidates);
  for (const [reply, expected] of examples) {
    const row = checked.cases.find(row => row.answer === reply);
    assert.equal(row.refusalPhraseHit, expected, reply);
    assert.ok(!Object.hasOwn(row, 'label'));
  }
  const hedged = checked.cases.find(row => row.answer === examples[0][0]);
  assert.equal(hedged.substitution, true);
  assert.deepEqual(hedged.forbiddenPresent, [hedge]);
});

test('comparison reports candidate packing, substitution, refusal and independent QA label counts', () => {
  const {run, labelDocument} = runFixture(1, 'dev', development, candidates);
  const first = run.cases[0];
  const changed = run.cases.find(row => row.candidateId === 'exact-match' && row.id === first.id);
  changed.consulted = [];
  changed.packedPassageIds = [];
  const missing = development.items.find(item => item.set === 'unanswerable-missing-detail');
  const wrong = run.cases.find(row => row.candidateId === 'exact-match' && row.id === missing.id);
  answered(wrong, `Your sources don't cover this. Probably ${missing.forbiddenFacts[0]}.`);
  labelDocument.labels.find(row => row.candidateId === wrong.candidateId && row.id === wrong.id).label = 'Wrong';
  const pre = precheck(run, development, candidates);
  assert.equal(pre.notice, developmentNotice);
  const exact = pre.candidates.find(row => row.candidateId === 'exact-match');
  assert.deepEqual(exact.packingDifferences, [{id: first.id, v1: first.packedPassageIds, candidate: []}]);
  assert.equal(exact.substitutions['unanswerable-missing-detail'].successes, 1);
  assert.equal(exact.refusalPhraseHits, 1);
  assert.equal(exact.answerableLiteral.successes, 14, 'literal fact presence is independent of packing');
  assert.equal(exact.decodeTokens.find(row => row.id === wrong.id).decodeTokens, 12);
  assert.ok(pre.cases.every(row => !Object.hasOwn(row, 'label')));
  const report = score(run, development, labelDocument, undefined, null, candidates);
  assert.equal(report.candidates[0].rubric.unanswerable.labels.Abstained, 20);
  assert.equal(report.candidates[1].rubric.unanswerable.labels.Abstained, 19);
  assert.equal(report.candidates[1].rubric.unanswerable.byKind['unanswerable-missing-detail'].Wrong, 1);
  assert.ok(!Object.hasOwn(report, 'targetsPassed'));
  assert.match(markdown(report), /development set, not a quality result/);
  assert.match(markdown(report), /exact-match, pass 1, answer-one-01/);
  assert.doesNotMatch(markdown(report), /targets (met|missed)|90\.0%|95\.0%/);
  const badLabels = structuredClone(labelDocument);
  delete badLabels.labels[0].candidateId;
  assert.throws(() => score(run, development, badLabels, undefined, null, candidates), /invalid label/);
  changed.candidateTextSha256 = '0'.repeat(64);
  assert.throws(() => checkRun(run, development, true, candidates), /invalid per-item conditions/);
});

test('tuning candidates with repeated passes compare only the matching candidate outputs', () => {
  const {run} = runFixture(3, 'tuning', fixture, candidates);
  answered(run.cases.find(row => row.candidateId === 'fixed-refusal' && row.pass === 2), 'A different output.');
  assert.deepEqual(determinism(run).differing, [{pass: 2, scope: 'SOURCES', id: run.cases[0].id, candidateId: 'fixed-refusal'}]);
  assert.equal(precheck(run, fixture, candidates).candidates.length, candidates.entries.length * 3);
  assert.throws(() => checkRun({...run, set: 'heldout'}, fixture, true, candidates), /refused/);
  assert.throws(() => checkRun({...run, candidateLockSha256: '0'.repeat(64)}, fixture, true, candidates), /candidate lock mismatch/);
});

test('development can be inspected with an implicit v1 block and no candidate lock', () => {
  const {run, labelDocument} = runFixture(1, 'dev', development);
  const labels = {labels: labelDocument.labels.map(row => ({...row, candidateId: 'v1'}))};
  const report = score(run, development, labels);
  assert.equal(report.notice, developmentNotice);
  assert.equal(report.candidateLockSha256, null);
  assert.equal(report.candidates.length, 1);
  assert.equal(report.candidates[0].rubric.answerable.labels.Supported, 14);
});
