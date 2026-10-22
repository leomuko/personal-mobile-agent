# P2 V3 Preparation Status

Date: 2026-10-22. Branch: dev. Base: 2f36abd.

## Delivered Preparation

- Protocol: `planning/P2_V3_EVIDENCE_SUFFICIENCY_PROTOCOL.md`.
- Rubric: `planning/P2_V3_JUDGMENT_RUBRIC.md`.
- Machine contract, three corpora, exact packs and freeze:
  `planning/fixtures/evidence-sufficiency-v3/`.
- Host artifact checks: `scripts/tests/v3-protocol.test.mjs`.

Each split has 60 passages and 60 questions, with complete passage/detail judgments,
relevance grades, full-corpus labels and independently reviewable delivered-pack
labels. All inputs are synthetic. The authored English patterns do not establish
real-world or multilingual generalization.

Freeze 1 failed an identity prerequisite: query suffixes collided with passage IDs.
Its original datasets/freeze and failure explanation are retained under `rejected/`.
Freeze 2 inserts `q` in query IDs only; corpus text, labels, instructions and policy
are unchanged. This happened before independent review or model collection.

## Verification

Passed: `node --test scripts/tests/*.test.mjs`: 58 tests, zero failures/skips.
This includes seven V3 preparation checks and the existing 51 host regressions.
Passed: `git diff --check`. Preservation checks cover 289 retained V1/V2 files,
including original rejected bytes, approvals, policies and saved campaign evidence.
These checks require the retained local historical artifacts; they are not a claim
that ignored benchmark files are distributed in a clean Git checkout.

Independent corpus judgment review: PASS for preparation only. The separate review
in `docs/P2_V3_CORPUS_JUDGMENT_REVIEW.md` covers all 180 questions, 180 passages,
14,400 passage/detail judgments and 180 exact delivered packs. The attestation in
`planning/fixtures/evidence-sufficiency-v3/independent-review.json` binds freeze 2:
`eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9`.
The seven preparation checks passed again after approval, including its exact
freeze/file binding. No frozen files changed during or after independent review.

Not run: Android builds/tests, ADB, S24 workloads, vectors, verifier generation,
quality scoring or feasibility timings. No verifier or collector was implemented.
Existing application source and native policy were not changed by this slice.
No commits were created.

## Next Boundary

Semantic search remains disabled. Preparation approval alone does not authorize
runtime implementation/collection. A later authorized experiment must first pass
the small whole-pipeline feasibility gate, then component and development gates.
Held-out planning/collection requires a successful sealed development selection.
The five-second whole-pipeline deadline and 2048/512 budget remain unchanged.

## Subsequent Authorized Work

This document records the completed preparation step, not the current runtime
acceptance status. The subsequently authorized validator and small S24 experiment
are recorded in `docs/P2_V3_FEASIBILITY_RESULTS.md`. Their first request exceeded
the whole-pipeline deadline; the larger campaign is blocked. The original
preparation freeze and judgment approval remain unchanged.
