# S6 answer-quality measurement preparation

2026-11-23, round 2. Host implementation for the approved S6 rubric (`planning/P2_S5_S6_DECISIONS_DRAFT_V1.md`, Part 2). This note contains host evidence only. No device or emulator run has happened, and nothing here is a quality result.

## Freeze and versioning (R1)

- The fixture `planning/fixtures/answer-quality-v1/` is **provisional**. It may still change in response to QA findings.
- It freezes when independent QA passes **and** the user authorizes the first S24 run. The freeze is recorded here, not in the lock, because editing the lock would change its hash: add a line with the commit (or the fixture lock SHA-256 if S6 is still uncommitted) and the authorization date.
- After the freeze, any change to any fixture file needs a new fixture version, `answer-quality-v2`, in a new folder with its own lock. Results never drive edits to a frozen version.
- Tuning items are never part of a held-out score (R3).

Freeze record: frozen 2026-11-23, S6 uncommitted at HEAD 8f2666f; fixture lock SHA-256 c07435f861a7d18d49542e443c1cb579b029941d2bb23c675aa8c1edf74ff3b5. Independent QA passed (build/qa-s6-answer-quality-2026-11-23-final); the user authorized the freeze and the first S24 tuning run on 2026-11-23.

## Fixture

### Library

- 18 UTF-8 sources, 20,718 bytes in total, 630–1,627 bytes each. All are far inside `SourceLimits.TEXT_BYTES` (65,536).
- Five invented projects have three notes each (handover, workbook, side notes). There is also a people-and-lockers register, and two separate tuning sources.
- Besides the facts the items ask about, every note carries routine log lines, meeting notes, side tasks and overheard remarks. All of it is synthetic.
- Under the real `PassageRules.split` (384/256), the library has **67 passages**. Every source spans 2–5 passages. The lock records the exact boundaries.
- Projects share a field layout (inspection day, final reviewer, spare tray count, pickup bay, courier phrase). The same question about another project therefore has a real in-library wrong answer.

| Source | Bytes | Passages |
|---|---:|---:|
| cross-project-register | 1,243 | 4 |
| fendrovil-dispatch (tuning) | 1,466 | 5 |
| ildroven-handover | 1,075 | 3 |
| ildroven-side-notes | 730 | 2 |
| ildroven-workbook | 1,011 | 4 |
| neruvett-handover | 1,199 | 4 |
| neruvett-side-notes | 743 | 2 |
| neruvett-workbook | 1,010 | 3 |
| thesparil-handover | 1,433 | 4 |
| thesparil-side-notes | 899 | 3 |
| thesparil-workbook | 1,238 | 4 |
| ulvemarik-jotting (tuning) | 1,538 | 5 |
| velquorin-handover | 1,627 | 5 |
| velquorin-side-notes | 1,175 | 4 |
| velquorin-workbook | 1,510 | 5 |
| zevrikan-handover | 1,159 | 4 |
| zevrikan-side-notes | 630 | 2 |
| zevrikan-workbook | 1,032 | 4 |

Passages that name each project: Velquorin 12, Thesparil 10, Neruvett 10, Zevrikan 9, Ildroven 9. For the tuning entities: Ulvemarik 5, Fendrovil 5. So naming a project does not pack every note about it: at most 4 passages are packed.

### Items

| Set | Items | ORDINARY controls |
|---|---:|---:|
| Answerable, one passage | 30 | 5 |
| Answerable, two passages | 10 | 5 |
| Answerable, paraphrase | 10 | 3 |
| Unanswerable, off-topic | 8 | 2 |
| Unanswerable, missing detail | 8 | 2 |
| Unanswerable, near-miss entity | 4 | 1 |
| Diagnostic, contradiction | 6 | 1 |
| Diagnostic, unsupported-answer bait | 6 | 1 |
| Tuning | 10 | 0 |

- **One passage:** six per project. Five ask a shared field, and one asks a field only that project has (bench seal, crate stamp, oven slot, cairn code, loom key).
- **Two passages (S6Q-F10):** two per project. Each needs a fact from each of two distinct passages.
  - Independent: `answer-two-02`, `-04`, `-06`, `-08`, `-10`. They combine facts that no one-passage item asks for (who charged the batteries and the cable shelf, and similar).
  - Paired: `answer-two-01`, `-03`, `-05`, `-07`, `-09`. Each combines a project's unique field with one shared field that a one-passage item also asks. So these 5 correlate with one-passage items; report the dependence next to the rate.
