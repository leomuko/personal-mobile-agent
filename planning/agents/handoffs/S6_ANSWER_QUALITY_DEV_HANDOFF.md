# S6 dev handoff: answer-quality fixture, host scorer and S24 harness

2026-11-23. From the planner to the dev agent. This handoff is self-contained.

Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. **The approved rubric is binding:**
[../../P2_S5_S6_DECISIONS_DRAFT_V1.md](../../P2_S5_S6_DECISIONS_DRAFT_V1.md),
Part 2, approved by the user on 2026-11-19. Slice context:
[../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md), S6.

**Authorization:** the user asked for the next development step on 2026-11-23.
This slice is **host work only**. It builds the instruments: the fixture, the
host tooling and the device harness. It does not measure anything. Each S24
run needs a separate go-ahead from the user, which the planner asks for after
QA.

## Where things stand

- `dev` is at HEAD `8f2666f`, and the working tree is clean.
- S3, S2a, S2b, S4 and S5 are committed. S5 passed on the S24:
  - UI run: 27 of 27 tests and 40 PNGs.
  - SQLCipher gate: 9 of 9.
- **Grounding path**, built in S4:
  - `ConversationSession` and `ConversationController`, with `RequestScope.SOURCES`.
  - The prompt is `GroundedPrompt`, version `grounded-prompt-v1`, with `@@@` delimiters, `MAX_PASSAGES = 4`, and 4096/512 for ordinary chat.
  - When nothing usable is found, the result is a typed `SourcesNoAnswer`: `NO_MATCH`, `NOT_INDEXED`, `RETRIEVAL_INCOMPLETE` or `PASSAGES_UNUSABLE`. The model is not called.
  - The references to consulted passages are stored as `ConsultedPassage`s.
- **Decoding** for ordinary chat is greedy: `SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)` in `LiteRtLlmEngine`. Whether the GPU output is deterministic has **not** been shown.
- **Device-harness patterns to reuse rather than reinvent:**
  - `ChatContextMeasurementTest`: secureQa, S24-only checks, a test-owned engine and `ModelManager`, supervised thermal, a run ID, a JSON report in `noBackupFilesDir`, and no automatic retries.
  - `GroundingSqlCipherTest`: a **test-owned** SQLCipher database and key that never resolve the app's database or session, with cleanup.
  - `scripts/verify-chat-context.sh`: build, stage and hash the APKs, install only the staged copies, a deadline, owned-process cleanup, evidence collection and binding to a commit. Fake-adb node tests.
- **Fixture precedent:** `planning/fixtures/semantic-retrieval-v1.json` with a `.lock.json` that holds its sha256 and a freeze policy.

## Part A: the frozen synthetic fixture

Create `planning/fixtures/answer-quality-v1/`. This new folder is explicitly
authorized. Do not touch any other fixture.

1. **Library.** Synthetic plain-text sources, about 12 to 20, of realistic note
   length. Examples: "Synthetic project notes", a synthetic trip plan,
   synthetic meeting minutes, a synthetic recipe log, a synthetic household
   inventory.
   - Use invented people, projects, places, dates and numbers only. No real
     personal data, and no well-known facts the model could answer from
     general knowledge.
   - Include the near-miss entities that the near-miss items need: the same
     fact recorded about a different person or project.
   - Include the contradictory pairs that the contradiction diagnostics need.
   - Store the sources as files under `library/`, UTF-8 text with a title.
2. **Items.** Use `items.json`. The counts are **exact**, and they come from the rubric:

   | Set | Count |
   | --- | --- |
   | Answerable, one passage | 30 |
   | Answerable, two passages | 10 |
   | Answerable, paraphrased question | 10 |
   | Unanswerable, off-topic | 8 |
   | Unanswerable, relevant passage but missing detail | 8 |
   | Unanswerable, near-miss entity | 4 |
   | Diagnostic, contradictory passages | 6 |
   | Diagnostic, unsupported-answer bait | 6 |
   | Tuning set, kept separate from the held-out sets | about 10 |

   Each item has these fields:
   - `id`, `set` and `question`;
   - `requiredFacts`: a list of normalised strings, such as numbers, dates and names;
   - `forbiddenFacts`: plausible wrong values;
   - `answerSpans`: source file plus exact quoted text, or start and end offsets, for the passages that contain the answer;
   - `expected`: `answer`, `abstain` or `diagnostic`;
   - `ordinarySubset`: a flag. Exactly 20 held-out items carry it, and the choice is documented.
