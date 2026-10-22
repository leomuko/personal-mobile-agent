# P2 Calibration-Policy Qualification

Execution handoff and current status, 2026-10-22. This is a prerequisite within
P2, not indexing, semantic-search UI, or complete P2 acceptance.

## Status and Scope

Host tooling and opt-in Android batch instrumentation are implemented. The
wording-corrected corpus has now received independent judgment reapproval bound
to the renewed lock and dataset hashes. Calibration/development collection is
completed through the supervised workflow. All three frozen policies failed the
development gates; the sealed selection contains no eligible policy. See the
[retained campaign results](../docs/P2_CALIBRATION_POLICY_RESULTS.md). Held-out
collection/evaluation was not run. Semantic search remains
disabled; corpus approval is not encoder-quality qualification or P2 acceptance.

The rejected freeze's independent judgment review **failed**; split separation
passed for the stated synthetic scope. The [original report](../docs/P2_CALIBRATION_JUDGMENT_REVIEW.md)
and the complete rejected dataset/lock/report bytes are retained under
`fixtures/calibration-policy-v2/rejected/2026-10-22-depth-ambiguity/`.
The authorized pre-collection correction changes only `dev020n1` to ask:
"Borage stand: What is the oil cup depth in millimetres?" Its intended all-zero
judgment remains unchanged pending independent re-review. The renewed freeze
records this provenance in [correction.json](fixtures/calibration-policy-v2/correction.json).
No scores or vectors informed the change. A separate QA reviewer completed the
[independent re-review](../docs/P2_CALIBRATION_JUDGMENT_REAPPROVAL.md) and issued
[approval](fixtures/calibration-policy-v2/independent-review.json) for this exact
renewed freeze. The original rejection and its report remain immutable history.

The v1 fixtures, lock, fitter and failed evidence remain unchanged. Its English
Recall@5 of 0.45, nDCG@10 of 0.45 and 0/20 false matches remain a failed result.
The QA-reported unfiltered ranking diagnostic suggests investigating calibration;
it does not authorize removing the cutoff or treating v1 as independent new data.

Keep the pinned USE encoder artifact, SDK/head 0, 100 dimensions, response-text
format, SDK L2 normalization and verified 192-token admission unchanged. Complete
candidate identities are in [the protocol](fixtures/calibration-policy-v2/protocol.json).
Do not change normal/isolated-QA memory policy, the five-second per-request
deadline, thermal stops, cancellation or confirmed-cleanup/quarantine rules.

## Frozen Inputs and Independent Review

The authoritative [lock](fixtures/calibration-policy-v2/lock.json) binds the
protocol, datasets and implementation source hashes. Its current SHA-256 is
`ed4712c0cb6261b83591f3e6678c71c1565a6270e1388dda529583218872d8d1`.
The prior pre-fix lock is retained under `fixtures/calibration-policy-v2/locks/`;
it is historical and must not authorize collection. That earlier code-fix refresh
changed two implementation hashes. This subsequent wording-only freeze changes
only the development dataset hash in the rejected lock; calibration, held-out,
protocol, policies, gates, encoder, implementation and judgments are unchanged.

| Split | Answerable | Unanswerable | Passages | Batches, at most 32 entries |
| --- | ---: | ---: | ---: | ---: |
| Calibration | 60 | 60 | 39 | 5 |
| Development | 40 | 40 | 28 | 4 |
| Held-out | 60 | 60 | 42 | 6 |

Each split has its own searchable passage corpus and fixed distractors. The JSON
contains explicit grades for every query/passage pair. Source families are
disjoint. Examples include short questions, lexical mismatch, missing same-topic
details, and conflicting records which remain discoverable without being asserted
as factual support. The current author-assigned grades use 0 and 3; the supported
grading scale is 0 through 3. Relevance means answering the requested detail or
explicitly contradicting it, not merely mentioning the same entity.

English is the only required language in this investigation. No new non-English
diagnostic evidence or supported-language claim is produced. The corpora are
synthetic; multiple questions can share a source record. Their descriptive
query-level intervals do not establish representative real-world performance.