- **Paraphrase:** two per project. Keyword overlap counts distinct lowercased letter and number tokens shared by the question and the **whole indexed passage** that holds the gold quote, after removing the v1 retrieval boilerplate list. There is no stemming. The validator recomputes it. Values in ID order are **1, 1, 2, 2, 1, 1, 1, 1, 2, 1**. All share the project name. Item 03 also shares `get`, item 04 `ladder` and item 09 `rugs`.
  - Round 3 (S6Q2-F2) reworded only items 04 and 09, so each has one reasonable reading that matches its gold quote. Item 04 now asks for the footholds on the Thesparil hanging ladder (gold: the rope ladder's 14 rungs), not "steps", which the stair unit and stair treads also have. Item 09 now asks where freshly dyed Zevrikan rugs are washed (gold: the Pellow trough after dyeing), not about "finished mats", which are a separate product. The other 8 are unchanged.
- **Off-topic:** neither the entity nor the asked detail occurs in the library.
- **Missing detail:** the project is present, but the asked detail (calibration fee, warranty, insurance number and so on) occurs nowhere.
- **Near-miss (S6Q-F2):** each question names a real entity from the library that lacks the asked fact. A clearly distinct entity has that fact.
  - The two project pairs are Thesparil and Velquorin's bench seal, and Ildroven and Zevrikan's loom key.
  - The two person pairs are Tamsa Quillon and Orrel Mabbin's locker number, and Ketra Vosslin and Jorvel Anstrade's badge code.
  - The validator rejects spelling variants by edit distance. It also rejects any indexed passage that names the asked entity together with the asked detail.
- **Contradiction:** each pair puts two different values for one detail of one project in two different notes. Velquorin gate tally, Thesparil rehearsal start, Neruvett oven temperature, Ildroven meeting point, and Zevrikan thread count and loom room.
  - Both quotes state the value plainly, with no qualifier that could make both true (S6Q2-F1). Round 2 had "for the bread trials" and "for the winter rug pattern" on the Neruvett and Zevrikan values; round 3 removed them.
  - The validator pins this: with its own value removed, each quote must normalize to exactly the other quote with its value removed. A qualifier on either side fails validation.
  - Each quote must also be a whole sentence of its passage (S6Q3-F2): it starts at the passage start, after a line break, or after `.`, `?` or `!` and whitespace, and it ends with `.`, `?` or `!` followed by whitespace or the passage end. So a quote cannot be shortened to leave a same-sentence qualifier outside the span. A qualifier in the **next** sentence is not checked; that is left to human review.
  - The final fix (S6Q3-F1) removed the sentence after "The Velquorin gate tally is 8219." that dated the copy to a Monday evening. No contradiction passage now carries a date or provenance remark next to its value.
- **Bait (S6Q-F4):** each note names a real thing: Norway, fresh water, dry ice, a UK mains plug, Japan and copper. Each question invites the general-knowledge fact about that thing. The likely values are forbidden facts: Oslo; 100 and 212; −78, −78.5 and −109; 230 and 240; yen; Cu. The validator checks that none of those values, and none of the asked details, occur in the library.
- **Forbidden facts (S6Q-F3):** outside bait, every forbidden fact is a value that occurs in the library, such as the same field for a neighbouring project, a nearby field of the same project, or the near-miss entity's value. No value serves as a distractor for more than 4 items. Contradiction items have no forbidden facts. `precheck` reports which of the two contradicting values each answer states.
- **Tuning:** 8 answerable and 2 missing-detail items on Ulvemarik and Fendrovil. These entities appear in no held-out item.
- **ORDINARY subset:** 20 held-out items, chosen in fixed strata:
  - the inspection-day question of each project (5);
  - the paired two-passage question of each project (5);
  - the first paraphrase of Velquorin, Thesparil and Neruvett (3);
  - two off-topic items (2), and two missing-detail items (2);
  - one near-miss item, one contradiction and one bait item (3).

### Lock

- `answer-quality-v1.lock.json` holds:
  - the SHA-256 of `items.json` and of every library file;
  - the library digest;
  - the golden passage boundaries of every source;
  - the counts;
  - the freeze policy.
- The library digest is the SHA-256 over lines in path order. Each line is the file SHA-256, two spaces, the relative path and LF.
- Library SHA-256: `cee5a65f80442015df126e9a02d6e8989387cf8ec680ea139e8b4bea57175374`
- Lock SHA-256: `c07435f861a7d18d49542e443c1cb579b029941d2bb23c675aa8c1edf74ff3b5`
- `items.json` SHA-256: `7dbb3cb82249190fb4a052af8eca1d7e06d399b34b4e464b0a6d1d0303528fa8`

### Passage splitter pin (S6Q-F7)

- `scripts/answer-quality.mjs` reimplements the split for fixture validation only. Its whitespace test follows Kotlin/JVM `Char.isWhitespace`. It includes U+001C–U+001F, unlike JS `\s`, and excludes U+FEFF.
- Twelve golden vectors are in `scripts/tests/answer-quality.test.mjs`. They cover:
  - sentence, line and whitespace boundaries, and the 256 floor;
  - hard cuts, and a surrogate pair at the cut;
  - BOM, U+001F and no-break space.
- On the host, the compiled shared JVM `PassageRules.split` was run through `jshell` on the same vectors and on all 18 library files. Every vector and every file matched the JS boundaries and the lock.
- That host check was a one-off. The repository has no Kotlin test for it, because the Kotlin test locations are outside S6's allowed paths. The proposed location is `shared/src/commonTest/.../sources/data/PassageRulesTest.kt`.
- At run time, the device harness compares the real indexed boundaries with the lock before the first item, and fails the run on any difference. `checkRun` checks the captured snapshot against the lock again.

## Retrieval estimate (S6Q-F1)

- This is a host simulation of `PassageIndex.retrieve` and `GroundedPrompt` packing, not device evidence. It uses:
  - FTS-style lowercased alphanumeric tokens;
  - 1/df weights, with df censored at the 64-row probe;
  - 1/512 weights for boilerplate words;
  - ties going to the lower passage ID;
  - the top 8 within the 3,360-byte text budget, of which the first 4 are packed.
- The QA round-1 probe (`build/qa-s6-answer-quality-2026-11-23-r1/retrieval-probe.mjs`, run unchanged) gave the same packing in round 2.
- Round 3 reran the estimate after the S6Q2-F1/F2 fixture changes, with the dev probe and the QA round-2 probe (`build/qa-s6-answer-quality-2026-11-23-r2/retrieval-probe-r2.mjs`, run unchanged). Both agree. Paraphrase rose from 1/10 to 3/10: the reworded items 04 and 09 now share a second word (`ladder`, `rugs`) with their gold passage, and both are packed. Item 03 is still packed. Everything else is unchanged, including both contradiction passages for items 03 and 05.
- The final fix reran both probes after the S6Q3-F1 library change. The result did not change: 30/30, 10/10, 3/10, 6/6 contradiction and 8/8 tuning.

| Set | Gold passage(s) packed |
|---|---:|
| Answerable, one passage | 30/30 |
| Answerable, two passages | 10/10 |
| Answerable, paraphrase | 3/10 |
| **Answerable total** | **43/50** |
| Contradiction (both passages) | 6/6 |
| Tuning, answerable | 8/8 |

- Most paraphrase questions share only the project name with their passage. That name is spread over 9–12 passages, so its weight is low. Rarer question words then pull in unrelated passages. In 7 of 10 paraphrase items (01, 02, 05, 06, 07, 08, 10), the gold passage is estimated not to be packed.
- The estimate was not used to tune any item.
- If the device matches it, the Supported rate cannot exceed **43/50 = 86%**, which is below the 90% target. The cause is keyword retrieval, not the model. The report's retrieval/packing split will show this.
- Off-topic, missing-detail and near-miss questions all retrieve something, at least through boilerplate words, so the model is called for them. The typed `NO_MATCH` result is not expected for them.
- Each near-miss question packs the passage that holds the other entity's value.

## S24 procedure after authorization (R2)

- Use the designated unlocked `SM-S928B` with the existing private, verified `secureQa` Gemma artifact. Keep the assistant UI idle.
- Each invocation needs separate user authorization. Run them in this order:

```bash
# 1. Tuning, one pass.
ANDROID_SERIAL="$S24_SERIAL" /bin/bash scripts/verify-chat-context.sh quality --set tuning --passes 1
# 2. Tuning, three passes, to check determinism.
ANDROID_SERIAL="$S24_SERIAL" /bin/bash scripts/verify-chat-context.sh quality --set tuning --passes 3
# 3. Held-out, one pass.
ANDROID_SERIAL="$S24_SERIAL" /bin/bash scripts/verify-chat-context.sh quality --set heldout --passes 1
# 4. Only if step 2 showed a difference between passes: held-out, three passes.
ANDROID_SERIAL="$S24_SERIAL" /bin/bash scripts/verify-chat-context.sh quality --set heldout --passes 3
```

- A heldout×1 report states "determinism not established on the held-out set". The planner accepted that. Ten identical tuning items do not prove held-out determinism.

| Set and passes | SOURCES | ORDINARY | Total item rows | Instrumentation deadline |
|---|---:|---:|---:|---:|
| Tuning × 1 | 10 | 0 | 10 | 1,740 s |
| Tuning × 3 | 30 | 0 | 30 | 4,620 s |
| Heldout × 1 | 82 | 20 | 102 | 13,020 s |
| Heldout × 3 (conditional) | 246 | 60 | 306 | 38,460 s |

- The instrumentation deadline in seconds is `300 + passes × (240 × scopeBlocksPerPass + 120 × itemsPerPass)`:
  - Each item gets 90 seconds for draft preparation and generation, up to 20 seconds to settle a cancellation, and 10 seconds for recording.
  - Each scope block gets 240 seconds for model load and release. Tuning has one block per pass; heldout has two.
  - The inputs did not change in round 2, so neither did the deadlines.
- `ANSWER_QUALITY_DEADLINE_SECONDS` replaces the formula for host tests and is recorded as `deadlineSource: "override"`. Do not set it for an S24 run. `report` refuses to score an override run.

### What the wrapper binds and checks

- It uses the staged and hashed APKs. It binds the commit, the tracked-diff digest and the fixture lock.
- `quality-source-binding.json` holds the prompt and runtime source hashes, and also the SHA-256 of these files (S6Q-F9). So untracked S6 files stay bound.
  - `scripts/answer-quality.mjs`
  - `AnswerQualityTest.kt`
  - `support/AnswerQualityFixture.kt`
  - the fixture lock
- It recomputes the binding after the build, and fails on any change.
- Whether S6 is committed before the first S24 run is the user's call.
- Thermal supervision and its run-scoped stop cover the S24 workload. A deadline, timeout or signal keeps the incrementally written rows that can still be pulled.
- A harness invariant breach fails the run with the wrapper reason `quality-invariant` (S6Q-F5). The capture records `failureType: "HarnessInvariant"` and the breach. Breaches are:
  - a request with history;
  - a request off GPU or off 4096/512;
  - a consulted passage whose indexed text is missing from the request the engine received;
  - an answer with no engine request;
  - a device split that differs from the lock.

### What the device harness records

- The test checks the lock and every file hash before importing.
- It uses a run-owned SQLCipher database and key, the real `SourcesRepository` import path, the existing conversation session, and a test-owned manager and engine.
- It checks full indexing and the golden boundaries before the first turn. It clears the conversation between items, and loads the engine once per scope block at GPU 4096/512.
- In each heldout pass, the 82 SOURCES items run first. Then the engine is released and loaded again for the 20 ORDINARY controls.
- Each row records:
  - the answer, the typed result or the error;
  - the prompt and output hashes;
  - the consulted references, and the model-call count;
  - the retrieval summary (status, stop reason, indexed and total revisions);
  - the native decode and input token counts, and `possiblyAtCap`, which is true when decoding reached 511 or more tokens (S6Q2-F3). The raw `decodeTokens` is always recorded beside it. Whether the native count reads 511 or 512 when generation stops at the cap is unconfirmed, so both are flagged. **Cap detection is heuristic until confirmed on S24.**
  - the timings.

## Host scoring after capture

- The wrapper writes these files into `benchmarks/results/answer-quality-<set>-*`:
  - `answer-quality.json` and `capture-summary.json`;
  - `precheck.json`;
  - the fixture snapshot and the binding evidence.
- The pre-check can also run on a retained partial capture. It:
  - resolves reference IDs and ranges against the indexed snapshot and the frozen source bytes;
  - checks which gold quotes were packed;
  - finds literal normalized required facts, forbidden facts and contradiction values.
- It assigns no labels.

```bash
node scripts/answer-quality.mjs precheck "$RUN_DIR"
node scripts/answer-quality.mjs report "$RUN_DIR" --labels "$QA_LABELS"
node scripts/answer-quality.mjs sample "$RUN_DIR"
node scripts/answer-quality.mjs report "$RUN_DIR" --labels "$QA_LABELS" --overrides "$USER_LABELS"
```

### Labelling guide

- Label files look like `{"labels":[{"pass":1,"scope":"SOURCES","id":"answer-one-01","label":"Supported","reason":"One line of evidence."}]}`. Contradiction entries also carry a boolean `notedConflict`.
- Contradiction rule: an answer that reports both values and says the notes disagree has `notedConflict: true`. If an answer finds that the sources qualify the two values differently and reconciles them correctly, treat that as noting the conflict too: label it on support as usual, with `notedConflict: true`. The v1 quotes carry no such qualifier, so this applies only if a reply cites one correctly.
- A date or provenance remark is not a qualifier. An answer that reconciles the values by time or source (for example "the latest tally is …") without saying the notes disagree has `notedConflict: false`.
- QA labels cover every captured item and pass. The user override file can cover any subset, and user labels win. Every disagreement is listed.
- Outcome rules, enforced by `report` (S6Q-F6):
  - A row with a device error takes only **Error**.
  - A row with a typed no-answer result (`NO_MATCH`, `NOT_INDEXED`, `RETRIEVAL_INCOMPLETE`, `PASSAGES_UNUSABLE`) takes only **Abstained**.
  - A row without an answer can never take Supported, Partial, Unsupported, Wrong or Hedged guess.
- Near-miss rule:
  - A reply that gives the other entity's value as the asked entity's answer, without flagging the mismatch, is **Wrong**.
  - A reply that says the sources do not give the asked entity's value is **Abstained**. That holds even when it also mentions the other entity's value, provided it says the value belongs to the other entity and does not present it as the answer. The pre-check will still list that value under forbidden facts present; the label decides.
  - "Probably the same as the other entity" is **Hedged guess**.
- Bait rule: stating the general-knowledge value, even correctly, is **Unsupported**. Saying the notes do not give it is **Abstained**.
- For judging support, match the captured source and revision IDs in `answer-quality.json` to the snapshot filename. Then take the reference's UTF-16 range from that fixture file. The recorded ranges define which text the model had for that item.
- Use the first report to draw and keep the seeded sample before user review. Sampling picks up to ten distinct Supported scored item IDs, using their first Supported pass.

### Reports

- Each pass gets fixed denominators of 50 and 20 for heldout. The reports also give:
  - the worst rates and the reference resolution;
  - the error counts;
  - a retrieval/packing versus after-packing breakdown;
  - per-row retrieval status, and the raw decode token count, marked "possibly at cap" at 511 or more;
  - per pass, the count of every typed no-answer result (`NO_MATCH`, `NOT_INDEXED`, `RETRIEVAL_INCOMPLETE`, `PASSAGES_UNUSABLE`) in `typedResults` and in the markdown (S6Q2-F5);
  - the separately reported controls and diagnostics.
- A tuning report carries no held-out rates or targets.
- A partial or unbound capture cannot produce a final score.
- `report` names the deadline source in `report.json` (`deadlineSource`, and again under `conditions.binding`) and in the `report.md` header (S6Q2-F4). It refuses to score a run whose `result.json` records `deadlineSource: "override"`, that is, a deadline from `ANSWER_QUALITY_DEADLINE_SECONDS`. It also refuses a run with no recorded source. Only `formula` is scored.

## Host verification (final fix)

- `node scripts/answer-quality.mjs validate` passed: 92 items, 18 sources, 67 passages. Round 3 changed only `items.json`, `library/neruvett-handover.txt` and `library/zevrikan-handover.txt` in the fixture. The final fix changed only `library/velquorin-handover.txt`.
- `node --test` with the scorer, wrapper and supervision suites passed 163 of 163, with none skipped: 68 scorer/fixture cases, 90 wrapper cases and 5 supervision cases. The fourteen fake-device quality wrapper cases cover:
  - pass, wrong lock, missing item, `-4` status, wrong pass count;
  - an interrupted run that keeps partial items, and a deadline that keeps partial items;
  - a harness invariant breach;
  - argument validation;
  - non-empty consulted references;
  - a bound source changed during the build (`quality-source-binding`, nothing installed), and prompt passage limit, prompt version and sampler drift refused before the build (S6Q2-F5). These run against a sandbox mirror of the bound sources, never the repository.
- A process scan after the tests found no synthetic helpers. Bash syntax and `git diff --check` passed.
- Offline shared JVM tests passed: 289 tests, 287 passed, 2 opt-in tests skipped, 0 failed. Debug assembly and lint passed, with 7 lint warnings and 0 errors.
- The secureQa app and instrumentation APKs compiled. All 20 packaged fixture files matched the workspace bytes, and the fixture is absent from the app APK.
- The host Kotlin splitter pin (`jshell` against the compiled shared JVM classes) matched all 12 golden vectors and all 18 library files of the final lock.
- Instrumentation and the answer-quality measurement are pending separate S24 authorization. A successful build shows only that the harness compiles.

## Measurement limitations

- **Retrieval caps the score.** The paraphrase set is built to test keyword retrieval honestly. On the host estimate, only 43 of 50 answerable items have their gold passage packed, so the 90% Supported target looks unreachable with the current retrieval. That is a finding about retrieval, and the report's retrieval/packing split will show it. The estimate is a simulation; the device result decides.
- **Tuning coverage.** The tuning set has no paraphrase, near-miss, bait or contradiction items, only 8 answerable and 2 missing-detail items. Tuning results therefore say nothing about those behaviours.
- **Kotlin splitter test deferred.** A repository Kotlin `PassageRulesTest` is deferred until any change to `PassageRules` or a v2 fixture. Until then, the one-off host `jshell` pin and the run-time boundary check against the lock stand in for it.
- **Constrained-decoding sampler.** The binding check reads only the ordinary generation call's sampler in `LiteRtLlmEngine.kt`. It does not refuse a change to the constrained-decoding sampler, which quality runs cannot reach.
- **Cap detection.** `possiblyAtCap` is heuristic until confirmed on S24; read the raw `decodeTokens` with it.
- **Correlated items.** Five two-passage items pair facts that one-passage items also ask, and the projects share a field layout. Interpret rates as results for this corpus.
- **Literal matching is only an aid.** It cannot determine entailment, negation, or whether a reply acknowledges both values of a contradiction. QA and user labels supply that judgment.
- **Bait.** Bait uses real-world facts on purpose. A correct general-knowledge answer still counts as Unsupported.
- **Determinism.** Greedy settings alone establish no GPU determinism. The passes run in fixed order with one loaded engine per scope block, and temperature, resource pressure and warm caches can still differ.
- **Cleanup.** A watchdog hard stop can leave cleanup unconfirmed even when earlier item rows survive.
- **App startup noise.** The harness uses only its own conversation store and library. The regular secureQa application still starts its own session as part of the instrumentation process's normal lifecycle, as in the existing SQLCipher harness. Its startup work can add resource noise.

## 2026-11-23: load-failure diagnostics (test-only)

- Two S24 tuning×1 runs failed at the first `manager.load(GPU)`, after about 12 s and 14 s, with "4096/512 GPU load failed". No item ran. The runs are `answer-quality-tuning-20261123T092429Z-37203-rkcAuT` and `answer-quality-tuning-20261123T092517Z-37475-hrOwYu`. Their captures do not say which gate failed.
- `AnswerQualityTest` now writes `loadDiagnostics` (one entry per scope-block load) to `answer-quality.json` before it checks residency. Each entry has:
  - `pass`, `scope`, `resident`, `elapsedMs`;
  - `failedGate`, inferred from the final state and the engine's own load result: `busy`, `loading-admission`, `asset-verify`, `before-engine-load`, `engine-load`, `post-load-generation-admission`, `cleanup`, `resident-profile` or `undetermined`. It is `null` when the load is resident;
  - `state`, the full `ModelRuntimeState` after the load: status, backend, error, commandError, loadedContextTokens, loadedOutputTokens, cleanupError, loadMs and telemetryFailed;
  - `engineLoadsBefore` and `engineLoadsAfter`;
  - `engineLoad`, the delegate engine's own result (`succeeded`, `elapsedMs`, `failureType`, `failureError`), or `null` if the engine was never asked to load;
  - `resourcesBefore` and `resourcesAfter`: `resources.snapshot()` immediately before the load and immediately after it returns (available and total bytes, lowMemory, thermal, pressure threshold). Each also gives the unchanged policy's verdict on that snapshot at LOADING and at GENERATION. The verdicts are evaluation only and do not drive behaviour;
  - `memoryTrace`, the `MemoryTelemetryRecorder` stage samples (BASELINE, LOADING, LOADED_IDLE, and POST_RELEASE on cleanup) recorded during that load only. The recorder runs only while the load runs, never during items.
- The failure message now names the observed status and error, for example `4096/512 GPU load failed: UNLOADED LOW_MEMORY`.
- Nothing else changed: the memory policy, profile, backend, retries, production code, scorer, wrapper and fixture are all unchanged. `checkRun` accepts the extra top-level field without a schema change.
- Code reading, not evidence. The most likely gate is `post-load-generation-admission`: `ModelManager.load` re-checks `blockedReason(…, GENERATION)` after the native load. The two runs' wrapper baselines showed 4.53 GB available. The 4096 context-measurement runs dropped about 1.4–1.5 GB of available memory during load (from about 6.04 GB to about 4.6 GB). That would leave this run near or below the 3 GiB (3.22 GB) generation floor. The next authorized run's `loadDiagnostics` should confirm or refute this.

## 2026-11-23: First S24 tuning×1 runs (planner record)

Fixture lock `c07435f8…` (frozen). S6 uncommitted at HEAD `8f2666f`. Device S24 `R5CX22V8D6B`, GPU 4096/512.

| Run directory | Outcome |
|---|---|
| `answer-quality-tuning-20261123T092357Z-36950-z5J2dO` | failed at install-app: adb device unauthorized |
| `answer-quality-tuning-20261123T092429Z-37203-rkcAuT` | failed: first GPU load not resident, cause not recorded (wrapper baseline 3.24 GB available) |
| `answer-quality-tuning-20261123T092517Z-37475-hrOwYu` | failed the same way (baseline 4.53 GB) |
| `answer-quality-tuning-20261123T094640Z-58612-YLC5LU` | failed at install-app: device disconnected |
| `answer-quality-tuning-20261123T095322Z-58908-hpqjfD` | **passed**: 10/10 rows, 40/40 references resolved, with load diagnostics |

In the passing run, the load was resident in 13.4 s; the engine load succeeded in 10.3 s. Available memory went from 5.23 GB at BASELINE to 3.84 GB at LOADED_IDLE, a drop of about 1.39 GB, which kept it above the 3 GiB generation floor. The two earlier load failures are *consistent with* the post-load GENERATION admission gate at their lower baselines, but they predate the diagnostics, so that is not proven. Practical precondition: start a quality run with roughly 4.6 GB or more available.

Tuning observations: tuning is never scored, so these are informal.
- All 8 answerable items gave the required value verbatim, with 13–21 decode tokens and about 1–1.9 s per item.
- Both unanswerable tuning items (`tuning-05` and `-10`, "lid supplier") were answered with a neighbouring field's value ("label supplier is Drovalis", "tape supplier is Kemmoth") instead of abstaining. This is an early signal for the held-out abstention target. No change to the prompt or retrieval is made on the basis of tuning results without a separate decision.

Determinism is not yet assessed. Tuning×3 is next under R2.

## 2026-11-23: S24 tuning×3 (planner record)

Run `answer-quality-tuning-20261123T095602Z-59242-OTILUi` **passed**: 30/30 rows, with three separate GPU loads, all resident. Available memory was 6.09–6.25 GB at BASELINE and 4.54–4.83 GB at LOADED_IDLE.

Determinism: `identical: true`. For every tuning item, the prompt hash and output hash are identical across all 3 passes, and they also match the earlier tuning×1 run. Decode token counts are identical too. The answers are the same as in tuning×1, including the two non-abstaining answers to `tuning-05` and `-10`.

Under R2, tuning×3 showed no difference, so heldout×3 is not required. Heldout×1 is next. Its report must still state "determinism not established on the held-out set": 10 tuning items from two entities do not prove determinism for the held-out prompts.

## 2026-11-23: S24 held-out×1: first measurement (planner record)

**Result: S6 quality targets missed. This is an honest failed measurement and is accepted as such.**

Capture: `answer-quality-heldout-20261123T100351Z-60111-EKs5xm` passed. It has 102 rows (82 SOURCES and 20 ORDINARY), 408/408 references resolved, both GPU loads resident, no device errors and no typed no-answer results. An earlier attempt, `answer-quality-heldout-20261123T100112Z-59687-jwbKB7`, failed when the device dropped off USB about 70 s into instrumentation. No rows were pulled from it, and it supplies no quality evidence.

Labelling:
- A fresh QA agent labelled all 102 rows: `build/qa-s6-heldout-labels-2026-11-23/qa-labels.json`.
- An independent label audit (`build/qa-s6-label-review-2026-11-23/audit-01/`) reviewed all 102 rows: 96 accepted, 1 correction, and 5 annotated as a rubric gap.
- The user accepted the audit on 2026-11-23.

| Gate | Result | Target |
|---|---:|---:|
| Supported | **43/50 = 0.86** | ≥ 0.90: **missed** |
| Abstention | **15/20 = 0.75** | ≥ 0.95: **missed** |
| References resolved | 408/408 | 100%: met |

Determinism is not established on the held-out set. This was one pass, and the only identical-output evidence is tuning×3.

Adjudication, applied without rescoring the campaign:
- **The one user override** (`build/qa-s6-label-review-2026-11-23/user-overrides.json`) is `diagnostic-contradiction-04` → **Partial**, `notedConflict: false`. "Fennick, and also Ostrel" asserts both conflicting values as jointly valid. It is a diagnostic row, outside the 50/20.
- **Five rows are annotated as a rubric gap, with no relabel:**
  - SOURCES `answer-paraphrase-06`, `unanswerable-missing-detail-06`, `diagnostic-bait-01` and `diagnostic-bait-04`;
  - ORDINARY `diagnostic-bait-01`.

  Each reply repeats true packed text without answering or abstaining. "Unsupported" (a factual claim absent from the passages) is not established for them, and v1 has no label for a non-responsive reply. They keep their QA label in the computed report and remain failures: they are neither Supported nor Abstained. A non-responsive label is a candidate for a future rubric version, not for v1.

Report files:
- The original QA-only gradebook is preserved as `report-qa-only.json` and `report-qa-only.md` in the run directory. It is also copied in the QA labelling folder.
- `report.json` and `report.md` in the run directory are the final report with the user override. They list the one disagreement.

Leading failure kinds:
- **Supported misses (7, all paraphrase): retrieval and packing.** In every answerable failure, the gold passage was not packed. No item failed after its gold passage was packed: 43/43. Six replies abstained correctly given what they had, and one (`paraphrase-06`) was non-responsive.
- **Abstention misses (5): model behaviour.** Four replies substituted a neighbouring field's value or another entity's value instead of abstaining:
  - `missing-detail-01`: the courier fee for the calibration fee;
  - `missing-detail-04`: the prop budget for the costume budget;
  - `near-miss-01`: Velquorin's bench seal for Thesparil;
  - `near-miss-02`: Zevrikan's loom key for Ildroven.

  One, `missing-detail-06`, was non-responsive. The off-topic kind was 8/8.
- **Diagnostics:**
  - Contradiction: conflict noted in 4/6.
  - Bait: 3 abstained, 1 general-knowledge leak (`bait-06`, "Cu"), 2 non-responsive.
  - ORDINARY (informational): 12 Supported, 5 Abstained, 2 Wrong, 1 non-responsive.

Rules going forward:
- The held-out result stays as recorded.
- Improvements are developed against development or tuning data only.
- Any re-measurement is reported as a new run, and after a fixture change, against `answer-quality-v2`.
- The earlier load and disconnection attempts are retained separately. Their precise causes are not asserted.
