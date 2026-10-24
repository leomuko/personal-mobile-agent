import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, writeFileSync, mkdtempSync, mkdirSync, copyFileSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { tmpdir } from 'node:os';
import { OA_ROOT, OA_INPUTS, OA_ID } from '../output-adherence-preparation.mjs';
import { OA_PREPARATION_SHA, loadApprovedAdherence } from '../output-adherence-gates.mjs';
import { sha256 } from '../v3-feasibility.mjs';

function fixture(run) {
  const root = mkdtempSync(join(tmpdir(), 'oa-approval-host-only-'));
  try {
    for (const path of [...OA_INPUTS, ...['preparation-lock.json', 'prepared.json', 'validator-cases.tsv',
      'validator-cases.sha256', 'integration-authorization.json'].map(name => `${OA_ROOT}/${name}`)]) {
      const target = join(root, path); mkdirSync(dirname(target), { recursive: true }); copyFileSync(path, target);
    }
    const read = name => JSON.parse(readFileSync(join(root, OA_ROOT, name)));
    const lock = read('preparation-lock.json');
    // Synthetic approval lives only inside this temporary fixture, never the repository corpus.
    const review = { schema: 1, protocolId: OA_ID, verdict: 'PASS', scope: 'output-adherence-corpus-and-exact-packs',
      preparationLockSha256: OA_PREPARATION_SHA, preparedSha256: sha256(readFileSync(join(root, OA_ROOT, 'prepared.json'))),
      files: lock.files, controls: ['oa01', 'oa02', 'oa03', 'oa04', 'oa05', 'oa06'], reviewer: 'synthetic-host-fixture-only',
      nativeIntegrationAuthorized: false, deviceExecutionAuthorized: false, semanticEnablement: false };
    const path = join(root, OA_ROOT, 'independent-review.json'); writeFileSync(path, JSON.stringify(review));
    return run({ root, read, review, path });
  } finally { rmSync(root, { recursive: true, force: true }); }
}

test('integration needs both the exact corpus attestation and human scope approval', () => fixture(f => {
  assert.equal(loadApprovedAdherence(f.root).plan.entries.length, 6);
  rmSync(f.path); assert.throws(() => loadApprovedAdherence(f.root), /ENOENT/);
  for (const edit of [r => { r.verdict = 'FAIL'; }, r => { r.preparationLockSha256 = 'stale'; },
    r => { r.controls.reverse(); }, r => { delete r.files[OA_INPUTS[0]]; },
    r => { r.deviceExecutionAuthorized = true; }]) {
    const review = structuredClone(f.review); edit(review); writeFileSync(f.path, JSON.stringify(review));
    assert.throws(() => loadApprovedAdherence(f.root), /attestation/);
  }
}));

test('integration approval cannot enlarge the experiment or silently change profile', () => fixture(f => {
  const original = f.read('integration-authorization.json'), path = join(f.root, OA_ROOT, 'integration-authorization.json');
  for (const edit of [a => { a.requests = 8; }, a => { a.deadlineMs = 60000; },
    a => { a.profileId = 'memory-baseline-v1'; }, a => { a.cleanupWithinDeadline = false; },
    a => { a.preparationLockSha256 = 'other'; }, a => { a.deviceExecutionAuthorized = true; },
    a => { a.integrationApproved = false; }]) {
    const approval = structuredClone(original); edit(approval); writeFileSync(path, JSON.stringify(approval));
    assert.throws(() => loadApprovedAdherence(f.root), /integration approval/);
  }
}));

test('native inputs and separate QA oracle have distinct immutable identities', () => fixture(f => {
  const { plan, oracles, bindings } = loadApprovedAdherence(f.root);
  assert.equal(plan.oraclesSha256, sha256(JSON.stringify(oracles)));
  assert.equal(bindings.planSha256, sha256(JSON.stringify(plan)));
  assert.equal(bindings.preparationLockSha256, OA_PREPARATION_SHA);
  for (const entry of plan.entries) {
    assert.equal(entry.packSha256, sha256(plan.instructions + '\nDATA\n' + entry.dataJson + '\nEND DATA'));
    assert.equal(entry.encoderInputSha256, sha256(entry.question));
    assert.deepEqual(Object.keys(JSON.parse(entry.dataJson)), ['q', 'd', 'p']);
    for (const key of ['expected', 'witnesses', 'judgments', 'review', 'shape']) assert.equal(key in entry, false);
  }
  assert.deepEqual(oracles.controls.map(c => c.state), ['S', 'C', 'M', 'U', 'M', 'S']);
}));
