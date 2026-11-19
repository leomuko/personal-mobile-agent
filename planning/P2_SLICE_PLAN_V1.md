# P2 shorter route — slice plan v1 (revision 2)

2026-11-10; **revised 2026-11-11** after independent review
(`build/qa-shorter-route-2026-11-11-1/review.json`, ROUTE-F1 to F5 accepted)
and the user's acceptance of D1–D4. **No slice has started.** It implements
[P2_SHORTER_ROUTE_PROPOSAL_V1.md](P2_SHORTER_ROUTE_PROPOSAL_V1.md) with the
planner / dev / QA workflow in [agents/](agents/DEV_AGENT_BRIEF.md).

**Scope:** these slices deliver an interim milestone, M1 — grounded answers
over text Sources with keyword passage retrieval and consulted-passage
provenance. They do not complete P2. See "Remaining P2 scope" below.

## Workflow per slice

1. Planner (main session) issues the slice handoff.
2. Dev agent implements within the handoff's allowed paths.
3. QA agent reviews the working tree and runs the checks that fit the change.
4. Planner triages QA findings and sends in-scope ones back to dev. At most
   three dev/QA rounds, then the planner brings the disagreement to the user.
   Inside an approved slice there are no extra approval stops.
5. Planner reads the full diff. The planner reruns checks only when the tree
   changed after QA's verified snapshot, QA could not run a check, or a result
   looks doubtful. Unchanged, QA-verified results are not rerun.
6. User reviews. Commits happen only on the user's instruction.

One slice at a time. Dev is the only writer; QA and planner do not edit source.
Since 2026-11-18 the dev and QA agents may start their own helper agents under
the rules in their briefs (disjoint files for writers, read-only for QA, one
Gradle run at a time); the planner's independent QA round still applies.

## Checks proportionate to the change

| Change | Evidence |
| --- | --- |
| Python or Node host tooling only | That tooling's own tests. No Gradle run needed. |
| Shared/KMP or app code | `./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug` plus targeted tests; `:shared:testAndroidHostTest` when `androidMain` changes; `verifySqlDelightMigration` when the schema changes. |
| Storage, lifecycle or UI behaviour | The above plus emulator instrumentation on a designated emulator. |
| Native performance or answer quality | S24 measurement, with the user's go-ahead per run, synthetic inputs, raw results under `benchmarks/results/`. No automatic retries. |

The earlier freeze / lock / single-attempt ceremony applies only where the
user asks for it. A failing legacy host test is attributed from its actual
cause: it is called an archived-input failure only after showing that it fails
on a path under the folders archived on 2026-11-10.

## Decisions

| # | Status | Decision |
| --- | --- | --- |
| D1 | Accepted | Bake-off of keyword-only, small permissively licensed encoders and fusion, USE-QA as control; one-time public downloads after licence and artifact confirmation. |
| D2 | Accepted | Measure 4096 and 8192 on the S24; nothing above 2048 is the ordinary default before that. |
| D3 | Accepted, refined | Automatic retrieval on ordinary chat turns; no hit means plain chat. A separate, explicit, persisted source-scoped request state carries the "sources do not cover this" reply. |
| D4 | Accepted | QA-only paths stay untouched and pinned to 2048/512. |
| D5 | **Open** | How the user makes a request source-scoped (not in the approved screenshots). Needed before S5; S4 builds the state without UI. |

## Slices

### S3 — Passage retrieval, keyword (first)

- **Goal:** return ranked, prompt-sized passages with exact provenance.
- **Scope:** a retrieval unit smaller than today's 2048-character display
  chunks, indexed for full-text search through a SQLDelight migration;
  a shared retriever returning passage text with source ID, title, revision ID
  and offsets, ranked, bounded by count and bytes, and tagged with the context
  epoch it was read under. Natural-language questions must not require every
  word to match. Existing source-level search and the Sources screen are
  unchanged.
- **Contracts (second review, 2026-11-11):** retrieval work is bounded
  (paged scan, fixed candidate heap, bounded text fetch, 5-second deadline and
  cancellation checks) and an early stop is reported as an incomplete result;
  the result carries index coverage so "no passages" is distinguishable from
  "not indexed yet", with each revision published atomically; passages have
  their own count limit and capacity checks in import and backfill.
- **Later gate:** Android SQLCipher compatibility is checked on an emulator
  before S4 relies on the retriever on device.
- **Out of scope:** embeddings, prompt changes, UI, re-chunking stored
  display chunks.
- **Done when:** JVM tests cover ranking order, bounds, migration from the
  current schema with existing sources, backfill, delete and fence
  invalidation, rename, and hostile query text; migration verification passes.
- **Handoff:** [agents/handoffs/S3_DEV_HANDOFF.md](agents/handoffs/S3_DEV_HANDOFF.md).
- **Status (2026-11-16):** implemented with ranking follow-up; independent QA
  PASS; planner review accepted for host scope with one correction (query
  truncation counted filler words) carried into the S2a handoff as Part 1.
  Uncommitted. SQLCipher emulator gate still open.

