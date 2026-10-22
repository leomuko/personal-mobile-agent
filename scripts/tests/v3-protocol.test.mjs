import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { createHash } from 'node:crypto';

// Preparation-only checks: no runtime imports, model calls, scoring or collection.
const root = 'planning/fixtures/evidence-sufficiency-v3';
const json = path => JSON.parse(fs.readFileSync(path, 'utf8'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const protocol = json(`${root}/protocol.json`);
const splits = ['calibration', 'development', 'heldout'].map(s => json(`${root}/${s}.json`));
const instructions = fs.readFileSync(`${root}/verifier-instructions.txt`, 'utf8').replace(/\n$/, '');
const bytes = s => Buffer.byteLength(s, 'utf8');
const prefix = text => {
  let result = '';
  for (const scalar of text) {
    if (bytes(result + scalar) > 160) break;
    result += scalar;
  }
  return result;
};
const splitBoundary = (text, i) => i > 0 && i < text.length &&
  /[\uD800-\uDBFF]/.test(text[i - 1]) && /[\uDC00-\uDFFF]/.test(text[i]);

test('preparation remains non-executing and preserves pinned constraints', () => {
  assert.equal(protocol.scope, 'protocol-rubric-datasets-only');
  for (const key of ['nativeCollectionAuthorized', 'verifierImplementationAuthorized', 'semanticEnablement'])
    assert.equal(protocol[key], false);
  assert.equal(protocol.encoder.tokenLimit, 192);
  assert.equal(protocol.encoder.dimensions, 100);
  assert.equal(protocol.verifier.contextTokens, 2048);
  assert.equal(protocol.verifier.reservedOutputTokens, 512);
  assert.equal(protocol.timing.wholePipelineHardDeadlineMs, 5000);
  assert.ok(protocol.timing.included.includes('prefill/inference'));
  assert.ok(protocol.timing.included.includes('output validation'));
  assert.equal(protocol.selection.eligibleApproaches.length, 1);
  assert.match(protocol.selection.heldout, /successful sealed development/);
  assert.deepEqual(Object.values(protocol.metrics.endToEnd).filter(v => typeof v === 'number'), [.9, .8, .05]);
});

test('all three pools have disjoint families/entities and complete two-level judgments', () => {
  const families = new Set(), entities = new Set(), ids = new Set();
  for (const d of splits) {
    assert.equal(d.passages.length, 60); assert.equal(d.queries.length, 60);
    const ownFamilies = new Set(d.passages.map(p => p.sourceFamily));
    const ownEntities = new Set(d.passages.map(p => p.entity));
    for (const f of ownFamilies) { assert.ok(!families.has(f)); families.add(f); }
    for (const e of ownEntities) { assert.ok(!entities.has(e)); entities.add(e); }
    const counts = { S: 0, C: 0, M: 0, U: 0 };
    for (const row of [...d.passages, ...d.queries]) { assert.ok(!ids.has(row.id)); ids.add(row.id); }
    for (const q of d.queries) {
      counts[q.fullEvidenceLabel]++;
      assert.equal(q.relevanceGrades.length, 60); assert.equal(q.detailJudgments.length, 60);
      assert.ok(bytes(q.question) <= 180);
      assert.ok(q.requestedDetails.length >= 1 && q.requestedDetails.length <= 2);
      for (const [i, row] of q.detailJudgments.entries()) {
        assert.equal(row.length, q.requestedDetails.length); assert.match(row, /^[SCMU]+$/);
        assert.ok(Number.isInteger(q.relevanceGrades[i]) && q.relevanceGrades[i] >= 0 && q.relevanceGrades[i] <= 3);
        if (d.passages[i].entity !== q.requestedDetails[0].e) {
          assert.equal(q.relevanceGrades[i], 0); assert.ok([...row].every(s => s === 'U'));
        }
      }
    }
    assert.deepEqual(counts, protocol.splitPopulations[d.split]);
  }
  for (const split of ['calibration', 'development', 'heldout']) {
    const prior = json(`planning/fixtures/calibration-policy-v2/${split}.json`);
    for (const p of prior.passages) { assert.ok(!families.has(p.sourceFamily)); assert.ok(!entities.has(p.entity)); }
  }
});

test('fixed packs reproduce exact serialization, clipping, byte bounds and recorded identities', () => {
  const freeze = json(`${root}/freeze.json`);
  for (const d of splits) for (const q of d.queries) {
    let p = q.componentCandidates.map(id => {
      const row = d.passages.find(p => p.id === id); assert.ok(row);
      return { p: id, r: row.revision, b: 0, t: prefix(row.text) };
    });
    const render = () => instructions + '\nDATA\n' + JSON.stringify({ q: q.question, d: q.requestedDetails, p }) + '\nEND DATA';
    while (p.length && bytes(render()) > 1312) p.pop();
    assert.ok(p.length); assert.equal(render(), q.fixedPack.payload);
    assert.equal(bytes(render()), q.fixedPack.utf8Bytes);
    assert.equal(224 + bytes(render()), q.fixedPack.inputTokenUpperBound);
    assert.ok(q.fixedPack.inputTokenUpperBound + 512 <= 2048);
    assert.ok(!render().includes('<|') && !render().includes('<start_of_') && !render().includes('<turn|>'));
    assert.equal(hash(render()), freeze.packs[q.id]);
    assert.deepEqual(q.fixedPack.deliveredIntervals, p.map(v => ({ p: v.p, r: v.r, b: 0, e: v.t.length })));
  }
});

test('all witness coordinates are exact Unicode-safe original revision spans', () => {
  for (const d of splits) for (const q of d.queries) {
    for (const w of [...q.witnesses, ...q.fixedPack.gold.d.flatMap(d => d.witnesses)]) {
      const p = d.passages.find(p => p.id === w.p); assert.ok(p); assert.equal(w.r, p.revision);
      assert.ok(w.b >= 0 && w.b < w.e && w.e <= p.text.length);
      assert.equal(p.text.slice(w.b, w.e), w.q); assert.ok(bytes(w.q) <= 96);
      assert.ok(!splitBoundary(p.text, w.b) && !splitBoundary(p.text, w.e));
    }
    for (const detail of q.fixedPack.gold.d) {
      if (['M', 'U'].includes(detail.s)) assert.equal(detail.witnesses.length, 0);
      for (const w of detail.witnesses) assert.ok(q.fixedPack.deliveredIntervals.some(i =>
        i.p === w.p && i.r === w.r && i.b <= w.b && w.e <= i.e));
      if (detail.s === 'C') {
        assert.equal(new Set(detail.witnesses.map(w => w.p)).size, 2);
        assert.ok(detail.witnesses.some(w => d.passages.find(p => p.id === w.p).conflictsWith?.length));
      }
    }
  }
});

test('intentional clipping, missing units, ambiguous dates/zones and injection retain their judgments', () => {
  for (const d of splits) {
    const losses = d.queries.filter(q => ['S', 'C'].includes(q.fullEvidenceLabel) && !q.fixedPack.completeRequiredEvidence);
    assert.equal(losses.length, 1); assert.equal(losses[0].fullEvidenceLabel, 'S');
    assert.equal(losses[0].fixedPack.gold.o, 'M');
    assert.equal(losses[0].fixedPack.gold.d[1].s, 'M');
    for (const q of d.queries.filter(q => q.id.endsWith('n'))) {
      assert.equal(q.fullEvidenceLabel, 'M'); assert.equal(q.fixedPack.gold.o, 'M');
    }
    assert.equal(d.passages.filter(p => p.untrustedInstruction).length, 4);
    assert.ok(d.passages.some(p => p.text.includes('locale unspecified')));
    assert.ok(d.passages.some(p => p.text.includes('time zone unspecified')));
    assert.ok(d.passages.some(p => p.text.includes('🌐')));
    assert.ok(d.passages.some(p => p.text.includes('Cafe\u0301')));
    assert.ok(d.queries.some(q => q.fullEvidenceLabel === 'M' && q.fixedPack.gold.d.some(d => d.s === 'S')));
  }
});

test('frozen preparation files and prior campaigns remain byte-identical', () => {
  const freeze = json(`${root}/freeze.json`);
  for (const file of freeze.files) {
    const b = fs.readFileSync(file.path); assert.equal(b.length, file.bytes, file.path);
    assert.equal(hash(b), file.sha256, file.path);
  }
  const preserved = json(`${root}/preserved-v1-v2.json`);
  for (const file of preserved.files) {
    const b = fs.readFileSync(file.path); assert.equal(hash(b), file.sha256, file.path);
  }
});

test('judgment approval, when present, binds the exact freeze rather than a prose verdict', () => {
  const path = `${root}/independent-review.json`;
  if (!fs.existsSync(path)) return; // Pending remains blocking; absence is never approval.
  const approval = json(path), freeze = json(`${root}/freeze.json`);
  assert.equal(approval.verdict, 'PASS');
  assert.equal(approval.freezeSha256, hash(fs.readFileSync(`${root}/freeze.json`)));
  assert.equal(approval.scope, 'protocol-rubric-full-corpus-and-delivered-pack-judgments');
  assert.deepEqual(approval.files, freeze.files);
  assert.equal(approval.nativeCollectionAuthorized, false);
  assert.equal(approval.verifierImplementationAuthorized, false);
});
