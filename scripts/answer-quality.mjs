// S6 fixture validation and manual-label reporting. No runtime or device commands.
import { createHash, randomBytes } from 'node:crypto';
import { lstatSync, readFileSync, readdirSync, writeFileSync, renameSync } from 'node:fs';
import { basename, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const fixtureRoot = fileURLToPath(new URL('../planning/fixtures/answer-quality-v1/', import.meta.url));
export const lockName = 'answer-quality-v1.lock.json';
export const counts = Object.freeze({
  'answer-one': 30, 'answer-two': 10, 'answer-paraphrase': 10,
  'unanswerable-off-topic': 8, 'unanswerable-missing-detail': 8, 'unanswerable-near-miss': 4,
  'diagnostic-contradiction': 6, 'diagnostic-bait': 6, tuning: 10,
});
export const profile = Object.freeze({ contextTokens: 4096, outputTokens: 512 });
// S6Q2-F3: whether the native count reaches 511 or 512 at the cap is unconfirmed, so flag both; heuristic until
// confirmed on the S24. The raw decodeTokens is always recorded alongside.
export const possiblyAtCapFrom = profile.outputTokens - 1;
export const sampler = Object.freeze({ topK: 1, topP: 1, temperature: 0, seed: 0 });
export const promptVersion = 'grounded-prompt-v1';
const answerable = ['answer-one', 'answer-two', 'answer-paraphrase'];
const unanswerable = ['unanswerable-off-topic', 'unanswerable-missing-detail', 'unanswerable-near-miss'];
const diagnostics = ['diagnostic-contradiction', 'diagnostic-bait'];
const labels = ['Supported', 'Partial', 'Unsupported', 'Wrong', 'Abstained', 'Hedged guess', 'Error'];
// Labels that judge a model reply; a row without an answer cannot carry them (S6Q-F6).
const answerLabels = ['Supported', 'Partial', 'Unsupported', 'Wrong', 'Hedged guess'];
const typedResults = ['NO_MATCH', 'NOT_INDEXED', 'RETRIEVAL_INCOMPLETE', 'PASSAGES_UNUSABLE'];
const retrievalStatuses = ['COMPLETE', 'INCOMPLETE', 'FAILED', 'SKIPPED'];
// A forbidden value may serve as a distractor for a few items, never as a shared placeholder (S6Q-F3).
const maxForbiddenReuse = 4;
const minIndependentTwoPassage = 5;
const stopwords = new Set('a an the what which who where when why how is are was were do does did can could would should i me my we our you your it its of for to in on at and with please tell about'.split(' '));
const sourceName = /^library\/[a-z0-9]+(?:-[a-z0-9]+)*\.txt$/;
const digest = /^[a-f0-9]{64}$/;
const decoder = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true });
const months = 'january february march april may june july august september october november december'.split(' ');
const monthNames = months.map(month => month.length > 3 ? `${month}|${month.slice(0, 3)}` : month).join('|');
const dayBeforeMonth = new RegExp(`\\b(\\d{1,2})(?:st|nd|rd|th)?[ -]+(${monthNames})[ ,.-]+(\\d{4})\\b`, 'g');
const monthBeforeDay = new RegExp(`\\b(${monthNames})[ .-]+(\\d{1,2})(?:st|nd|rd|th)?[ ,.-]+(\\d{4})\\b`, 'g');

function requireThat(condition, message) {
  if (!condition) throw new Error(message);
}

const object = value => value !== null && typeof value === 'object' && !Array.isArray(value);
const nonempty = value => typeof value === 'string' && value.trim().length > 0;
const positive = value => Number.isSafeInteger(value) && value > 0;
const natural = value => Number.isSafeInteger(value) && value >= 0;
const lex = (a, b) => a < b ? -1 : a > b ? 1 : 0;
export const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const json = file => JSON.parse(decoder.decode(readFileSync(file)));
const sorted = values => [...values].sort(lex);
const same = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const fields = (value, keys) => object(value) && same(sorted(Object.keys(value)), sorted(keys));

function regularFile(file) {
  requireThat(lstatSync(file).isFile(), `Expected a regular file at ${file}`);
  return readFileSync(file);
}

function dateToken(year, month, day) {
  return `${year}:${String(month).padStart(2, '0')}:${String(day).padStart(2, '0')}`;
}