3. **Paraphrased items** must truly share few keywords with their answer
   passage. Record the shared non-stopword count for each item, and keep it
   low. This tests keyword retrieval honestly, so do not "help" it.
4. **Lock.** Add `answer-quality-v1.lock.json` with the sha256 of the library
   and of every file, the counts, and a freeze policy modelled on
   `semantic-retrieval-v1.lock.json`:
   - no changes in response to results;
   - the tuning items are never part of a held-out score.

   The fixture counts as frozen only after independent QA and the user's
   go-ahead for the first device run. Until then, it may still change in
   response to QA findings.

## Part B: host tooling (`scripts/answer-quality.mjs`) with node tests

Add these subcommands:

- **`validate`.** It checks:
  - the exact counts per set;
  - the schema;
  - that every required fact appears, after normalisation, in its answer
    spans;
  - that answer spans resolve exactly in the library;
  - that forbidden facts do not appear in an item's answer spans;
  - that for missing-detail and off-topic items, the asked detail appears
    nowhere in the library;
  - that for near-miss items, the asked entity–fact pair is absent, while the
    near-miss entity's fact is present;
  - that every contradiction pair really disagrees;
  - the ordinary subset count;
  - that the lock hashes match.
- **`precheck <run-dir>`.** This is the deterministic pre-check from the
  rubric. For each answer it records:
  - whether the typed no-answer result appeared;
  - which required and forbidden facts are present, using normalised string
    match on numbers, dates and names;
  - **reference resolution**: whether every consulted reference resolves to a
    live source, revision and span in the run's snapshot of the library. This
    must be 100%;
  - whether the answer-bearing passage was packed, so retrieval misses can be
    told apart from model misses.

  It writes `precheck.json`. It never assigns the final label.
- **`report <run-dir> --labels <labels.json> [--overrides <user.json>]`.** It:
  - applies the rubric's scoring rules exactly: Supported ÷ 50, Abstained ÷
    20, broken down by the three unanswerable kinds, plus the diagnostics;
  - counts Partial as not supported, and Hedged guess as not abstained;
  - counts Error as a failure;
  - lists Abstained answers on answerable items separately;
  - lets the user's overrides win, and lists every disagreement;
  - writes `report.json` and `report.md` with the per-item table, the
    retrieval-vs-model split and the run conditions.

  A missed target is stated plainly.
- **`sample <run-dir>`.** It picks the 10 random Supported items for the
  user's review, using a recorded seed.
- **Tests.** Put them in `scripts/tests/answer-quality.test.mjs`. Use node's
  built-in test runner only, with no new dependencies. Cover:
  - each validator rule, with a failing case for each;
  - normalisation, including numbers, dates, case and punctuation;
  - each rubric label's effect on the rates;
  - user overrides winning;
  - a missed target being reported;
  - an Error being counted, not excluded;
  - the reference-resolution failure path.

## Part C: device harness (code only, not run)

