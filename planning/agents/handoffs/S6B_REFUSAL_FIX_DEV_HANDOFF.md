# S6b dev handoff: refusal fix for source-scoped answers

2026-11-23. From the planner to the dev agent. This handoff is self-contained.

Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. Slice context:
[../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md), S6b. The S6 rubric in
[../../P2_S5_S6_DECISIONS_DRAFT_V1.md](../../P2_S5_S6_DECISIONS_DRAFT_V1.md),
Part 2, stays binding for every label in this slice.

**Authorization:** the user asked for this slice on 2026-11-23. **Round 1 is
host work only.** It builds a development set and a way to compare prompt
candidates on the S24. It does not change what the app sends in production.
Each S24 run needs its own go-ahead from the user, which the planner asks for
after QA. Production adoption of a winning prompt is **round 2**, and it
starts only after the user picks a candidate.

## Why this slice exists

S6 measured keyword-grounded answers on the S24 and missed both targets. See
`docs/P2_ANSWER_QUALITY_RESULTS.md`, last section.

- Supported 43/50 (target 45). All 7 misses were retrieval: the gold passage
  was not packed. **This slice does not address them.** Semantic recall is a
  separate P2 item.
- Abstention 15/20 (target 19). These misses were the model's behaviour, and
  this slice targets them.
  - **Neighbouring field:** asked for a detail the notes lack, the model gave
    a nearby detail of the same project instead, for example a different fee
    or a different budget.
  - **Near-miss entity:** asked about entity A, which lacks the detail, the
    model gave entity B's value as A's answer.
  - **Non-responsive:** the reply repeated packed text without answering or
    refusing.
- Both unanswerable tuning items showed the neighbouring-field substitution
  too. Greedy decoding gave byte-identical output across tuning×3.

Today's source-scoped instruction is the one-sentence addition in
`GroundedPrompt.SOURCES_INSTRUCTION` (`grounded-prompt-v1`). It is placed
before the passages, and the user's question follows the `@@@ END PASSAGES`
line.

### Contamination rule

The v1 held-out result stays as recorded and is never re-scored. Do **not**
open `planning/fixtures/answer-quality-v1/items.json`, the v1 held-out run
folders under `benchmarks/results/`, or the v1 label folders under `build/`
to draft dev items or candidate wording. Work from the failure **kinds** above.
You may read v1 **code** (scorer, harness, wrapper) and the v1 lock's schema to
generalize them. The v1 tuning items may be used for development (they are
never scored).

## Where things stand

- `dev` is at `ac00699` with a clean tree. S6 is committed in `baa8646`,
  `eddde09` and `ac00699`.
- The S6 instruments are hard-wired to v1:
  - `scripts/answer-quality.mjs`: `fixtureRoot`, `lockName`, the exact item
    counts, `lock.fixture === 'answer-quality-v1'`, and sets `tuning|heldout`;
  - `AnswerQualityFixture.kt`: the asset name `answer-quality-v1.lock.json`,
    `items.size == 92`, and sets `tuning|heldout`;
  - `AnswerQualityTest.kt`: `PROMPT_VERSION = "grounded-prompt-v1"`, the set
    and pass checks;
  - `scripts/verify-chat-context.sh` quality mode: the fixture path, the
    binding list, and `--set tuning|heldout`;
  - `androidApp/build.gradle.kts`: one `androidTest` asset `srcDir` for v1.
- Quality-run conditions to keep: S24 `SM-S928B`, `secureQa`, GPU 4096/512,
  greedy sampler, test-owned SQLCipher store and engine, supervised thermal,
  staged and hashed APKs, no automatic retries.
- Practical S24 preconditions (from S6): check `adb devices` with
  `~/Library/Android/sdk/platform-tools/adb` before a run, and start with about
  4.6 GB or more of free memory. Below that, the GPU 4096/512 load can be
  refused by the 3 GiB generation floor. The memory policy stays unchanged.

## Round 1, Part A: development fixture `answer-quality-dev-v1`

Create `planning/fixtures/answer-quality-dev-v1/`, with `library/`,
`items.json` and `answer-quality-dev-v1.lock.json`, using the v1 schema and
lock format. This new folder is explicitly authorized. Do not touch any other
fixture.

1. **Library.** 8 to 14 synthetic notes with the same realism as v1: routine
   log lines and side remarks around the facts. Invented entities only:
   - **Disjoint from v1:** the validator rejects any project, person or place
     name that appears anywhere in the v1 library or items (edit distance ≤ 2
     counts as the same name).
   - Projects share a field layout, so every near-miss question has a real
     in-library value for another entity.
   - Each note carries several **sibling fields** of one kind (for example
     three fees, two budgets, two suppliers), so missing-detail items have a
     close neighbour present.
