import { test } from 'node:test';
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import {
  checkRun, containsFact, counts, determinism, distinctEntities, expectedLoads, fixturePassages, fixtureRoot,
  fixtureSummary, keywordOverlap, loadFixture, markdown, normalize, passageBoundaries, precheck, profile, promptVersion,
  sampleSupported, sampler, schedule, score, sha256, twoPassageKinds, validateFixture,
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

function runFixture(passes = 1, set = 'heldout') {
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
  const cases = schedule(fixture.items, set, passes).map(entry => {
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
      profile, sampler, promptVersion, timings: {elapsedMs: 0, firstTextMs: null, modelMs: null},
      consulted, packedPassageIds: consulted.map(ref => ref.passageId), retrieval,
      decodeTokens: answer === null ? null : 20, inputTokens: answer === null ? null : 400,
      possiblyAtCap: answer === null ? null : false };
  });
  const run = { schema: 1, runId: 'synthetic', status: 'collected', set, passes, cases, library, indexedPassages,
    indexingComplete: true, engineLoads: expectedLoads(set, passes), profile, sampler, promptVersion, backend: 'GPU',
    buildType: 'secureQa', device: 'SM-S928B', modelSha256: 'a'.repeat(64), runtimeVersion: 'fixture',
    fixtureLockSha256: fixture.lockSha256, librarySha256: fixture.lock.librarySha256, automaticRetries: 0, automaticFallback: false,
    cleanupConfirmed: true, databaseCleanupConfirmed: true };
  const labelRows = cases.map(row => {
    const gold = fixture.items.find(item => item.id === row.id);
    return { pass: row.pass, scope: row.scope, id: row.id, label: gold.expected === 'answer' ? 'Supported' :
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