Before any vector collection, an independent reviewer must inspect all texts,
complete judgments, distractors, contradiction treatment, source-family separation,
and exclusion of v1 examples and close paraphrases. Automated validation checks
exact duplication and family labels; it cannot prove semantic independence or
judgment correctness. The reviewer may reject this corpus. Do not manufacture an
approval or adjust labels after seeing scores.

An actual approval is recorded separately as `independent-review.json` beside the
lock. Required fields are: `schema: 2`, `protocolId`, the current `lockSha256`,
the lock's `datasets` mapping, independent reviewer identity, `independent: true`,
`judgmentsReviewed: true`, `separationReviewed: true`, `noScoresInspected: true`,
and the actual positive integer `reviewedAtMs` timestamp. The author cannot approve
their own work. This is an audit attestation, not a cryptographic identity system.
The current approval file was issued by the separate QA reviewer after complete
re-review and byte/hash verification, not by the implementation author.

## Policy Fitting and Selection

All policies preserve cosine-descending ranking and passage-ID-ascending ties.
Acceptance is `score >= cutoff`. JSON-safe cutoffs are `{ "kind": "all" }`,
`{ "kind": "none" }`, or `{ "kind": "score", "value": <finite score> }`.

F1 and F2 consider distinct top-one calibration scores plus accept-all/none.
F1 maximizes `2TP/(2TP+FN+FP)`; F2 maximizes `5TP/(5TP+4FN+FP)`; zero denominators
return zero. Ties prefer fewer false matches, then a higher cutoff.

Constrained recall considers **every distinct calibration query/passage score**,
including lower-ranked passages, plus accept-all/none. It maximizes cutoff-filtered
macro Recall@5 subject to calibration false-match rate <= 0.05. Ties prefer nDCG@10,
fewer false matches, then a higher cutoff. Its reported `objectiveName` is
`macro-recall-at-5`; its `objective` is that recall, not answerability F1.
F1/F2 also report explicit objective names. Equivalent numeric cutoffs retain the
protocol's stable candidate order.

Fit parameters only on calibration data. Evaluate the three fixed fits on
development data. Eligible policies must pass all three unchanged development
gates. Select by higher recall, higher nDCG, lower false-match rate, then the
frozen policy-ID order. If none qualifies, seal and retain that failed selection;
do not plan held-out batches or tune against held-out data.

`fitAndSelect` has no held-out argument and does not open the held-out dataset.
It writes one selection manifest and a matching root-level seal, binding policy,
parameters, code, dataset, review, collection and runtime snapshot identities.
Changing output directories does not allow another selection in the same root.

## Collection and One-Time Evaluation

Only reviewed calibration/development inputs can be planned before selection.
Held-out planning and device-wrapper admission require a **successful sealed
selection**, validated before held-out dataset loading or ADB access. Held-out
batch manifests additionally bind the exact selection hash. Missing, failed,
unsealed or tampered selections cannot authorize collection. Authoring and
independent judgment review necessarily inspect unscored text; fitting and policy
selection must not inspect held-out scores or judgments.

Android only collects bounded, supervised vectors through the existing encoder-only
owner. It does not fit policies or receive relevance judgments. Every batch runs
current counting/loading/encoding cancellation checks, validates exact text and
candidate identities, and requires confirmed cleanup and observed worker death.
At most one passage encode is outstanding. No Gemma generation is used to search.

Keep every attempt folder under `benchmarks/results/`. Successful batches are
sealed in the root's `.collection/` ledger and cannot be recollected to choose
different vectors. Only infrastructure failures may retry. An orphaned active lease
requires explicit recovery review; it does not expire automatically. Assembly
requires every batch exactly once with compatible APK/source snapshots; it must
not silently choose the latest attempt or combine partial collections.

Final evaluation validates the selected manifest and matching seal, then writes
`exposed.json` **before scoring**. It scores only the locked policy once. Results
and post-exposure infrastructure failures are write-once. Do not remove ledgers,
copy the root to bypass them, overwrite results, or reselect after exposure.
Conservative failures after exposure consume this protocol's evaluation; further
investigation requires a separately versioned protocol, not a silent rerun.

Report cutoff-filtered macro Recall@5 and graded nDCG@10, query-level results,
numerators/denominators, false-match counts/rates and descriptive 95% intervals.
Bootstrap rules are frozen: 10,000 samples, xorshift32 seed 20261021, n answerable
query draws with replacement per sample, paired draws for recall/nDCG, nearest-rank
2.5%/97.5% quantiles. False-match intervals use two-sided Wilson without continuity
correction. Intervals do not change the point-estimate gates:

