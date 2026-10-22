# P2 V3 Independent Corpus Judgment Review

## Findings And Verdict

PASS for preparation only. No actionable P0-P3 judgment, protocol, separation or
fixed-pack blockers were found. This verdict follows a full semantic review and
independent matrix audit, not merely the seven passing preparation tests.

Reviewer: `codex-independent-v3-corpus-judge-ba4a8b10-4bef-42cf-b3ce-9ac706639814`.
Review date: 2026-10-22. This reviewer did not author the corpus or protocol.

The separate attestation approves only
`protocol-rubric-full-corpus-and-delivered-pack-judgments`. It authorizes no
verifier implementation, native collection or semantic enablement, and selects no
approach or policy. No V3 quality scores or model results were produced.

## Scope And Snapshot

- Repository: `/Users/kezileo/Desktop/personal_mobile_agent`; branch `dev`.
- Review anchor/HEAD: `2f36abda3f72823e1c80c00ec2569ffe9eb3342b`. The preparation
  artifacts are untracked working-tree files, so HEAD alone does not identify them.
- Current freeze: `evidence-sufficiency-v3-preparation-freeze-2`.
- SHA-256 of the exact current `freeze.json` bytes:
  `eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9`.
- All 15 entries in `freeze.files` were verified for byte size and SHA-256; all
  180 exact payload hashes were reproduced. The attestation copies that exact
  ordered file array, including the retained rejected freeze and budget anchor.
- Primary review: `planning/P2_V3_EVIDENCE_SUFFICIENCY_PROTOCOL.md`,
  `planning/P2_V3_JUDGMENT_RUBRIC.md`, the complete current V3 fixture directory,
  its preserved rejection, and `scripts/tests/v3-protocol.test.mjs`.
- Context: `AGENTS.md`, `planning/QA_AGENT_PROMPT.md`, README, implementation plan,
  deferred issues, configured modules/targets, the frozen `GemmaChatBudget` source,
  and prior corpus materials for preservation and non-reuse only.
- Applied `kotlin-kmp-code-review` and `kotlin-testing-kmp`, including applicable
  code-quality/tests, data/security and KMP/release references. UI, architecture,
  platform integration and runtime implementation review are outside this scope.

The existing dirty runner and V2/application work were not modified or approved.
The concurrently added preparation-status document is outside this frozen review.
No Git operations changed HEAD, staging or branch.

## Full Review Coverage

All 180 new questions were read against all 180 source passages within their
respective 60-passage pools. There was no sampling. Cross-pool passages are not
search candidates for a query.

| Reviewed Object | Calibration | Development | Heldout | Total |
| --- | ---: | ---: | ---: | ---: |
| Source passages and source assertions | 60 | 60 | 60 | 180 |
| Questions / full-set labels | 60 | 60 | 60 | 180 |
| Requested details / full-detail labels | 80 | 80 | 80 | 240 |
| Passage/query matrix rows | 3,600 | 3,600 | 3,600 | 10,800 |
| Individual passage/detail state cells | 4,800 | 4,800 | 4,800 | 14,400 |
| Passage/query relevance grades | 3,600 | 3,600 | 3,600 | 10,800 |
| Full-corpus semantic witnesses | 60 | 60 | 60 | 180 |
| Exact delivered packs / pack labels | 60 | 60 | 60 | 180 |
| Delivered detail labels | 80 | 80 | 80 | 240 |
| Delivered semantic witnesses | 59 | 59 | 59 | 177 |
| Delivered source intervals | 180 | 180 | 180 | 540 |
| Directed conflict edges | 10 | 10 | 10 | 30 |

Audit coverage includes every group `01` through `10` for each prefix `v3k`,
`v3d`, `v3h`, and every question suffix `qs`, `qt`, `qm`, `qn`, `qc`, `qu`.
For every row, the six same-entity records were judged individually and every
other-entity record checked as U/grade 0. All-unrelated questions were checked
against all 60 records. Literal expected matrix patterns were independently
checked across every cell after reading the actual source text and question.

Full labels are S=20, C=10, M=20, U=10 in each split. Delivered labels are S=19,
C=10, M=21, U=10 in each split. These are corpus inventories, not model metrics.

## Semantic And Pack Judgments

- Every requested detail follows from its question alone. Source entity/cycle,
  attribute, value and unit annotations agree with the immutable source text.
  Service breaks never substitute for absent inspection intervals.