export function normalize(value) {
  const monthNumber = abbreviation => months.findIndex(month => month.startsWith(abbreviation)) + 1;
  const normalized = value.normalize('NFKC').toLowerCase()
    .replace(/\b(\d{4})[-/‐‑‒–—](\d{1,2})[-/‐‑‒–—](\d{1,2})\b/g, (_, year, month, day) => dateToken(year, month, day))
    .replace(dayBeforeMonth, (_, day, month, year) => dateToken(year, monthNumber(month), day))
    .replace(monthBeforeDay, (_, month, day, year) => dateToken(year, monthNumber(month), day))
    .replace(/\b\d{1,3}(?:,\d{3})+\b/g, number => number.replaceAll(',', ''))
    .replace(/[‐‑‒–—]/g, ' ').replaceAll('−', '-').replace(/[’']/g, '');
  return (normalized.match(/[\p{L}\p{M}][\p{L}\p{M}\p{N}]*|[+-]?\d+(?:[.:]\d+)*/gu) ?? []).join(' ');
}

export function containsFact(text, fact) {
  const normalized = normalize(fact);
  return normalized !== '' && ` ${normalize(text)} `.includes(` ${normalized} `);
}

function terms(text) {
  return new Set((text.toLowerCase().match(/[\p{L}\p{N}]+/gu) ?? []).filter(word => !stopwords.has(word)));
}

// Fixture validation only: match the v1 passage boundaries to check literal overlap and co-location.
// Run evidence comes from the actual indexed SQLite rows, independently of this helper.
// Kotlin/JVM Char.isWhitespace: Character.isWhitespace or isSpaceChar. Unlike JS \s it includes U+001C..U+001F
// and excludes U+FEFF. Pinned by the golden vectors in the tests and by the lock's passage boundaries.
const kotlinWhitespace = /^[\p{Zs}\p{Zl}\p{Zp}\t\n\u000B\f\r\u001C-\u001F]$/u;
const isHighSurrogate = char => char !== undefined && char.charCodeAt(0) >= 0xD800 && char.charCodeAt(0) <= 0xDBFF;

export function fixturePassages(text) {
  const result = [];
  for (let start = 0; start < text.length;) {
    let end = Math.min(start + 384, text.length);
    if (end < text.length) {
      if (isHighSurrogate(text[end - 1])) end--;
      let space;
      let sentence;
      for (let at = end; at >= start + 256; at--) {
        const prior = text[at - 1];
        if (space === undefined && kotlinWhitespace.test(prior)) space = at;
        if ('.!?\n'.includes(prior) && kotlinWhitespace.test(text[at])) { sentence = at; break; }
      }
      end = sentence ?? space ?? end;
    }
    result.push(text.slice(start, end));
    start = end;
  }
  return result;
}

export function keywordOverlap(question, passage) {
  const passageTerms = terms(passage);
  return sorted([...terms(question)].filter(word => passageTerms.has(word)));
}

export function passageBoundaries(text) {
  let start = 0;
  return fixturePassages(text).map(chunk => [start, start += chunk.length]);
}

function editDistance(a, b) {
  let previous = Array.from({ length: b.length + 1 }, (_, index) => index);
  for (let i = 1; i <= a.length; i++) {
    const current = [i];
    for (let j = 1; j <= b.length; j++)
      current[j] = Math.min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    previous = current;
  }
  return previous[b.length];
}

// Distinct entities, not spelling variants of each other (S6Q-F2).
export function distinctEntities(a, b) {
  const [x, y] = [normalize(a), normalize(b)];
  return x !== '' && y !== '' && !` ${x} `.includes(` ${y} `) && !` ${y} `.includes(` ${x} `) &&
    editDistance(x, y) >= Math.max(3, Math.ceil(Math.min(x.length, y.length) / 2));
}

export function libraryHash(hashes) {
  return sha256(sorted(Object.keys(hashes).filter(name => sourceName.test(name)))
    .map(name => `${hashes[name]}  ${name}\n`).join(''));
}

const factList = values => Array.isArray(values) && values.every(value => nonempty(value) && normalize(value)) &&
  new Set(values.map(normalize)).size === values.length;

function validateItem(item, library, isDuplicate) {
  const check = (pass, why) => requireThat(pass, `${item?.id ?? 'item'}: ${why}`);
  check(object(item) && Object.hasOwn(counts, item.set) && typeof item.id === 'string' &&
    new RegExp(`^${item.set}-[0-9]{2}$`).test(item.id) && !isDuplicate(item), 'invalid or duplicate ID/question/set');
  const extra = item.set === 'answer-paraphrase' ? 'keywordOverlap'
    : item.set === 'unanswerable-near-miss' ? 'nearMiss'
    : item.set === 'diagnostic-contradiction' ? 'contradiction'
    : item.set === 'diagnostic-bait' ? 'bait'
    : ['unanswerable-off-topic', 'unanswerable-missing-detail'].includes(item.set) ||
      (item.set === 'tuning' && item.expected === 'abstain') ? 'absence' : null;
  check(fields(item, ['id', 'set', 'question', 'requiredFacts', 'forbiddenFacts', 'answerSpans', 'expected',
    'ordinarySubset', ...(extra ? [extra] : [])]), 'item schema');
  check(nonempty(item.question) && item.question.length <= 1024 && !/[\r\n]/.test(item.question) && factList(item.requiredFacts) &&
    factList(item.forbiddenFacts) && typeof item.ordinarySubset === 'boolean' && Array.isArray(item.answerSpans), 'item schema');
  const expected = answerable.includes(item.set) ? ['answer'] : unanswerable.includes(item.set) ? ['abstain']
    : diagnostics.includes(item.set) ? ['diagnostic'] : ['answer', 'abstain'];
  check(expected.includes(item.expected) && (item.set !== 'tuning' || !item.ordinarySubset), 'expected kind or tuning in ordinary subset');
  check(item.expected === 'answer' ? item.requiredFacts.length > 0 : item.requiredFacts.length === 0, 'required fact count');
  const spanCount = ['answer-two', 'diagnostic-contradiction'].includes(item.set) ? 2 : item.expected === 'answer' ? 1 : 0;
  check(item.answerSpans.length === spanCount, 'answer span count');
  const locations = item.answerSpans.map(span => {
    check(fields(span, ['file', 'text']) && typeof span.file === 'string' && Object.hasOwn(library, span.file) &&
      nonempty(span.text), 'answer span schema');
    const text = library[span.file];
    const start = text.indexOf(span.text);
    check(start >= 0 && start === text.lastIndexOf(span.text), 'answer span does not uniquely resolve');
    const chunks = fixturePassages(text);
    const index = chunks.findIndex(chunk => chunk.includes(span.text));
    check(index >= 0, 'answer span crosses an indexed passage boundary');
    const at = chunks[index]?.indexOf(span.text) ?? -1;
    return { key: `${span.file}:${index}`, passage: chunks[index], quote: span.text, at };
  });
  check(new Set(locations.map(location => location.key)).size === spanCount, 'answer requires distinct passages');
  check(item.requiredFacts.every(fact => locations.some(({quote}) => containsFact(quote, fact))), 'required fact missing from answer spans');
  check(!item.forbiddenFacts.some(fact => locations.some(({quote}) => containsFact(quote, fact))), 'forbidden fact in answer spans');
  if (item.set === 'answer-two')
    check(locations.every(target => item.requiredFacts.some(fact => containsFact(target.quote, fact) &&
      locations.filter(location => location !== target).every(({passage}) => !containsFact(passage, fact)))),
    'two-passage item has no uniquely required fact in each passage');

  const texts = Object.values(library);
  const passages = texts.flatMap(fixturePassages);
  const inLibrary = fact => texts.some(text => containsFact(text, fact));
  if (item.set === 'diagnostic-bait')
    check(item.forbiddenFacts.length > 0 && !item.forbiddenFacts.some(inLibrary),
      'bait general-knowledge value missing or present in the library');
  else check(item.forbiddenFacts.every(inLibrary), 'forbidden fact is not an in-library distractor');
  if (extra === 'keywordOverlap') {
    const overlap = keywordOverlap(item.question, locations[0].passage);
    check(overlap.length <= 2 && same(item.keywordOverlap, overlap), 'incorrect or excessive paraphrase keyword overlap');
  } else if (extra === 'absence') {
    const { entity, detail } = item.absence ?? {};
    check(fields(item.absence, ['entity', 'detail']) && nonempty(entity) && nonempty(detail) &&
      containsFact(item.question, entity) && containsFact(item.question, detail), 'invalid absent-detail metadata');
    check(!texts.some(text => containsFact(text, detail)), 'asked detail occurs in the library');
    check(texts.some(text => containsFact(text, entity)) === (item.set !== 'unanswerable-off-topic'),
      'off-topic/related entity mismatch');
  } else if (extra === 'nearMiss') {
    const { entity, nearEntity, detail, fact } = item.nearMiss ?? {};
    check(fields(item.nearMiss, ['entity', 'nearEntity', 'detail', 'fact']) &&
      [entity, nearEntity, detail, fact].every(nonempty) && normalize(entity) !== normalize(nearEntity) &&
      containsFact(item.question, entity) && containsFact(item.question, detail) && !containsFact(item.question, nearEntity) &&
      item.forbiddenFacts.some(value => normalize(value) === normalize(fact)), 'invalid near-miss metadata');
    check(distinctEntities(entity, nearEntity) && inLibrary(entity), 'near-miss entity is a spelling variant or not in the library');
    // No indexed passage records the asked detail for the asked entity, with or without the other entity's value.
    check(!passages.some(passage => containsFact(passage, entity) && containsFact(passage, detail)), 'asked entity–fact pair exists');
    check(texts.some(text => text.split('\n').some(line => [nearEntity, detail, fact].every(value => containsFact(line, value)))),
      'near-miss entity–fact pair missing');
  } else if (extra === 'bait') {
    const { entity, detail, mention } = item.bait ?? {};
    check(fields(item.bait, ['entity', 'detail', 'mention']) && [entity, detail, mention].every(nonempty) &&
      containsFact(item.question, entity) && containsFact(item.question, detail), 'invalid bait metadata');
    check(!texts.some(text => containsFact(text, detail)), 'asked detail occurs in the library');
    check(passages.some(passage => containsFact(passage, entity) && containsFact(passage, mention)),
      'bait mention is not recorded with its entity');
  } else if (extra === 'contradiction') {
    const { entity, detail, values } = item.contradiction ?? {};
    check(fields(item.contradiction, ['entity', 'detail', 'values']) && nonempty(entity) && nonempty(detail) &&
      containsFact(item.question, entity) && containsFact(item.question, detail) &&
      factList(values) && values.length === 2 && item.answerSpans[0].file !== item.answerSpans[1].file &&
      locations.every(({quote}, index) => [entity, detail, values[index]].every(value => containsFact(quote, value)) &&
        !containsFact(quote, values[1 - index])), 'contradiction does not contain distinct values for the same detail');
    // S6Q2-F1: with its value removed, each quote must read identically, so no qualifier can reconcile the pair.
    const frame = (quote, value) => ` ${normalize(quote)} `.replace(` ${normalize(value)} `, ' ').trim();
    check(factList(values) && locations.length === 2 &&
      frame(locations[0].quote, values[0]) === frame(locations[1].quote, values[1]),
      'contradiction quotes differ beyond their values');
    // S6Q3-F2: each quote is a whole sentence of its passage, so no same-sentence qualifier sits outside the span.
    // A sentence starts at the passage start or after a line break or a sentence end ([.?!] then whitespace), and
    // ends with [.?!] followed by whitespace or the passage end. A qualifier in the next sentence is left to review.
    const wholeSentence = ({passage, quote, at}) => {
      const before = passage.slice(0, at), after = passage.slice(at + quote.length);
      return at >= 0 && /[.?!]$/.test(quote) && (/^\s*$/.test(before) || /[.?!]\s+$/.test(before) || /\n\s*$/.test(before)) &&
        (after === '' || /^\s/.test(after));
    };
    check(locations.every(wholeSentence), 'contradiction quote is not a whole sentence');
  }
}

export function validateFixture(items, library, lock, actualHashes) {
  requireThat(fields(items, ['schema', 'items']) && items.schema === 1 && Array.isArray(items.items), 'items schema');
  requireThat(fields(lock, ['schema', 'fixture', 'files', 'librarySha256', 'passageBoundaries', 'counts',
    'ordinarySubsetCount', 'freezePolicy']) &&
    lock.schema === 1 && lock.fixture === 'answer-quality-v1' && object(lock.files) &&
    fields(lock.counts, Object.keys(counts)) && lock.ordinarySubsetCount === 20 &&
    fields(lock.freezePolicy, ['approval', 'noResultsDrivenChanges', 'tuningExcludedFromHeldout']) &&
    nonempty(lock.freezePolicy.approval) && lock.freezePolicy.noResultsDrivenChanges === true &&
    lock.freezePolicy.tuningExcludedFromHeldout === true, 'lock schema or policy');
  const names = Object.keys(library);
  requireThat(names.length >= 12 && names.length <= 20 && names.every(name => sourceName.test(name) &&
    /^# [^\r\n]+\n\n\S/u.test(library[name]) && !library[name].includes('\0') &&
    Buffer.byteLength(library[name]) <= 65536), 'library schema or size');
  requireThat(fields(lock.files, [...names, 'items.json']) && Object.entries(lock.files)
    .every(([name, hash]) => digest.test(hash) && hash === actualHashes[name]) &&
    lock.librarySha256 === libraryHash(actualHashes), 'fixture lock hash mismatch');
  // Golden passage boundaries: the JS splitter must reproduce them, and the device's indexed rows must match them.
  requireThat(fields(lock.passageBoundaries, names) &&
    names.every(name => same(lock.passageBoundaries[name], passageBoundaries(library[name]))), 'passage boundary mismatch');
  requireThat(names.every(name => lock.passageBoundaries[name].length >= 2), 'every source must span several passages');
  const seenIds = new Set(), seenQuestions = new Set();
  const seenCounts = Object.fromEntries(Object.keys(counts).map(set => [set, 0]));
  for (const item of items.items) {
    validateItem(item, library, value => seenIds.has(value.id) ||
      (typeof value.question === 'string' && seenQuestions.has(normalize(value.question))));
    seenIds.add(item.id);
    seenQuestions.add(normalize(item.question));
    seenCounts[item.set]++;
  }
  requireThat(Object.keys(counts).every(set => seenCounts[set] === counts[set] &&
    lock.counts[set] === counts[set]), 'incorrect counts per set');
  requireThat(items.items.filter(item => item.ordinarySubset).length === 20, 'ordinary subset must have exactly 20 items');
  const uses = new Map();
  for (const item of items.items) for (const fact of item.forbiddenFacts)
    uses.set(normalize(fact), (uses.get(normalize(fact)) ?? 0) + 1);
  requireThat([...uses.values()].every(count => count <= maxForbiddenReuse), 'forbidden fact reused as a placeholder');
  requireThat(twoPassageKinds(items.items).independent.length >= minIndependentTwoPassage,
    'too few two-passage items with independent facts');
  const passages = names.flatMap(name => fixturePassages(library[name]));
  requireThat(entityNames(items.items).every(entity => passages.filter(passage => containsFact(passage, entity)).length > 4),
    'a project name appears in too few passages');
}

// Project names: every scored project has a contradiction; tuning entities carry abstention metadata.
function entityNames(items) {
  return sorted(new Set(items.flatMap(item => [item.contradiction?.entity,
    item.set === 'tuning' ? item.absence?.entity : undefined]).filter(Boolean)));
}

// Independent two-passage items ask only facts that no one-passage item asks (S6Q-F10).
export function twoPassageKinds(items) {
  const single = new Set(items.filter(item => item.set === 'answer-one').flatMap(item => item.requiredFacts.map(normalize)));
  const two = items.filter(item => item.set === 'answer-two');
  const independent = two.filter(item => item.requiredFacts.every(fact => !single.has(normalize(fact)))).map(item => item.id);
  return { independent, paired: two.map(item => item.id).filter(id => !independent.includes(id)) };
}

export function fixtureSummary(fixture) {
  const names = Object.keys(fixture.library);
  const passages = names.flatMap(name => fixturePassages(fixture.library[name]));
  return {
    sources: Object.fromEntries(names.map(name => [name, { bytes: Buffer.byteLength(fixture.library[name]),
      passages: fixture.lock.passageBoundaries[name].length }])),
    libraryBytes: names.reduce((total, name) => total + Buffer.byteLength(fixture.library[name]), 0),
    passages: passages.length,
    passagesPerEntity: Object.fromEntries(entityNames(fixture.items).map(entity =>
      [entity, passages.filter(passage => containsFact(passage, entity)).length])),
    paraphraseOverlap: Object.fromEntries(fixture.items.filter(item => item.set === 'answer-paraphrase')
      .map(item => [item.id, item.keywordOverlap.length])),
    twoPassage: twoPassageKinds(fixture.items),
  };
}

export function loadFixture(directory = fixtureRoot) {
  requireThat(lstatSync(join(directory, 'library')).isDirectory(), 'library must be a directory');
  requireThat(same(sorted(readdirSync(directory)), [lockName, 'items.json', 'library'].sort(lex)), 'unexpected fixture files');
  const lib = Object.fromEntries(sorted(readdirSync(join(directory, 'library'))).map(name => {
    const path = `library/${name}`;
    requireThat(sourceName.test(path), 'invalid library filename');
    return [path, regularFile(join(directory, path))];
  }));
  const lockBytes = regularFile(join(directory, lockName)), itemsBytes = regularFile(join(directory, 'items.json'));
  const lock = JSON.parse(decoder.decode(lockBytes)), items = JSON.parse(decoder.decode(itemsBytes));
  const library = Object.fromEntries(Object.entries(lib).map(([file, bytes]) => [file, decoder.decode(bytes)]));
  validateFixture(items, library, lock, Object.fromEntries(Object.entries({...lib, 'items.json': itemsBytes})
    .map(([file, bytes]) => [file, sha256(bytes)])));
  return { items: items.items, library, lock, lockSha256: sha256(lockBytes) };
}

const key = ({pass, scope, id}) => `${pass}/${scope}/${id}`;
export function schedule(items, set, passes) {
  requireThat(['tuning', 'heldout'].includes(set) && [1, 3].includes(passes), 'invalid run set or passes');
  const primary = items.filter(item => (item.set === 'tuning') === (set === 'tuning'));
  const ordinary = set === 'heldout' ? primary.filter(item => item.ordinarySubset) : [];
  return Array.from({ length: passes }, (_, pass) => [...primary.map(item => ({ pass: pass + 1, scope: 'SOURCES', id: item.id })),
    ...ordinary.map(item => ({ pass: pass + 1, scope: 'ORDINARY', id: item.id }))]).flat();
}

export function expectedLoads(set, passes) {
  const scopes = set === 'heldout' ? ['SOURCES', 'ORDINARY'] : ['SOURCES'];
  return Array.from({length: passes}, (_, pass) => scopes.map(scope => ({pass: pass + 1, scope, count: 1}))).flat();
}

function resolveLibrary(run, fixture) {
  const bySource = new Map(), byPassage = new Map(), seenFiles = new Set(), seenRevisions = new Set();
  requireThat(Array.isArray(run.library) && Array.isArray(run.indexedPassages), 'missing library snapshot');
  for (const source of run.library) {
    requireThat(object(source) && Object.hasOwn(fixture.library, source.file) && !seenFiles.has(source.file) &&
      positive(source.sourceId) && !bySource.has(source.sourceId) && positive(source.revisionId) &&
      !seenRevisions.has(source.revisionId) && source.sha256 === fixture.lock.files[source.file], 'invalid library source snapshot');
    bySource.set(source.sourceId, source);
    seenFiles.add(source.file);
    seenRevisions.add(source.revisionId);
  }
  for (const entry of run.indexedPassages) {
    requireThat(object(entry) && positive(entry.passageId) && !byPassage.has(entry.passageId), 'invalid or duplicate indexed passage');
    byPassage.set(entry.passageId, entry);
  }
  return { bySource, byPassage };
}

function resolveReference(ref, snapshot, library) {
  const source = snapshot.bySource.get(ref.sourceId), row = snapshot.byPassage.get(ref.passageId);
  if (!source || !row || source.revisionId !== ref.revisionId ||
      ['sourceId', 'revisionId', 'startUtf16', 'endUtf16'].some(field => row[field] !== ref[field])) return null;
  const text = library[source.file];
  if (!natural(ref.startUtf16) || !positive(ref.endUtf16) || ref.startUtf16 >= ref.endUtf16 || ref.endUtf16 > text.length) return null;
  const span = text.slice(ref.startUtf16, ref.endUtf16);
  if (!span.isWellFormed() || sha256(span) !== row.textSha256) return null;
  return { file: source.file, startUtf16: ref.startUtf16, endUtf16: ref.endUtf16, text: span };
}

function checkIndexSnapshot(snapshot, library, boundaries) {
  const partitions = new Map([...snapshot.bySource.keys()].map(id => [id, []]));
  for (const passage of snapshot.byPassage.values()) {
    requireThat(resolveReference(passage, snapshot, library) !== null, 'index snapshot contains an invalid passage');
    partitions.get(passage.sourceId).push(passage);
  }
  for (const [id, passages] of partitions) {
    const source = snapshot.bySource.get(id);
    let end = 0;
    for (const passage of passages.sort((a, b) => a.startUtf16 - b.startUtf16)) {
      requireThat(passage.startUtf16 === end, 'index snapshot has a gap or overlap');
      end = passage.endUtf16;
    }
    requireThat(end === library[source.file].length, 'index snapshot does not cover the revision');
    // The device's PassageRules split must reproduce the lock's golden boundaries (S6Q-F7).
    requireThat(same(passages.map(passage => [passage.startUtf16, passage.endUtf16]), boundaries[source.file]),
      'index snapshot passage boundaries differ from the fixture lock');
  }
}

function validRetrieval(value) {
  return value === null || (fields(value, ['status', 'reason', 'indexedRevisions', 'totalRevisions']) &&
    retrievalStatuses.includes(value.status) && (value.reason === null || /^[A-Z_]{1,40}$/.test(value.reason)) &&
    natural(value.indexedRevisions) && natural(value.totalRevisions) && value.indexedRevisions <= value.totalRevisions);
}

function sameConfiguration(actual, expected) {
  return fields(actual, Object.keys(expected)) && Object.entries(expected).every(([field, value]) => actual[field] === value);
}

export function checkRun(run, fixture, complete = true) {
  requireThat(object(run) && run.schema === 1 && typeof run.runId === 'string' && /^[A-Za-z0-9_-]{1,80}$/.test(run.runId) &&
    ['running', 'failed', 'collected'].includes(run.status), 'invalid run report');
  requireThat(run.fixtureLockSha256 === fixture.lockSha256 && run.librarySha256 === fixture.lock.librarySha256,
    'fixture hash mismatch');
  requireThat(run.promptVersion === promptVersion && sameConfiguration(run.profile, profile) && sameConfiguration(run.sampler, sampler) &&
    run.buildType === 'secureQa' && run.device === 'SM-S928B' && run.backend === 'GPU' && run.automaticRetries === 0 &&
    run.automaticFallback === false &&
    typeof run.modelSha256 === 'string' && digest.test(run.modelSha256) && nonempty(run.runtimeVersion), 'invalid run conditions');
  const expected = schedule(fixture.items, run.set, run.passes);
  requireThat(Array.isArray(run.cases) && run.cases.length <= expected.length, 'invalid item count');
  if (complete) requireThat(run.status === 'collected' && run.cleanupConfirmed === true && run.databaseCleanupConfirmed === true &&
    run.cases.length === expected.length && Array.isArray(run.library) &&
    run.library.length === Object.keys(fixture.library).length && run.indexingComplete === true &&
    Array.isArray(run.engineLoads) && run.engineLoads.length === expectedLoads(run.set, run.passes).length &&
    expectedLoads(run.set, run.passes).every((load, index) => sameConfiguration(run.engineLoads[index], load)),
  'incomplete run or cleanup');
  const snapshot = resolveLibrary(run, fixture);
  if (complete) checkIndexSnapshot(snapshot, fixture.library, fixture.lock.passageBoundaries);
  for (const [index, row] of run.cases.entries()) {
    requireThat(object(row) && key(row) === key(expected[index]), 'missing, unexpected or unordered item');
    requireThat(row.promptVersion === run.promptVersion && sameConfiguration(row.profile, profile) &&
      sameConfiguration(row.sampler, sampler), 'invalid per-item conditions');
    requireThat((row.answer === null || nonempty(row.answer)) && (row.error === null || nonempty(row.error)) &&
      (row.typedResult === null || typedResults.includes(row.typedResult)) &&
      [0, 1].includes(row.modelCalls) && Array.isArray(row.consulted) && row.consulted.length <= 4 &&
      row.consulted.every((ref, position) => object(ref) && positive(ref.sourceId) && positive(ref.revisionId) &&
        positive(ref.passageId) && natural(ref.startUtf16) && positive(ref.endUtf16) &&
        ref.position === position + 1) && new Set(row.consulted.map(ref => ref.passageId)).size === row.consulted.length &&
      same(row.packedPassageIds, row.consulted.map(ref => ref.passageId)) &&
      (row.modelCalls === 1 ? typeof row.promptSha256 === 'string' && digest.test(row.promptSha256)
        : row.promptSha256 === null && row.consulted.length === 0) &&
      row.outputSha256 === (row.answer === null ? null : sha256(row.answer)), 'invalid item evidence');
    requireThat([row.answer, row.error, row.typedResult].filter(value => value !== null).length === 1 &&
      (row.answer === null || row.modelCalls === 1) &&
      (row.typedResult === null || (row.modelCalls === 0 && row.scope === 'SOURCES')), 'inconsistent item outcome');
    requireThat(fields(row.timings, ['elapsedMs', 'firstTextMs', 'modelMs']) && natural(row.timings.elapsedMs) &&
      [row.timings.firstTextMs, row.timings.modelMs].every(time => time === null || (natural(time) && time <= row.timings.elapsedMs)),
    'invalid item timings');
    // Retrieval summary and native decode counts (S6Q-F5). A typed result always follows a recorded retrieval.
    requireThat(validRetrieval(row.retrieval) && (row.typedResult === null || row.retrieval !== null) &&
      [row.decodeTokens, row.inputTokens].every(count => count === null || natural(count)) &&
      (row.modelCalls === 1 || (row.decodeTokens === null && row.inputTokens === null)) &&
      (row.decodeTokens === null ? row.possiblyAtCap === null :
        row.possiblyAtCap === (row.decodeTokens >= possiblyAtCapFrom)),
    'invalid retrieval or decode evidence');
  }
  return snapshot;
}

export function precheck(run, fixture) {
  const snapshot = checkRun(run, fixture, false);
  const items = new Map(fixture.items.map(item => [item.id, item]));
  const cases = run.cases.map(row => {
    const item = items.get(row.id);
    const resolved = row.consulted.map(ref => resolveReference(ref, snapshot, fixture.library));
    const packedSpans = item.answerSpans.map(span => resolved.some(entry => entry?.file === span.file && entry.text.includes(span.text)));
    return {
      pass: row.pass, id: row.id, scope: row.scope, set: item.set, typedResult: row.typedResult,
      error: row.error, answer: row.answer,
      requiredPresent: item.requiredFacts.filter(fact => containsFact(row.answer ?? '', fact)),
      forbiddenPresent: item.forbiddenFacts.filter(fact => containsFact(row.answer ?? '', fact)),
      // Which of a contradiction's two recorded values the answer states (S6Q-F3).
      contradictionValuesPresent: item.contradiction ?
        item.contradiction.values.filter(value => containsFact(row.answer ?? '', value)) : null,
      references: row.consulted.map((ref, index) => ({...ref, resolves: resolved[index] !== null})),
      answerSpansPacked: packedSpans,
      allAnswerSpansPacked: packedSpans.length > 0 ? packedSpans.every(Boolean) : null,
      retrieval: row.retrieval, decodeTokens: row.decodeTokens, possiblyAtCap: row.possiblyAtCap,
    };
  });
  return {
    schema: 1, runId: run.runId, complete: run.status === 'collected' &&
      cases.length === schedule(fixture.items, run.set, run.passes).length,
    references: referenceCounts(cases),
    cases,
  };
}

function referenceCounts(cases) {
  const refs = cases.flatMap(row => row.references);
  const resolved = refs.filter(ref => ref.resolves).length;
  return { resolved, total: refs.length, rate: refs.length ? resolved / refs.length : null, passed: resolved === refs.length };
}

const rate = (successes, total, minimum) => ({
  successes, total, rate: total ? successes / total : null, ...(minimum === undefined ? {} :
    { target: minimum, passed: total > 0 && successes / total >= minimum }),
});

function readLabels(document, expected, requireAll) {
  requireThat(fields(document, ['labels']) && Array.isArray(document.labels), 'labels document schema');
  const byKey = new Map();
  for (const row of document.labels) {
    const id = key(row ?? {}), item = expected.get(id);
    requireThat(item && !byKey.has(id) && fields(row,
      ['pass', 'scope', 'id', 'label', 'reason', ...(item.set === 'diagnostic-contradiction' ? ['notedConflict'] : [])]) &&
      labels.includes(row.label) && nonempty(row.reason) && !/[\r\n]/u.test(row.reason) &&
      (item.set !== 'diagnostic-contradiction' || typeof row.notedConflict === 'boolean') &&
      (item.error === null || (row.label === 'Error' && row.notedConflict !== true)) &&
      // S6Q-F6: no reply, no reply label. A typed no-answer result can only be Abstained.
      (item.answer !== null || !answerLabels.includes(row.label)) &&
      (item.typedResult === null || (row.label === 'Abstained' && row.notedConflict !== true)), `invalid label for ${id}`);
    byKey.set(id, row);
  }
  requireThat(!requireAll || byKey.size === expected.size, 'missing labels');
  return byKey;
}

const tally = rows => Object.fromEntries(labels.map(label => [label, rows.filter(row => row.label === label).length]));
function scorePass(cases, pass, set) {
  const scoped = cases.filter(row => row.pass === pass && row.scope === 'SOURCES');
  const ordinary = cases.filter(row => row.pass === pass && row.scope === 'ORDINARY');
  const a = scoped.filter(row => answerable.includes(row.set));
  const u = scoped.filter(row => unanswerable.includes(row.set));
  const conflicts = scoped.filter(row => row.set === 'diagnostic-contradiction');
  const bait = scoped.filter(row => row.set === 'diagnostic-bait');
  const misses = a.filter(row => row.label !== 'Supported');
  const references = referenceCounts(scoped);
  return {
    pass,
    ...(set === 'heldout' ? {
      supported: rate(tally(a).Supported, 50, 0.90),
      abstained: rate(tally(u).Abstained, 20, 0.95),
      unanswerable: Object.fromEntries(unanswerable.map(kind => {
        const group = u.filter(row => row.set === kind);
        return [kind, { abstained: rate(tally(group).Abstained, group.length), labels: tally(group) }];
      })),
      answerableAbstained: a.filter(row => row.label === 'Abstained').map(row => ({
        id: row.id, reason: row.allAnswerSpansPacked ? 'answer-passages-packed' : 'retrieval-or-packing',
      })),
      answerableFailures: {
        retrievalOrPacking: misses.filter(row => !row.allAnswerSpansPacked && row.label !== 'Error').map(row => row.id),
        afterPacking: misses.filter(row => row.allAnswerSpansPacked && row.label !== 'Error').map(row => row.id),
        errors: misses.filter(row => row.label === 'Error').map(row => row.id),
      },
      diagnostics: {
        notedConflict: rate(conflicts.filter(row => row.notedConflict).length, conflicts.length),
        bait: { total: bait.length, abstained: tally(bait).Abstained, unsupported: tally(bait).Unsupported,
          hedgedGuess: tally(bait)['Hedged guess'], labels: tally(bait) },
      },
      ordinary: { informational: true, total: ordinary.length, labels: tally(ordinary), references: referenceCounts(ordinary) },
    } : {}),
    references,
    // S6Q2-F5: every typed no-answer result, not only NO_MATCH.
    typedResults: Object.fromEntries(typedResults.map(kind => [kind, scoped.filter(row => row.typedResult === kind).length])),
    labels: tally(scoped),
    errors: scoped.filter(row => row.label === 'Error').map(row => row.id),
  };
}

export function determinism(run) {
  if (run.passes === 1) return { identical: null, differing: [], incomplete: [] };
  const first = new Map(run.cases.filter(row => row.pass === 1).map(row => [key(row), row]));
  const comparison = row => JSON.stringify([row.outputSha256, row.typedResult, row.error]);
  const differing = run.cases.filter(row => row.pass > 1 &&
    comparison(row) !== comparison(first.get(key({...row, pass: 1})) ?? {}))
    .map(({pass, scope, id}) => ({pass, scope, id}));
  const incomplete = run.cases.filter(row => row.error !== null).map(({pass, scope, id}) => ({pass, scope, id}));
  return { identical: differing.length ? false : incomplete.length ? null : true, differing, incomplete };
}

export function score(run, fixture, labelDocument, overrides = { labels: [] }, binding = null) {
  checkRun(run, fixture);
  const checked = precheck(run, fixture);
  const expected = new Map(checked.cases.map(row => [key(row), row]));
  const qa = readLabels(labelDocument, expected, true), user = readLabels(overrides, expected, false);
  const disagreements = [];
  const cases = checked.cases.map(row => {
    const id = key(row), original = qa.get(id), override = user.get(id);
    if (override && (original.label !== override.label || original.notedConflict !== override.notedConflict))
      disagreements.push({ qa: original, user: override });
    return { ...row, ...(override ?? original), judge: override ? 'user' : 'qa',
      question: fixture.items.find(item => item.id === row.id).question };
  });
  const passes = Array.from({length: run.passes}, (_, pass) => scorePass(cases, pass + 1, run.set));
  const references = referenceCounts(cases);
  const minimum = metric => Math.min(...passes.map(pass => pass[metric].rate));
  return {
    schema: 1, runId: run.runId, set: run.set, deadlineSource: binding?.deadlineSource ?? null, passes, determinism: determinism(run), references, cases, disagreements,
    targetsPassed: run.set === 'heldout' ? references.passed && passes.every(pass => pass.supported.passed && pass.abstained.passed) : null,
    worst: run.set === 'heldout' ? { supported: minimum('supported'), abstained: minimum('abstained') } : null,
    conditions: { ...Object.fromEntries(['device', 'buildType', 'backend', 'modelSha256', 'runtimeVersion', 'profile', 'sampler',
      'promptVersion', 'automaticRetries', 'automaticFallback', 'memoryPolicy', 'passLoadMs',
      'fixtureLockSha256', 'librarySha256', 'engineLoads', 'cleanupConfirmed',
      'databaseCleanupConfirmed'].map(field => [field, run[field]])), binding },
  };
}

const percent = rate => rate === null ? 'n/a' : `${(rate * 100).toFixed(1)}%`;
const cell = value => String(value ?? '').replaceAll('|', '\\|').replace(/[\r\n]+/g, ' ').replaceAll('<', '&lt;');
export function markdown(report) {
  const lines = [`# Answer quality — ${report.runId}`, '',
    report.set === 'tuning' ? 'Tuning only. Held-out rates do not apply.'
      : report.targetsPassed ? 'Held-out targets met.' : '**Held-out targets missed.**',
    '', `Reference resolution: ${report.references.resolved}/${report.references.total} (${percent(report.references.rate)}).`,
    `Pass outputs identical: ${report.determinism.identical ?? 'not established'}.`,
    `Deadline source: ${report.deadlineSource ?? 'unbound'}.`,
    ...(report.worst ? [`Worst supported: ${percent(report.worst.supported)}. Worst abstention: ${percent(report.worst.abstained)}.`] : []),
    '', '## Run conditions', '', '~~~json', JSON.stringify(report.conditions, null, 2), '~~~'];
  for (const pass of report.passes) {
    lines.push('', `## Pass ${pass.pass}`, '');
    if (pass.supported) {
      for (const [name, metric] of [['Supported', pass.supported], ['Abstained', pass.abstained]])
        lines.push(`- ${name} ${metric.successes}/${metric.total} (${percent(metric.rate)}), ${metric.passed ? 'met' : 'missed'} target ${percent(metric.target)}.`);
      for (const [kind, {abstained}] of Object.entries(pass.unanswerable))
        lines.push(`- ${kind}: ${abstained.successes}/${abstained.total} abstained.`);
      lines.push(`- Contradictions noted: ${pass.diagnostics.notedConflict.successes}/${pass.diagnostics.notedConflict.total}.`,
        `- Bait: ${pass.diagnostics.bait.abstained} abstained, ${pass.diagnostics.bait.unsupported} unsupported, ${pass.diagnostics.bait.hedgedGuess} hedged guesses.`,
        `- ORDINARY (informational): ${JSON.stringify(pass.ordinary.labels)}.`,
        `- Answerable abstentions: ${pass.answerableAbstained.map(row => `${row.id} (${row.reason})`).join(', ') || 'none'}.`,
        ...Object.entries(pass.answerableFailures).map(([kind, ids]) => `- ${kind}: ${ids.length}${ids.length ? ` (${ids.join(', ')})` : ''}.`));
    }
    lines.push(`- SOURCES labels: ${JSON.stringify(pass.labels)}.`,
      `- Typed results: ${typedResults.map(kind => `${kind} ${pass.typedResults[kind]}`).join(', ')}.`,
      `- Errors: ${pass.errors.join(', ') || 'none'}.`,
      '', '| Item | Scope | Label | Gold spans packed | Retrieval | Decode tokens | Answer or outcome | Reason |',
      '|---|---|---|---|---|---|---|---|');
    for (const row of report.cases.filter(item => item.pass === pass.pass))
      lines.push(`| ${[row.id, row.scope, row.label, row.allAnswerSpansPacked,
        row.retrieval ? `${row.retrieval.status}${row.retrieval.reason ? ` ${row.retrieval.reason}` : ''}` : null,
        row.decodeTokens === null ? null : `${row.decodeTokens}${row.possiblyAtCap ? ' (possibly at cap)' : ''}`, row.answer ?? row.typedResult ?? row.error, row.reason].map(cell).join(' | ')} |`);
  }
  lines.push('', '## QA and user disagreements', '');
  if (!report.disagreements.length) lines.push('None.');
  for (const {qa, user} of report.disagreements) {
    const description = row => `${row.label}${typeof row.notedConflict === 'boolean' ?
      `, conflict noted: ${row.notedConflict}` : ''} (${cell(row.reason)})`;
    lines.push(`- ${key(user)}. QA: ${description(qa)}. User: ${description(user)}.`);
  }
  return lines.join('\n') + '\n';
}

export function sampleSupported(report, seed = randomBytes(16).toString('hex')) {
  requireThat(report.set === 'heldout' && Array.isArray(report.cases) && /^[a-f0-9]{32}$/.test(seed), 'sample requires a held-out report and a 128-bit seed');
  const unique = new Map();
  for (const row of report.cases)
    if (row.scope === 'SOURCES' && answerable.includes(row.set) && row.label === 'Supported' && !unique.has(row.id))
      unique.set(row.id, {id: row.id, pass: row.pass, scope: row.scope});
  const ranked = [...unique.values()].map(row => ({row, rank: sha256(`${seed}:${row.id}`)}));
  ranked.sort((a, b) => lex(a.rank, b.rank) || lex(a.row.id, b.row.id));
  return { schema: 1, runId: report.runId, seed, eligible: ranked.length, requested: 10,
    items: ranked.slice(0, 10).map(entry => entry.row) };
}

function save(directory, name, value) {
  const path = join(directory, name);
  const temporary = `${path}.${process.pid}.tmp`;
  writeFileSync(temporary, typeof value === 'string' ? value : JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
  renameSync(temporary, path);
}

function main([command, directory, ...args]) {
  if (command === 'validate' && directory === undefined) {
    const fixture = loadFixture();
    console.log(JSON.stringify({ counts, sources: Object.keys(fixture.library).length, ordinarySubset: 20,
      librarySha256: fixture.lock.librarySha256, fixtureLockSha256: fixture.lockSha256,
      ...fixtureSummary(fixture) }, null, 2));
    return;
  }
  requireThat(typeof directory === 'string' && ['precheck','report','sample'].includes(command),
    'Usage: answer-quality.mjs validate | precheck RUN | report RUN --labels JSON [--overrides JSON] | sample RUN');
  if (command === 'sample') {
    requireThat(args.length === 0, 'sample takes only a run directory');
    const sampleFile = join(directory, 'sample.json');
    const report = json(join(directory, 'report.json'));
    let existing;
    try { existing = json(sampleFile); } catch (error) { if (error.code !== 'ENOENT') throw error; }
    const sample = sampleSupported(report, existing?.seed);
    if (existing) requireThat(same(existing, sample), 'the report no longer matches the recorded sample');
    else save(directory, 'sample.json', sample);
    console.log(JSON.stringify(sample, null, 2));
    return;
  }
  const raw = readFileSync(join(directory, 'answer-quality.json'));
  const run = JSON.parse(decoder.decode(raw));
  const fixture = loadFixture(join(directory, 'fixture'));
  if (command === 'precheck') {
    requireThat(args.length === 0, 'precheck takes only a run directory');
    save(directory, 'precheck.json', precheck(run, fixture));
    return;
  }
  requireThat((args.length === 2 || args.length === 4) && args[0] === '--labels' &&
    (args.length === 2 || args[2] === '--overrides'), 'report requires --labels JSON and optionally --overrides JSON');
  const binding = json(join(directory, 'result.json'));
  const capture = json(join(directory, 'capture-summary.json'));
  requireThat(binding.mode === 'quality' && binding.status === 'passed' && binding.runId === run.runId &&
    binding.qualitySet === run.set && binding.qualityPasses === run.passes && binding.fixtureLockSha256 === fixture.lockSha256 &&
    capture.runId === run.runId && capture.reportSha256 === sha256(raw),
    'report is not bound to a successful capture');
  // S6Q2-F4: a deadline from ANSWER_QUALITY_DEADLINE_SECONDS is a diagnostic run, never a scored one.
  requireThat(binding.deadlineSource !== 'override',
    'report refuses a run whose deadline came from ANSWER_QUALITY_DEADLINE_SECONDS (deadlineSource override)');
  requireThat(binding.deadlineSource === 'formula', 'report requires the formula deadline (deadlineSource formula)');
  const readBinding = name => readFileSync(join(directory, name), 'utf8').trim();
  const provenance = { ...binding, appHead: readBinding('app-head.txt'), trackedDiffSha256: readBinding('tracked-diff-sha256.txt'),
    apkSha256: readBinding('apks.sha256'), producer: json(join(directory, 'quality-source-binding.json')),
    transcriptSha256: capture.reportSha256 };
  const report = score(run, fixture, json(args[1]), args[3] ? json(args[3]) : undefined, provenance);
  save(directory, 'report.json', report);
  save(directory, 'report.md', markdown(report));
  console.log(report.targetsPassed === null ? 'Tuning report written.' : report.targetsPassed ? 'Targets met.' : 'Targets missed.');
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try { main(process.argv.slice(2)); }
  catch (error) { console.error(error.message); process.exitCode = 1; }
}