- Recall@5 >= 0.90.
- nDCG@10 >= 0.80.
- False-match rate <= 0.05: 3/60 passes; 4/60 fails.

Unfiltered ranking is diagnostic only. A quality pass qualifies only this frozen
candidate/configuration/policy and corpus scope for subsequent indexing work.
It does not establish full-library capacity, hybrid-search latency, grounded
answers or complete P2. Every result keeps `productionEnablement: false`.

## Commands After Independent Approval

Run from the repository root. These commands are an execution recipe, not evidence
that every stage has run. The `check` command now validates the actual renewed
approval; historical approval or an old lock cannot authorize collection.

```sh
ROOT=planning/fixtures/calibration-policy-v2
node scripts/calibration-investigation.mjs check "$ROOT"
node scripts/calibration-investigation.mjs plan "$ROOT" build/calibration-plans calibration
node scripts/calibration-investigation.mjs plan "$ROOT" build/calibration-plans development
```

Build/install the designated secure-QA APKs through the approved QA workflow,
with baseline or explicitly authorized Android-pressure mode recorded. For each
planned batch, set designated `ANDROID_SERIAL` and pinned `P2_ENCODER_MODEL`, then
use the existing supervised wrapper:

```sh
node scripts/run-p2-embedding-gate.mjs --calibration-root "$ROOT" \
  --calibration-batch build/calibration-plans/calibration-0.json
```

Use `--android-pressure` only for the explicitly authorized isolated-QA profile,
with matching APK build flag. Do not change other safeguards. Assemble each split
using explicit successful `attempt.json` paths for **all** its batches:

```sh
node scripts/calibration-investigation.mjs assemble "$ROOT" build/calibration.json calibration <attempt-paths...>
node scripts/calibration-investigation.mjs assemble "$ROOT" build/development.json development <attempt-paths...>
node scripts/calibration-investigation.mjs select "$ROOT" build/calibration-selection build/calibration.json build/development.json
```

Only successful sealed selection permits held-out planning/collection. Then assemble
the held-out collection and run `evaluate` once with the same selection directory.
Quality failure exits with code 2. Invalid evidence/infrastructure failure exits
with code 1; successful collection alone is not a quality pass.

## Verification and Remaining Gates

Both QA findings were first reproduced by new host regressions, then fixed.
The focused suite covers divergent F1/recall objectives and missing/failed/tampered
selection rejection in planning and the actual wrapper before a fake ADB can run.
It also covers selection-bound held-out plans, loss of the seal, exposure,
split isolation, lower-ranked boundaries, incomplete batches, ownership/recovery,
manifest integrity and write-once results.

Current host campaign: 50 Node tests passed, zero failed/skipped. Baseline debug
build, shared JVM test task, lint and debug instrumentation assembly succeeded;
Gradle reused up-to-date outputs, including JVM tests. Secure-QA compilation is
reported separately in the implementation close-out. No device access or vector
collection was performed for these fixes. No independent judgment approval,
real development selection or held-out qualification is claimed.

Independent judgment reapproval and supervised calibration/development collection
are complete. Development selection failed and was sealed once; held-out planning
rejects the failed selection. Further tuning requires a separately versioned
investigation, not reopening this campaign or weakening its gates.
The rejected freeze's separate-agent review covered all 320 queries, 109 passages
and 11,960 pair judgments, with no scoring. The previous host campaign passed
50/50 tests and isolated Android-pressure QA APKs built successfully in preparation.
The wording correction now has byte-exact snapshot/delta regression coverage;
fresh host validation passed (51 Node tests, zero failures/skips; baseline Gradle
tasks passed, largely up-to-date). After reapproval, only the secure-QA app/test
APKs were updated with data preserved, and supervised calibration/development
collection began. The original failed gate was resolved by a wording-only renewed
freeze and independent approval, not by a waiver or policy change.
Indexing, vector persistence, hybrid UI, Memory, PDF and consolidated P2 acceptance
remain subsequent work. Release-packaging, normal memory-policy and audio deferrals
are unchanged.