- Wrong-cycle assertions retain topical grade 1 and passage/detail U; applicable
  records missing the requested attribute or unit are M/grade 2. Explicit facts
  receive grade 3, including conflicting facts. Two-detail grades correctly use
  the maximum applicable grade while retaining separate detail states.
- All 30 conflict edges identify an existing same-entity, same-cycle,
  same-attribute record with an incompatible explicit value. Both records remain
  applicable; their text explicitly denies supersession. Full and delivered C
  judgments retain both witnesses. Neither chronology nor a disputing sentence
  grants authority to choose a value.
- All full and delivered S labels have every requested detail supported. Partial
  M cases retain the supported detail and its citation without promoting the
  complete question to S. C precedence and the distinction between passage-level
  assertion and set-level conflict are consistent across the rubric and protocol.
- Missing units remain missing; exact cm/mm and terminating s/min conversions
  support the applicable questions. Ambiguous slashed dates and local timestamps
  without zones remain M. No locale, offset, alias or extra conversion is inferred.
- Injection strings in the 12 marked passages contribute no fact or authority.
  Exact DATA objects contain only `q,d,p`, question-derived `i,e,a,c,u`, and
  passage `p,r,b,t`. Assertions, matrices, grades, gold witnesses, flags and
  source-family annotations are absent from every delivered payload.
- Every full witness was read for semantic support; every delivered witness was
  matched to its full witness and actual delivered context. Attribute/value
  spans may omit the header because the entity/cycle header is delivered in the
  same excerpt. Alternate valid semantic spans are allowed by the rubric.
- All 357 recorded witness occurrences have exact revision-bound UTF-16 source
  slices. Witnesses and all 540 delivered intervals have safe boundaries; all
  19,089 inspected JSON strings are well-formed Unicode. Composed accents,
  combining marks and supplementary-plane characters are retained exactly.
- All 180 complete payloads reproduce the frozen instructions, compact JSON key
  order, LF delimiters, scalar-prefix clipping, candidate order, intervals,
  byte counts, token upper bounds and SHA-256 identities. No fixed pack drops a
  whole candidate for fitting. All gold output objects fit the JSON byte and
  reference limits; this is not a generated-token or native-runtime measurement.

The explicit loss cases are `v3k01qt`, `v3d01qt`, `v3h01qt`. Their station witnesses
are respectively `[231,252)`, `[230,252)`, `[231,252)` in their original `01l`
records, beyond delivered `[0,160)`. Each remains full S/SS and delivered M/SM,
with `completeRequiredEvidence=false`; the omitted station has no delivered
witness. Their full answerable status is preserved for end-to-end evaluation.

Payload byte ranges are 842-1076, 842-1078 and 842-1080 by split. The largest
payload gives `128 + 96 + 1080 + 512 = 1816 <= 2048`. The protocol's maximum
1312-byte payload gives exactly 2048. Question maxima are 72/71/73 UTF-8 bytes;
all satisfy the 180-byte limit. The 160-byte excerpt cap and 512-token output
reservation remain separate constraints, consistent with the frozen budget.

## Separation And Preservation

All nine source families and all 30 source entities are split-disjoint. The 30
absent-entity question identities are also disjoint. Read all V1 source/question
text and current V2 source/question text, plus the rejected V2 correction, for
non-reuse review. Normalized exact-text comparisons covered 952 prior text rows
including duplicate rejected V2 copies. No new source or question duplicates an
old example; semantic inspection found no close paraphrase of an old substantive
example. Common record syntax, units, missing-detail patterns and Unicode test
patterns are the explicitly disclosed structural reuse, not independent domains.

All 289 prior files listed in `preserved-v1-v2.json` retain their byte sizes and
hashes. Prior heldout content was accessed only for this authorized non-reuse
review; it was not collected, encoded, scored, used for tuning or evaluated.

The retained freeze-1 datasets and reason remain intact. Reversing only the `q`
insertion in all 180 current query IDs makes each dataset structurally identical
to its preserved original. Payload hashes match the old freeze under that exact
ID mapping. Passage IDs, text, ordering, judgments, witnesses, intervals and packs
did not change. Current query and passage IDs are globally unique.

## Numerical And Execution Contracts

Reviewed the definitions without computing V3 model metrics or choosing a policy.

- Relevance-only Recall@5 uses grade-3 passages; its inventory is 40 questions per
  split. nDCG@10 uses gain `2^grade - 1` and positive-grade questions, 50 per split.
  No-positive questions are separately reported. These populations differ
  intentionally from full S/C answerability and from delivered-pack sufficiency.