### S2a — Ordinary-chat context profiles

- **Goal:** let ordinary chat run with a larger context without changing QA
  requests or the default.
- **Scope:** named ordinary-chat profiles (context, output reservation)
  supplied explicitly by the conversation path. `ChatRequest` defaults stay
  2048/512, because QA requests inherit them (`ModelManager` builds its
  verification request from the defaults). `ModelManager` records the loaded
  engine's backend, context and output identity and, when a request needs a
  different one, performs a confirmed release and reload under the existing
  ownership rules instead of reusing or rejecting. The Models panel load keeps
  its current configuration. The conservative byte bound stays; only its
  ceiling follows the profile. The larger profiles are selectable for QA
  builds only until S2b's measurement is accepted. Dev also reports whether
  LiteRT-LM 0.17.1 exposes a token counter (report only).
- **Out of scope:** V3/V4/constrained paths, memory policy, the 512 output
  reservation, any device run.
- **Handoff:** [agents/handoffs/S2A_DEV_HANDOFF.md](agents/handoffs/S2A_DEV_HANDOFF.md).
- **Done when:** tests cover QA-2048 → ordinary-4096/8192 → QA-2048
  transitions with confirmed cleanup between them, a preloaded engine of the
  wrong profile, cancellation during the reload, over-bound rejection, and
  unchanged QA behaviour.
- **Status (2026-11-18):** implemented with the S3 truncation correction;
  independent QA PASS in round 3 of 3 (`build/qa-s2a-2026-11-16/`), after
  fixes for S2a-F1 (panel prompts on an extended engine), S2a-F2 (stale idle
  release unloading a replacement) and S2a-F3 (idle release skipped during
  pending cancellation). Host scope only; 4096/8192 unmeasured until S2b.
  Carry into S2b: expose the loaded context size in runtime state, telemetry
  and the Models panel; memory admission does not yet scale with context.

### S2b-prep — Loaded-context observability and device test readiness

- **Goal:** make the device gates runnable and their results attributable.
- **Scope:** loaded context/output sizes in runtime state, memory telemetry and
  the Models panel (with a specific refusal message for S2a-F1); memory floors
  stay unchanged and are documented as uncalibrated for 4096/8192; the S2b
  measurement instrumentation test and the Android SQLCipher passage gate
  test, written and compiled but not run.
- **Handoff:** [agents/handoffs/DEVICE_PREP_DEV_HANDOFF.md](agents/handoffs/DEVICE_PREP_DEV_HANDOFF.md).

### S2b — S24 context measurement (device)

- **Goal:** choose the context size from measurement.
- **Scope:** for 4096 and 8192 on the S24 GPU path: cold load, first text,
  decode rate, peak PSS, thermal status and confirmed cleanup, with synthetic
  prompts near each bound, through the existing supervised QA harness.
- **Done when:** a results doc records the numbers and the planner recommends
  one value for the user to accept as the ordinary default.
- **Status (2026-11-18):** first pass run on the S24 (4096 and 8192, both
  passed) and the SQLCipher gate passed. Results and recommendation (4096,
  provisional) in `docs/P2_CONTEXT_MEASUREMENT_RESULTS.md`. Follow-up needed:
  2048 baseline, longer-answer decode case, repetitions.
- **Follow-up (2026-11-18):** user accepted 4096 as the provisional ordinary
  default (512 output; QA requests stay 2048/512; 8192 experimental).
  [S2b follow-up handoff](agents/handoffs/S2B_FOLLOWUP_DEV_HANDOFF.md)
  implemented and passed independent QA round 1 with P3 notes only:
  release BuildConfig has no automated test, report build type/baked size come
  from the test APK, summary flag nit, untracked-file provenance (fixed by
  committing before runs). Next: S24 runs 2048, 4096, 8192 with `--repeat 3`.

### S4 — Grounded prompt, request scope and consulted-passage record

- **Goal:** put retrieved passages into the request and remember which.
- **Scope:** a context assembler packing the current message, passages and
  recent turns within the active profile (passages are delimited, untrusted
  evidence, never instructions); the persisted request state from D3
  (ordinary or source-scoped) and its two behaviours; persistence of the
  passage references used by each assistant turn; existing fence rules for
  cancel, retry and source deletion during generation.
- **Out of scope:** UI, semantic ranking, model-written citations.
- **Done when:** shared tests cover packing and truncation, both no-hit
  behaviours, the source-scoped no-hit reply without loading the model,
  deterministic retry, delete-during-generation, and restart recovery of
  stored references and scope.
- **Depends on:** S3, S2a.
- **Handoff:** [agents/handoffs/S4_DEV_HANDOFF.md](agents/handoffs/S4_DEV_HANDOFF.md)
  (starts after S2b-prep; references-only provenance, typed `SOURCES` no-hit result).