2. **Items, exact counts:**

   | Kind | Count | Notes |
   | --- | ---: | --- |
   | Answerable, one passage | 10 | Five are **twins**: same note and field family as a missing-detail item, asking for a sibling that *is* present. |
   | Answerable, two passages | 4 | |
   | Unanswerable, missing detail with a sibling present | 10 | The asked field is absent; a sibling field of the same kind is present in a packed passage. The sibling's value is a forbidden fact. |
   | Unanswerable, near-miss entity | 8 | The asked entity is present and lacks the field; another entity's value is packed and is a forbidden fact. Include 3 about people. |
   | Unanswerable, off-topic | 2 | |
   | Diagnostic, bait | 3 | As v1: general-knowledge values are forbidden and absent from the library. |
   | Diagnostic, contradiction | 3 | As v1, including the whole-sentence and no-qualifier checks. |

   That is 14 answerable, 20 unanswerable and 6 diagnostic: 40 items. All run
   in `SOURCES` scope. There is no `ORDINARY` subset and no tuning subset.
3. **Retrieval is not under test here.** Answerable questions share enough
   keywords that the host retrieval estimate packs every gold passage. Every
   missing-detail and near-miss question packs its forbidden-fact passage.
   The validator enforces both with the existing probe logic. A miss fails
   validation; it is fixed by rewording, not by changing retrieval.
4. **Lock and freeze.** Same lock format as v1. The dev set is a
   development instrument: it is **never** scored against the 90/95 targets
   and never reported as a quality result. It freezes when QA passes and the
   user authorizes the first S24 run. After that, edits need
   `answer-quality-dev-v2`. Record the freeze in the results doc, not the lock.

## Round 1, Part B: prompt-candidate seam (shared code)

Add the smallest seam that lets the harness choose the source-scoped prompt
text without changing production behaviour.

1. A **public** immutable type in `core/conversation/session`, for example
   `SourcesPromptText(version: String, instruction: String, reminder: String?)`:
   - `instruction` replaces the source-scoped sentence that follows the shared
     ordinary text. The ordinary text, the `@@@` structure, delimiters and
     passage labels are not configurable.
   - An optional `reminder` is rendered on its own line between
     `@@@ END PASSAGES` and the user's message. Null renders exactly as today.
   - A companion `V1` holds today's text and `version = "grounded-prompt-v1"`.
   - Validation: no `@@@`, nothing `GroundedPrompt.usable` would reject, no
     line that `imitatesStructure`, and at most 600 UTF-8 bytes for
     instruction plus reminder.
2. Thread it as a constructor parameter with default `V1`:
   `ConversationSession` → `ConversationAssistant` → `GroundedPrompt.assemble`
   and `render`. `ORDINARY` scope ignores it. Koin and every production
   construction keep the default. Do not add a setting, flag or BuildConfig
   field.
3. Packing still uses `fits()` on the real rendered text, so a longer
   candidate can pack fewer passages. That is acceptable, but it must be
   visible: see Part C.
4. **Tests** (commonTest):
   - golden: with `V1`, `render` and `assemble` produce byte-identical
     requests to `ac00699` for both scopes, with and without history;
   - a reminder renders at the stated place, and `ORDINARY` ignores the type;
   - validation rejects each forbidden form;
   - a candidate long enough to drop the fourth passage records three
     consulted references, and the budget is never exceeded.

## Round 1, Part C: candidates, harness, wrapper and scorer

1. **Candidate file.** Create
   `planning/fixtures/grounded-prompt-candidates-r1/candidates.json` with a lock
   (SHA-256 of the file). It holds 3 to 5 candidates plus a `v1` baseline entry
   equal to `SourcesPromptText.V1`. Each entry has `id`, `version`,
   `instruction`, `reminder` and a one-line `rationale`. Plain prose only: no
   JSON output format, no tool calls, no constrained decoding. Families worth
   covering (your wording, judged against the dev set only):
   - **exact-match rule:** the answer must be the exact detail asked, about
     the exact person, project or item named. A sibling detail, or the same
     detail about someone else, is not the answer;
   - **recency:** the same rule repeated as a short `reminder` just before
     the question;
   - **fixed refusal:** when not covered, reply with one fixed sentence
     ("Your sources don't cover this.");
   - optionally a combination of the above.

   The planner reviews the candidate texts before the S24 run. A second file
   (`-r2`) is allowed once, only after the planner asks for it. There are at
   most two candidate rounds in this slice.
2. **Device harness.** Generalize `AnswerQualityTest` and
   `AnswerQualityFixture`:
   - a new set `dev` reads `answer-quality-dev-v1` (passes: 1 only);
   - a `candidates` argument names the candidate file's lock hash. The harness
     verifies the file and runs **one SOURCES block per candidate**, `v1` first
     and then file order, with one GPU load and release per block, as today's
     scope blocks do;
   - candidates are accepted for sets `dev` and `tuning` only. With `heldout`,
     or with any v1 held-out item, the run fails before loading the model;
   - each row records `candidateId`, the candidate version and its text
     SHA-256. The report records the candidate lock;
   - new invariant: in a `v1` block, the sent prompt equals what the
     production default would render for that item. This is the same check
     the golden test pins on the host;
   - the v1 `tuning` and `heldout` runs without candidates behave exactly as
     before, with the same argument checks and output schema plus additive
     fields only.