- Fixed-pack recovery/abstention uses the actual delivered S/C/M/U populations.
  Precision includes valid claims of the state plus every failed/invalid request;
  empty precision is zero. False support retains the delivered C/M/U denominator.
  Overall labels alone cannot earn recovery or precision credit without correct
  details and semantic witnesses. Invalid references cannot be accepted.
- Full S/C remains answerable; M/U remains unanswerable. End-to-end relevance
  credit is zeroed on incorrect overall/detail decisions or failed/invalid
  requests. Full M/U S/C claims and failures count as false matches; correct M
  with a supported partial-detail citation does not. The frozen 30-question
  unanswerable denominator makes one false match pass 5% and two fail, without
  rounding. Coverage is assessed separately against actual delivered evidence.
- Descriptive uncertainty fixes 10,000 query resamples, unsigned xorshift32,
  seed 20261022 reset per metric/population, ASCII query ordering, paired indices,
  nearest-rank quantiles and the stated Wilson z value. Fixed-denominator failures
  remain in their populations. Intervals do not replace point-estimate gates;
  undefined populations cannot pass. Query resampling is not evidence of
  independent real-world examples or deployment generalization.
- Calibration permits diagnostics only. Component gates precede combined
  development; selection requires all component/end-to-end/performance gates.
  Retrieval alone is ineligible and cannot invent sufficiency labels. Sealing
  binds the reviewed artifacts, implementation, accepted attempts and selection
  before any heldout planning/collection. Heldout is used once for the locked
  approach, without post-exposure reselection. Infrastructure retries preserve
  failed attempts and cannot silently replace quality outcomes.
- The five-second monotonic deadline begins at foreground submission and includes
  queueing, reclamation, packing/counting, encoder load/encoding/confirmed cleanup,
  Gemma load/prefill/inference, and validation. It is never reset at admission.
  Keyword p95 <=1s and complete hybrid p95 <=2s remain targets requiring later
  evidence. Any small-feasibility overrun blocks the larger combined campaign.
  Timeout invalidates the result and signals the current owner; cleanup remains
  separately tracked with reservation retention and quarantine when uncertain.
  Stop/host validity, serial residency, no tools/writes and no production
  integration are consistent with that contract. No runtime capability is claimed.

## Verification And Limits

| Check | Outcome | Evidence / Limit |
| --- | --- | --- |
| `node --test scripts/tests/v3-protocol.test.mjs` | Passed | Exit 0; 7 tests, 0 failures. The absent-approval branch was pending on the first run, not evidence of approval. Rerun after attestation validates the approval binding. |
| Exact freeze/file/pack hash and size checks | Passed | All 15 bound files and 180 packs; freeze SHA above. |
| Independent complete matrix and semantic audit | Passed | Counts and semantic review above; no corpus edits. |
| Witness/context, Unicode and annotation-boundary audit | Passed | All full/delivered witnesses, intervals and DATA key sets. |
| Freeze-1 renewal / V1-V2 preservation / non-reuse | Passed | Query-only deep comparison; all 289 preserved hashes and sizes; text review. |
| Verifier parser, native settings/token feasibility, model quality | Not run | No implementation, model run, collection or scoring authorized. |
| Five-second execution, cancellation, cleanup, latency, S24 behavior | Not run | No device access or ADB; preparation cannot verify these. |
| Gradle build, JVM, Android lint, instrumentation and broader host suites | Not run by this reviewer | Modules/targets inspected; this change is preparation-only. Parent's broader checks are separate evidence. |

No executed check failed or was infrastructure-blocked. Final hash verification
and the post-attestation seven-test rerun passed on the same freeze.

These are patterned synthetic English fixtures with authored question
decomposition, shared structural templates and a small fixed corpus. None of this
establishes deployment generalization, automatic decomposition, multilingual
accuracy, full-library capacity, privacy/offline behavior, latency or device
compatibility. Output-parser rejection behavior and native feasibility remain
later implementation gates; host checks cannot certify them.

Existing BUILD-001, MODEL-001, UI-001/UI-002 device-acceptance limits, AUDIO-001 and
AUDIO-002 remain governed by `planning/DEFERRED_ISSUES.md`; none is fixed or
release-cleared here. Approved screenshots are not re-evaluated because no UI
change is in scope. This PASS is not whole-product or release approval.

Next action: the parent can validate the preparation report/attestation and its
broader host checks. Any verifier implementation or native feasibility work needs
separate authorization under the frozen sequence. Preserve this snapshot and
review before any later correction; do not tune against exposed heldout material.