- **Status (2026-11-19):** implemented on host; independent QA PASS round 1
  (`build/qa-s4-2026-11-19/`). Planner rulings during dev: packing keeps the
  most recent turn before passages and caps passages at 4 (4096: 4 passages
  with up to 2 turns); `PASSAGES_UNUSABLE` reason; retrieval skipped on an
  empty library; backfill limited to 250 ms per batch. Open P3s: lookalike
  delimiter lines in passages, a SOURCES turn with chat disabled records no
  result, temporary no-answer reasons block retry (S5 design), two test gaps.
  Not yet verified: migration 6 on Android SQLCipher (needs
  `GroundingSqlCipherTest` and an S24 run of `PassageSqlCipherTest` at v7).
- **Round 2 (2026-11-19):** a separate fresh review found S4-F5 (P2: process
  loss during retrieval left no attempt or Retry) and S4-F2; fixed by writing
  the PENDING attempt in the submit transaction and recording SOURCES results
  without the runtime; lookalike delimiters excluded; `GroundingSqlCipherTest`
  added (compile only). Fresh QA round 2 PASS with P3s: S4-F6 (a PENDING
  attempt can outlive a failed state refresh until restore), S4-F7 (Unicode
  space lookalikes; diff-hunk false positives), S4-F8 (the `OK (8 tests)` gate
  does not detect skipped opt-ins). S4-F3 remains an S5 design item.

### S5 — "Passages consulted" UI

- **Goal:** show provenance in both chat hosts and let the user scope a request.
- **Scope:** a compact row under grounded assistant messages; tapping a
  passage opens the source at that span; the D5 affordance; identical in the
  drawer and the full app; resource strings; wording says "consulted", never
  "verified" or "supported by".
- **Done when:** Compose tests cover present, absent and deleted-source
  states, large font and both hosts; emulator screenshots are attached.
- **Depends on:** S4, D5.

### S6 — Answer-quality check (device)

- **Goal:** measure whether plain-text grounded answers are reliable.
- **Scope:** synthetic sources and questions covering answerable,
  unanswerable, **relevant-passage-but-missing-detail**, contradictory
  passages and unsupported-answer cases, judged with a rubric the user
  approves. Built towards the plan's 50 answerable / 20 unanswerable set and
  its ≥90% supported / ≥95% abstention targets, reported honestly if missed.
  No JSON verification is reinstated.
- **Depends on:** S4, S2b.

### M1 — Interim milestone review

Acceptance of S2–S6 as delivered: keyword grounded answers over text Sources.
This is **not** P2 acceptance.

### S1 — Retrieval comparison (separate investigation, non-blocking)

- **Goal:** compare retrieval configurations; inform whether and how semantic
  ranking joins the retriever.
- **Scope:** host-only tooling under `scripts/retrieval_bakeoff/`. Scores
  BM25, USE-QA (control, from the retained vectors in
  `benchmarks/results/p2-semantic-gate-8SCUVh/`), the D1 candidates, and rank
  fusion. Each configuration reports ranking with no cutoff, ranking after its
  own calibrated cutoff, and false-match rate, with its ranking and
  calibration rule stated. The v1 fixture and `scripts/semantic-quality.mjs`
  are not modified. Results are exploratory.
- **Starting point:** on English held-out rows USE-QA already scores Recall@5
  1.00 / nDCG@10 0.963 with no cutoff and 0.45 / 0.45 with the calibrated one;
  the open question is answerability, not ranking.
- **When:** any time after S3; it touches only `scripts/`.

## Remaining P2 scope (not briefed yet)

| Item | Note |
| --- | --- |
| Explicit Memory | Remember, correct, forget, provenance and the memory editor. Memory is an empty state today. |
| Text-PDF ingestion | Import, extraction, recovery. Protected document storage prerequisites exist. |
| Semantic recall | Encoder integration, embedding storage and indexing, hybrid ranking; fresh held-out fixture (`semantic-retrieval-v2`) before any pass is claimed. |
| P2 acceptance | The consolidated campaign in the accepted P2 contract, unchanged, including the 50/20 quality targets. |

These become slices after M1, or earlier if the user reorders.

## Order

```text
S3 ──► S2a ──► S2b-prep ──► S4 ──► S5 ──► M1
                    ├──► SQLCipher gate (S24)  ──► S4 on device
                    └──► S2b (S24) ──► S6 ──┘     S1: independent, after S3
```

## Known risks

- `ModelManager` mixes ordinary chat with QA-only paths in one flow. S2a and
  S4 must change the ordinary path without altering the others.
- Today's display chunks are 2048 characters and are cut at fixed positions,
  which can split words. S3 introduces a separate retrieval unit rather than
  changing stored chunks.
- Legacy host suites may fail where they read folders archived on 2026-11-10.
  Not yet checked; attribute from evidence.
- Deferred items in `DEFERRED_ISSUES.md` stay deferred in every slice.