3. **Wrapper.** `verify-chat-context.sh quality` gains `--set dev` and
   `--candidates r1|r2`. Candidates are refused with `heldout`. The candidate
   file and dev lock are added to the source binding. The deadline formula
   counts each candidate as a scope block. Keep every existing check: staged
   APKs, binding recomputed after the build, thermal supervision, partial-row
   retention and the invariant failure reason.
4. **Scorer.** Generalize `scripts/answer-quality.mjs` to take the fixture by
   name, keeping v1's checks byte-for-byte for v1. `precheck` on a candidate
   run reports, **per candidate**:
   - answerable: required facts all present and no forbidden fact
     (literal aid, not a label);
   - unanswerable: forbidden-fact present (substitution), split by kind;
   - a refusal-phrase hit (aid only), and decode tokens;
   - packed passages per item, and **any item whose packing differs from the
     `v1` block**.

   Add `compare RUN_DIR`, a per-candidate table in Markdown and JSON. `report`
   accepts QA labels for a candidate run and gives per-candidate rubric counts.
   Neither command prints a 90/95 target or a pass for a dev or candidate run;
   the output says "development set, not a quality result".

## Selection rule (fixed before any run)

The planner applies this to **QA labels**, not literal aids, and recommends a
candidate. The user decides.

1. Eligible only if, on the 14 answerable items, Supported ≥ the `v1` block's
   count. No over-refusal is traded for abstention.
2. Among eligible candidates, the highest Abstained count on the 20
   unanswerable items wins.
3. Then fewer Wrong/Unsupported on bait, then more contradictions with the
   conflict noted, then the shorter text.
4. A winner must beat `v1` on Abstained by at least 3 of 20. Otherwise round 1
   reports "no candidate clears the bar", and the planner asks the user whether
   to try `-r2` or stop.

The v1 tuning set may be run with the same candidates as a cross-check
(10 items). Its result is informational and never overrides the rule.

## Round 2 (after the user picks a candidate): adoption

Separate go-ahead. In outline:
- set `SourcesPromptText.V1`'s successor as the production default, with
  `GroundedPrompt.VERSION = "grounded-prompt-v2"`, keeping `ORDINARY` unchanged;
- update the harness `PROMPT_VERSION`, the wrapper binding and the golden
  tests;
- run baseline host checks and an S24 SQLCipher/UI smoke run if the planner
  asks for one.

The scored re-measurement happens in **S6c**, not here. It uses a fresh
`answer-quality-v2` held-out set written by an agent that has not seen the
candidates or dev results, and it decides the rubric-v2 question of a
"non-responsive" label. Do not author v2 items in this slice.

## Allowed paths

- New: `planning/fixtures/answer-quality-dev-v1/**`,
  `planning/fixtures/grounded-prompt-candidates-r1/**` (and `-r2/**` only when
  the planner asks), `docs/P2_REFUSAL_FIX_RESULTS.md`.
- Shared: `GroundedPrompt.kt`, `ConversationSession.kt`,
  `ConversationAssistant.kt`, one new file for the prompt-text type if you
  prefer, and their commonTest tests (`GroundedPromptTest.kt` and a new test
  file if needed).
- App tests: `androidApp/src/androidTest/.../AnswerQualityTest.kt`,
  `.../support/AnswerQualityFixture.kt`, and `androidApp/build.gradle.kts`
  for the asset `srcDir` lines only.
- Tooling: `scripts/answer-quality.mjs`, `scripts/tests/answer-quality.test.mjs`,
  `scripts/verify-chat-context.sh`, `scripts/tests/verify-chat-context.test.mjs`.

Not allowed: `planning/fixtures/answer-quality-v1/**`, existing `docs/P2_*`
files (including `P2_ANSWER_QUALITY_RESULTS.md`), retrieval code
(`PassageIndex`, `PassageRules`, retriever ranking), `MAX_PASSAGES`, the
profile, sampler, memory policy, QA-only paths in `ModelManager` and
`LiteRtLlmEngine`, production DI, UI and strings. If the work cannot fit
these paths, stop and report.

## Checks before handback

- `node scripts/answer-quality.mjs validate` for v1 (unchanged result: 92
  items, 18 sources, 67 passages) and for `answer-quality-dev-v1`.
- `node --test scripts/tests/answer-quality.test.mjs scripts/tests/verify-chat-context.test.mjs`
  (and the supervision suite the S6 docs list), with nothing skipped.
  Fake-device cases: a dev×candidates pass, candidates refused with
  heldout, a wrong candidate lock, a changed candidate file during the build,
  and a packing-difference report.
- `./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`
  and the secureQa app plus instrumentation APK compile, as S6 did, with the
  dev fixture and candidate file packaged in the test APK only.
- `bash -n` on the wrapper and `git diff --check`.
- No device or emulator run.

## Results doc

Create `docs/P2_REFUSAL_FIX_RESULTS.md` with the dev fixture summary
(counts, entity disjointness evidence, host retrieval estimate per kind), the
candidate texts and rationales, the selection rule copied verbatim, host
checks, and an empty "S24 runs" section for the planner to fill. It claims
nothing about on-device behaviour.