1. **`AnswerQualityTest`** goes in `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/`.
   It mirrors the guards in `ChatContextMeasurementTest`:
   - `secureQa` only, the S24 only, the phone unlocked;
   - an opt-in argument (`answerQuality=true`) plus a run ID;
   - supervised thermal;
   - no automatic retries;
   - the profile fixed at **4096/512** with the GPU backend, and no override
     through arguments.

   Then:
   - Ship the fixture as androidTest assets, or push it with the script.
     Verify its sha256 against the lock before running; a mismatch fails the
     run.
   - Use a **test-owned** encrypted database, key and conversation store, as
     `GroundingSqlCipherTest` does. Never touch the app's database or session.
     Delete everything the test created at the end, and verify that the
     deletion happened.
   - Import the library through the real `SourcesRepository` import path, and
     wait until indexing is complete. A not-indexed library fails the run; it
     is not scored.
   - For each item, use a **fresh conversation** and the real
     `ConversationSession` path in **`SOURCES` scope**: real retrieval, the real
     `GroundedPrompt` and the real `ModelManager.generateChat`.
   - Run the `ORDINARY` subset as a separate, informational pass.
   - Record, for each item:
     - the answer text;
     - the typed result;
     - the consulted references;
     - the IDs of the packed passages;
     - the prompt version;
     - the profile;
     - the sampler settings;
     - the timings;
     - the output sha256;
     - any error.
   - Write the report incrementally, so a stopped run keeps every completed
     item.
   - Load the engine once per pass. Record a cleanup that is confirmed at the
     end.
2. **Script mode** in `scripts/verify-chat-context.sh`:

   ```
   quality --set tuning|heldout [--passes 1|3]
   ```

   - It reuses the existing build, stage, install, deadline, cleanup and
     binding path unchanged.
   - `heldout` runs the 70 scored items plus the 12 diagnostics in SOURCES
     scope, then the ORDINARY subset.
   - `tuning` runs only the tuning items.
   - **`--passes 3`** exists so that determinism can be checked, as the rubric
     requires. Each pass records its output hashes. The report states whether
     the passes were identical. The script never chooses the pass count on its
     own.
   - Choose a deadline formula from the item count and passes, and state it.
   - It pulls the report into `benchmarks/results/answer-quality-<set>-*`,
     records the fixture lock hash and binds to the commit.
   - Add fake-adb node tests: a pass, a fixture hash mismatch, a missing item,
     a `-4` status, an interrupted run that keeps partial items, and a passes
     count mismatch.

   The existing `measure`, `sqlcipher` and `ui` tests must stay unchanged and
   passing.

## Out of scope

- **Any prompt or retrieval change.** Tuning the instruction wording is a
  later, separate step, using the tuning set only.
- Running anything on a device or emulator.
- Labelling answers. A fresh QA agent and the user do that after a run.
- Using the on-device model as a judge. The rubric forbids it.
- New dependencies.
- Deferred issues.
- Memory, PDF and semantic recall.

## Allowed paths

- `planning/fixtures/answer-quality-v1/`: new
- `scripts/answer-quality.mjs` and `scripts/tests/answer-quality.test.mjs`: new
- `scripts/verify-chat-context.sh` and `scripts/tests/verify-chat-context.test.mjs`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/AnswerQualityTest.kt`,
  plus helpers under `androidTest/.../support/`, and androidTest assets if you
  use them
- `androidApp/build.gradle.kts`, **only** if androidTest assets need a source
  set entry. State the exact change and do not add dependencies.
- `docs/P2_ANSWER_QUALITY_RESULTS.md`: new. It records the fixture design,
  the tooling, the procedure and the host check results. It contains no
  device results.

If production code must change to make the harness possible, for example a
missing seam to get the packed passage IDs, **stop and report** with the
smallest change you propose. Do not make it yourself.

## Checks to run

Run one Gradle invocation at a time.

```sh
node scripts/answer-quality.mjs validate
node --test scripts/tests/answer-quality.test.mjs scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs
/bin/bash -n scripts/verify-chat-context.sh
./gradlew --offline :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline --rerun-tasks -PtestBuildType=secureQa :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest
```

- After the node tests, no `SYNTHETIC` process may remain.
- Never invoke real adb.

## Report back

Use the dev-brief handback format, plus:
- the fixture summary: counts per set, the library size, how paraphrase
  keyword overlap was measured and its values, the ordinary-subset choice, and
  the lock hashes;
- the exact S24 commands for `quality --set tuning` and `--set heldout`, with
  their deadlines and expected item counts;
- any production seam you needed and did not add;
- the risks to an honest measurement that you see. Examples: items that
  general knowledge could answer, items where keyword retrieval cannot
  succeed by design, or nondeterministic GPU output.
