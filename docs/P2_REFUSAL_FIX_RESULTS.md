# S6b round 1 refusal fix

2026-11-23. Host evidence only. This is a development set, not a quality result.

The second development round applies the independent QA findings to
`answer-quality-dev-v1` and `grounded-prompt-candidates-r1` before freeze.
The candidate run and any production adoption each require their own
authorization.

2026-11-23, development round 3. The Drossymanthel close note now describes
putting away the straightedge. The equipment repair question keeps its
location twin. Three leading questions now use direct wording, and the
near-miss aids also track packed values from the asked entity. The
candidate file and candidate lock retain their round-2 hashes.

## Development fixture

`answer-quality-dev-v1` contains 40 SOURCES items over 10 synthetic notes
and 20 indexed passages. Five one-passage questions are answerable twins.
Five near-misses name projects and three name people. Three missing-detail
items and their twins now cover dates, suppliers and locations. The other
two twins cover fees.

| Kind | Items | Any passage packed | Gold or distractor passages packed in both estimates |
|---|---:|---:|---|
| Answerable, one passage | 10 | 10 | 10/10 gold |
| Answerable, two passages | 4 | 4 | 4/4 with both gold |
| Missing detail | 10 | 10 | 10/10 sibling value |
| Near-miss entity | 8 | 8 | 8/8 with all listed distractors |
| Off-topic | 2 | 2 | No designated span |
| Bait | 3 | 3 | General-knowledge values absent |
| Contradiction | 3 | 3 | 3/3 with both conflicting spans |

The host estimate follows the S6 probe with lowercased keyword matching,
frequency weights censored at 64 rows, a boilerplate discount, passage-ID
tie-breaking, eight retrieval candidates, the 3,360-byte text budget and
up to four packed passages. It checks the baseline rendered prompt budget.
The archived QA probe ran unchanged. The two estimates agreed on the
packed passage count per item and on every gold and forbidden span check.
The capture records the actual packing separately for every candidate.

The names audit compared all 16 coined single-word names in the dev
notes and items against 1,177 v1 word spellings and
3,446 adjacent-word spellings. The smallest Levenshtein distance was
5. The development names are Brackenshivor, Cinderglasten,
Drossymanthel, Fulmarzeggant, Glimmerquastic, Kestrelmabrun, Nersivandrell,
Opmelgarrith, Quivvelglaston, Quobsentarrik, Rudfelmavorn, Splintorvetch,
Tallowdrisket, Varnockplenth, Wambreckloth and Yaxbrendeloth.

Fixture lock SHA-256 `f336017eb15850c7574c5281d0de9c95074f894997e408d901e813712c9fde02`.

Library SHA-256 `235b59651e706d7738ee4da4e08eb7a0164d674c686262f89188d1131cbe024a`.

Freeze pending independent QA and user authorization for the first S24 run.
Record the date and approved lock hash here. Later fixture revisions require
`answer-quality-dev-v2`.

## Review of substitution items

Round 3 rechecked all 18 questions against every passage in their estimated
packing. The review included routine remarks, where an informal sentence
can supply the requested detail. References to sweeping a floor, wiping
a table, late delivery and returning tools give no corresponding fee or
repair location. The three revised questions are direct wh-questions, and
all 20 unanswerable items prompt for a detail.

Both probes packed all 44 missing-detail distractors and all 37 near-miss
distractors, counting repetitions across items. The near-miss total
includes eight donor values, 28 same-entity fees and Opmelgarrith's route
badge code. Quobsentarrik's desk extension is a contact number, a separate
field from a route badge. The maximum normalized reuse is four. The
archived QA probe lists fees even for non-fee questions because its credit
check is independent of field family.

| Item | Asked field | Nearest present field | Why the value would substitute a different field |
|---|---|---|---|
| Missing detail 01 | Brackenshivor reservation fee | Storage fee | Reserving capacity and using storage are separate charges. |
| Missing detail 02 | Brackenshivor drain flushing date | Badge audit date | An audit appointment gives no date for drainage maintenance. |
| Missing detail 03 | Cinderglasten floor repair fee | Storage fee | The packed note about sweeping a floor names an activity. The storage charge covers holding materials. |
| Missing detail 04 | Cinderglasten label ink supplier | Clip supplier | The purchase of clips names a vendor for hardware. |
| Missing detail 05 | Drossymanthel insurance fee | Callout fee | Insurance covers risk and a callout charges for attendance. |
| Missing detail 06 | Drossymanthel equipment repair location | Intake and dispatch locations | The packed close note describes setting the roller aside for the morning crew and a spare by the window. Both recorded locations are logistics sites. Neither the register nor the close note provides a place for repairs. |
| Missing detail 07 | Kestrelmabrun cleaning fee | Intake fee | A note mentions wiping down the folding table. The quoted intake fee applies to receiving goods. |
| Missing detail 08 | Kestrelmabrun waste disposal fee | Storage fee | Removing waste and holding stock are separate services. |
| Missing detail 09 | Tallowdrisket badge replacement fee | Intake fee | Replacing a badge and receiving goods are separate transactions. |
| Missing detail 10 | Tallowdrisket late pickup fee | Courier fee | The courier entry gives the standard transport charge. The late-delivery remark concerns leaving space beside the folding screen. |
| Near miss 01 | Cinderglasten crating fee | Cinderglasten courier fee and Brackenshivor crating fee | Transport and packing are separate charges. The crating entry names Brackenshivor. |
| Near miss 02 | Opmelgarrith locker tag | Opmelgarrith route badge and Nersivandrell locker tag | The badge identifies a route. The locker tag belongs to Nersivandrell. |
| Near miss 03 | Drossymanthel lanyard fee | Drossymanthel meter seal fee and Cinderglasten lanyard fee | The seal and lanyard are different items. The lanyard charge names Cinderglasten. |
| Near miss 04 | Quobsentarrik route badge | Quobsentarrik desk extension and Opmelgarrith route badge | The extension directs calls. The route badge belongs to Opmelgarrith. |
| Near miss 05 | Kestrelmabrun meter seal fee | Kestrelmabrun rack rental fee and Drossymanthel meter seal fee | The rental and seal are different items. The seal charge names Drossymanthel. |
| Near miss 06 | Rudfelmavorn desk extension | Rudfelmavorn tally assignment and Quobsentarrik desk extension | The tally is a shift duty. The extension belongs to Quobsentarrik. |
| Near miss 07 | Tallowdrisket rack rental fee | Tallowdrisket tool locker fee and Kestrelmabrun rack rental fee | A locker and a rack are separate equipment. The rack rental names Kestrelmabrun. |
| Near miss 08 | Brackenshivor tool locker fee | Brackenshivor storage fee and Tallowdrisket tool locker fee | Storage gives a general charge. Only Tallowdrisket records a tool locker charge. |

## Round 1 prompt candidates

The shared ordinary instruction and passage delimiters come from
`GroundedPrompt`. Each instruction below is the full source-scoped addition.
The reminder, where present, follows the end-of-passages line.

Candidate file SHA-256 `6f2b79471ca3c93426f3ee51f7e607a72d1f5a82cf61895d93964eee0107bbd3`.

Candidate lock SHA-256 `8b2c6f64c283051692b139b666d77d00b94277ade80ed505c9025151d5c0cf28`.

| Candidate | Instruction | Reminder | Instruction and reminder bytes |
|---|---|---|---:|
| `v1` | Production wording | None | 105 |
| `exact-match` | Exact field and entity | None | 287 |
| `near-question` | Same as `exact-match` | Exact field and entity | 389 |
| `fixed-refusal` | Production evidence rule and fixed refusal | None | 133 |
| `exact-fixed` | Same as `exact-match` with only the last sentence replaced | None | 315 |

### v1

Version `grounded-prompt-v1`.

**Instruction**

> Answer only from these passages. If they do not contain the answer, say that the sources do not cover it.

**Reminder**

None.

Measure the current production wording against the same items.

### exact-match

Version `s6b-r1-exact-match`.

**Instruction**

> Answer only from these passages. Check that the passage states the exact detail asked for about the exact person, project or item named. A related detail or a value for a different entity does not answer the question. If the exact answer is missing, say that the sources do not cover it.

**Reminder**

None.

Makes both the requested field and the named entity explicit in the evidence check.

### near-question

Version `s6b-r1-near-question`.

**Instruction**

> Answer only from these passages. Check that the passage states the exact detail asked for about the exact person, project or item named. A related detail or a value for a different entity does not answer the question. If the exact answer is missing, say that the sources do not cover it.

**Reminder**

> Answer only when the passages give the exact detail for the exact person, project or item asked about.

Adds a brief restatement close to the question while holding the opening instruction constant.

### fixed-refusal

Version `s6b-r1-fixed-refusal`.

**Instruction**

> Answer only from these passages. If they do not contain the answer, reply with this sentence and stop: Your sources don't cover this.

**Reminder**

None.

Tests whether a fixed refusal improves abstention with the baseline evidence rule.

### exact-fixed

Version `s6b-r1-exact-fixed`.

**Instruction**

> Answer only from these passages. Check that the passage states the exact detail asked for about the exact person, project or item named. A related detail or a value for a different entity does not answer the question. If the exact answer is missing, reply with this sentence and stop: Your sources don't cover this.

**Reminder**

None.

Tests the exact-match rule and fixed refusal together.

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

## Round 2 host checks

- `node scripts/answer-quality.mjs validate` passed with 92 items, 18 sources
  and 67 passages. Its output matched the pre-slice validator byte for byte.
- `node scripts/answer-quality.mjs validate answer-quality-dev-v1` and
  `node build/qa-s6b-r1-2026-11-23/retrieval-probe-dev.mjs` passed with the
  counts above.
- `node --test scripts/tests/answer-quality.test.mjs scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs`
  passed all 178 tests with none skipped. The fake-device tests reject
  missing candidate and changed dev assets before any adb call. The
  scorer and supervision subset passed all 81 tests against the final lock.
- `./gradlew --offline --no-daemon :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`
  passed. JVM reported 294 tests, 292 passed, two existing opt-in tokenizer
  skips and zero failures. The new assistant test checks the runtime request
  in both scopes. Lint reported seven warnings and zero errors.
- `bash scripts/verify-chat-context.sh quality --set dev --candidates r1 --assets-only`
  built both secureQa APKs offline with the pinned Gradle daemon toolchain
  and `--no-daemon`. The wrapper verified 14 files in the real staged test
  APK byte for byte. The test APK also retained the 20 v1 assets. ZIP
  inspection confirmed zero dev or candidate assets in the staged
  secureQa app APK and in the debug app APK. The adb tripwire was unused.
- `bash -n scripts/verify-chat-context.sh` and `git diff --check` passed.

`--assets-only` runs the build, source binding, APK identity, hashes and
archive checks locally. Its result records `assetsOnly: true`. The
`precheck`, `compare` and `report` commands reject those results before
opening a transcript. The development fixture is linked into the test
source set from the neutral `android-test-assets` directory. Requested
candidate files are linked from their own round's directory.

For a captured candidate run, `compare RUN_DIR` writes `compare.json` and
`compare.md`. The rubric report accepts labels keyed by pass, scope, item ID
and candidate ID. A candidate text digest hashes the UTF-8 instruction,
one LF and the reminder if present. Refusal phrase hits remain literal
aids. A hedged reply can hit a refusal phrase and contain a forbidden value.

## Round 3 host checks

- Both named fixture validations, the unchanged QA retrieval probe,
  Bash syntax and the Git whitespace check passed. The v1 validation
  output matched the pre-slice output byte for byte.
- The final scorer and supervision subset passed all 83 tests. The focused
  wrapper subset passed all five tests, including the successful synthetic
  asset gate, the missing and modified asset cases, and refusal of the
  marked directory by all three transcript commands.
- The combined Node invocation exited 1 with 140 passed and 40 failed.
  Thirty-nine wrapper cases stopped at thermal readiness, where their fake
  heartbeat invokes `ps` and the sandbox denies process inspection. The
  remaining failure came from a reused test row in the refusal phrase
  examples. The final scorer subset includes that corrected case.
- The offline Gradle baseline and the real wrapper invocation with
  `--assets-only` both exited 1. The sandbox denied access to the Gradle
  wrapper cache lock before tasks could start. The wrapper recorded
  `assetsOnly: true` at the failed build stage. The JVM session regression,
  compile, lint and real staged APK inspection await an environment with
  Gradle cache access.

## Independent QA and planner review (2026-11-23, planner record)

Dev was Codex (GPT) for rounds 1–3. Each round was reviewed by a fresh Claude
QA agent:

| Round | Verdict | Report |
| --- | --- | --- |
| 1 | FAIL: S6b-F1 candidate confound, S6b-F2 ambiguous items; F3–F7 P3 | `build/qa-s6b-r1-2026-11-23/review.md` |
| 2 | FAIL: S6b-F8 item 06 answerable from packed text; F9–F11 P3 | `build/qa-s6b-r2-2026-11-23/review.md` |
| 3 | **PASS** (host scope); S6b-F12 P3 optional | `build/qa-s6b-r3-2026-11-23/review.md` |

QA ran the checks that Codex's sandbox blocked in round 3, all passing:
- the full Node suite, 180/180 with none skipped;
- `assembleDebug`, `jvmTest` with tests rerun (295 run, 0 failed, 2 existing
  opt-in skips; GroundedSessionTest 15/15, GroundedAssistantTest 14/14,
  GroundedPromptTest 16/16) and `lintDebug` (0 errors, 7 existing warnings);
- the secureQa assembly, and the `--assets-only` gate against the real staged
  test APK (14 assets, adb tripwire unused);
- its own unzip check: dev and candidate assets are in the test APK only.

Deferred: **S6b-F12**. The widened refusal aid also matches a reply that
states a value and adds a caveat. It affects the literal aid only; QA labels
decide selection.

Planner final review: the diff matches the three handoffs. Production keeps
`grounded-prompt-v1` byte for byte. Held-out runs refuse candidates in the
wrapper and the harness before any build or model load. The candidate set
changes one thing at a time.

Fixture lock at review: `f336017eb15850c7574c5281d0de9c95074f894997e408d901e813712c9fde02`.
Candidate lock: unchanged since round 2. The freeze is recorded here when the
user authorizes the first S24 run.

**Freeze record:** frozen 2026-11-23, when the user authorized the first S24
run without a commit. S6b is uncommitted at HEAD `ac00699`.
- `answer-quality-dev-v1` lock SHA-256
  `f336017eb15850c7574c5281d0de9c95074f894997e408d901e813712c9fde02`.
- `grounded-prompt-candidates-r1` lock SHA-256
  `8b2c6f64c283051692b139b666d77d00b94277ade80ed505c9025151d5c0cf28`
  (`candidates.json`
  `6f2b79471ca3c93426f3ee51f7e607a72d1f5a82cf61895d93964eee0107bbd3`).

From now on, a change to either needs `answer-quality-dev-v2` or
`grounded-prompt-candidates-r2`.

## S24 runs

### 2026-11-23: dev × r1, first run (planner record)

- Run: `benchmarks/results/answer-quality-dev-20261123T140548Z-62629-FOfkc1`.
- Conditions: S24 `R5CX22V8D6B`, GPU 4096/512, S6b uncommitted at `ac00699`,
  frozen locks as above.
- Wrapper result: `passed` at `complete`. 200/200 rows captured.
- Memory: the user asked to run below the ~4.6 GB guideline. The wrapper
  baseline was 3.93 GB. All five GPU loads were resident (15.1–24.8 s).
  Available memory was 4.38–5.91 GB before each load and 4.07–4.46 GB after.
- **Thermal stop in the last block.** Status was 0 at the `v1` load and 2
  (moderate) at every later load. In the `exact-fixed` block, item
  `unanswerable-near-miss-04` ended with error `THERMAL`. The engine was
  released, and the remaining 12 items of that block are `ENGINE_UNAVAILABLE`
  with no model call. The final thermal status was 3.
- The other four candidates (v1, exact-match, near-question and
  fixed-refusal) completed 40/40 with no errors.

Literal aids from `compare.md`. These are aids, not labels; selection uses QA
labels.

| Candidate | Literal answerable | Substitution, missing detail | Substitution, near miss |
| --- | ---: | ---: | ---: |
| v1 | 14/14 | 6/10 | 6/8 |
| exact-match | 14/14 | 6/10 | 6/8 |
| near-question | 13/14 | 6/10 | 6/8 |
| fixed-refusal | 13/14 | 5/10 | 6/8 |
| exact-fixed (incomplete) | 14/14 | 3/10 | 3/3 that ran (5 errored) |

`exact-fixed` is incomplete: 13 of its 40 rows are errors, including 7
unanswerable items. It cannot be compared or selected from this run.


### 2026-11-23 verification pair preparation

The r2 candidate file schedules 80 rows in two blocks, `v1` followed by
`exact-fixed`. Both entries match the frozen r1 entries byte for byte.
This pair uses the final candidate slot, r2. There is no r3, and the
experiment does not extend automatically.

| Artifact | SHA-256 |
| --- | --- |
| Frozen development fixture lock | `f336017eb15850c7574c5281d0de9c95074f894997e408d901e813712c9fde02` |
| Frozen r1 candidate lock | `8b2c6f64c283051692b139b666d77d00b94277ade80ed505c9025151d5c0cf28` |
| r2 candidate file | `5c9bfa865ccf21ea73a476e3cbcb87e743caf9317eb4d6d0103bebf77b2acd1e` |
| r2 candidate lock | `a27f9457a4e53092e25285b23df47a0f6771d628f6cc4e908cfa2b3e3fb26bb3` |
| `v1` text | `cfa0c54e1f1c0a35b138c2356145416091b155f9040c4c7358b8e3e67121f90b` |
| `exact-fixed` text | `2b9392670d5a0f9754b126ad990f22a730c5901ae6dc80c8c5fbcbe90f37e673` |

Both loaders verify the frozen r1 lock and entry fields for a declared
verification pair. The wrapper binds and snapshots the source manifest
and checks both source assets in the staged test APK.

The wrapper joins the owned thermal monitor with a 15-second shutdown
bound and then saves a final thermal reading. This final sample covers
the case where TERM interrupts the monitor before it writes the stop
marker and the device is still at or above either limit. Both measure
and quality parse thermal status and HAL SKIN temperature with the
monitor's rules and compare them against the app-exported limits.
A reading at or above either limit, a stop marker, or a quality capture
showing a thermal error fails as `thermal-stop`. An unavailable or
unparsable final reading fails as `thermal-final-unavailable`.
The wrapper checks the marker again before publishing success and
keeps the quality capture and `precheck` when available. Measure mode's
in-run safety-stop reason is now `thermal-stop` (previously
`thermal-monitor`).

Before each candidate load, the harness polls for thermal status 0 every
15 seconds with a 15-minute per-block bound. A wait that expires records
`thermal-cooldown-timeout` before that load. The capture records the
initial status, wait duration, sampled status at load, and available memory
before and after the load. The wrapper requires one ordered cooldown
record per recorded candidate load, a status of 0, a wait of at most
900,000 ms, and non-negative memory values. Missing or invalid evidence
fails as `quality-cooldown-evidence`. The wrapper reserves 900 additional
seconds per candidate block. The development pair has a formula deadline of 12,180
seconds.

The r1 run record above remains the historical capture. Its `exact-fixed`
block is incomplete. For the planned comparison, QA labels the four
complete r1 blocks and the r2 `exact-fixed` block under the existing
rubric and selection rule. The r2 `v1` block is the anchor. Use r1 blocks
in the comparison only if every r2 `v1` output is byte-identical to its r1
counterpart. If any differ, report the divergence and compare
`exact-fixed` solely with r2 `v1`.

Host checks confirmed both fixture validations, 84 scorer and supervision
tests, and ten focused wrapper tests. The focused tests covered a marker
after instrumentation and a marker during monitor shutdown in both
capture modes, a thermal error without a marker, a clean pair, a cooldown
precondition failure, two synthetic staged APK cases and Bash 3.2
compatibility. The full Node attempt ran 190 tests with none skipped.
Thirty-nine wrapper tests were blocked at thermal readiness by the
sandbox's `ps` restriction. The Bash guard failed once before its explicit
failure return was added. A UI case also hit a concurrent script edit.
Both passed on the final targeted reruns. The sandbox denied the
Gradle cache lock. The baseline and real `--assets-only` check
both stopped before Gradle tasks, so the Android loader regression and
real r2 APK packaging still need verification. No device run took place.

### 2026-11-23 verification pair, development fix round 2

The post-join reading addresses S6b-P1. The previously retained r1 capture
would be classified as `thermal-stop` under this gate, based on its
reported `THERMAL` row and stop marker. The historical capture remains
intact.

For S6b-P2, candidate runs select the Kotlin candidate-rule test in the
same instrumentation invocation as the capture and require two reported
tests. The rule test loads no model. For S6b-P3, the live wrapper gate
validates cooldown evidence before it writes a capture summary. This
keeps historical scorer inputs readable. S6b-P4 records the round limit
and the changed thermal gate above.

Both fixture validations passed byte for byte against the prior outputs.
The full Node suite ran 202 cases with none skipped. It reported 163
passes and 39 wrapper failures at thermal readiness because the sandbox
denied `ps`. The scorer and supervision subset passed all 85 cases, and
the focused wrapper subset passed all 22. The Gradle baseline and both
real staged-APK checks stopped at the sandbox's Gradle cache lock denial,
before a Gradle task started. No device run took place.

### 2026-11-23: verification-pair preparation, QA and planner review (planner record)

- Pair round 1: fresh QA FAIL (`build/qa-s6b-pair-2026-11-23/review.md`).
  S6b-P1 found that a severe reading could be lost when the monitor was joined.
- Pair round 2: fresh QA **PASS** on the host
  (`build/qa-s6b-pair-r2-2026-11-23/review.md`). S6b-P1 to P4 are resolved.
  QA ran every check that Codex's sandbox blocked: node 202/202, the Gradle
  baseline (jvmTest 295, 0 failed), and the r2 (16 assets) and r1 (14 assets)
  gates. The frozen inputs are unchanged.
- Planner review accepted. The final thermal reading is taken after the
  monitor is joined, on the same serial. It fails closed and gates both modes
  before `passed`.
- **Open, P3: S6b-P5.** A severe sample that the monitor takes but loses to
  the join signal is still missed if the device is just below both limits at
  the final reading, about 1.6 s later (QA probe). The risk is low but real at
  the S24's coincident limits (SKIN 45 °C, status 3). The decision rests with
  the user.

### 2026-11-23 S6b-P5 stop-intent fix (development)

The host monitor writes `safety-stop.json` with the run ID, the first
stop event, and pending delivery before the synchronous stop request. It
syncs the file and directory where supported, and publishes the record and
subsequent delivery outcome by same-directory atomic rename. A failed
delivery keeps the original event. If the intent write fails, the monitor
still attempts the stop and exits with a distinct failure status and log
message. TERM during the stop path leaves a failure exit status. The
wrapper's post-join reading and the monitor's thresholds, sample cadence,
and heartbeat behavior are unchanged.

Host regressions use the real monitor and a synthetic device command.
They cover TERM during a blocked request, below-limit final readings in
both wrapper modes, SKIN-only and unavailable-signal stops, delivery and
recording failures, and a clean pair. A signal can still interrupt the
monitor between the sample and the atomic write. The host record gives no
evidence that a device acted on the stop request.

The full host command ran 212 tests with none skipped. All ten new tests
passed, and the command reported 173 passes overall. The other 39 tests
were blocked at thermal readiness by the sandbox's `ps` restriction.
The wrapper syntax check, diff check, and frozen dev, r1, and r2 folder
hash comparisons passed.

### 2026-11-23: S6b-P5 stop-intent fix, QA and planner review (planner record)

Independent QA (`build/qa-s6b-monitor-2026-11-23/review.md`, fresh agent,
synthetic adb with a tripwire, host only): **PASS**.

- 212/212 node tests passed with none skipped, including the 39 tests the
  dev sandbox blocked. The monitor tests passed 8 runs in a row and the
  wrapper stop-intent tests 5 runs in a row.
- The earlier QA kill probe's two below-limit cases, which used to publish
  `passed`, now fail `thermal-stop`. The record keeps the first event.
- A mutant that writes the intent after the stop request fails 4/4 monitor
  tests and 5/6 wrapper stop-intent tests.
- Frozen inputs 16/16 and 6 symlinks unchanged; no Kotlin, Gradle or
  production change; consumers unchanged and consistent with the dev table.

Findings, all P3, **deferred** (none affects the verification-pair run):

| ID | Finding | Why it does not block the pair run |
|---|---|---|
| S6b-M1 | TERM is ignored for the whole stop path; only the 5 s adb timeout bounds it, and only if adb dies on SIGTERM. `verify-model-runtime.sh`, `verify-conversation-native.sh` and `run-p2-embedding-gate.mjs` never escalate past TERM. | The pair run uses `verify-chat-context.sh`, which kills the monitor after 15 s. Clean runs are unaffected. |
| S6b-M2 | Stale comment at `scripts/tests/verify-chat-context.test.mjs:334` (says the monitor has no handler during the stop request). | Comment only. |
| S6b-M3 | A folder fsync failure after a successful rename is logged as `thermal-stop-intent-write-failed` (exit 3) and the delivery outcome is lost. | Still fails safe; the pending record exists. |
| S6b-M4 | Pre-existing: two consumers check the marker without re-checking after joining the monitor. | Not used by the pair run. |

Planner review: confirmed the stop path in `scripts/monitor-model-thermal.mjs`
(pending record written atomically before `stopSupervisedQa`; delivery update
spreads the first event; exit 2 or 3) and the 5 s timeout in
`scripts/qa-supervision.mjs`. Frozen manifest re-checked: all OK. S6b-P5 is
closed for the demonstrated window around the blocking stop call; the window
between sample and intent write remains. Next step: the S24 verification-pair
run, on the user's go-ahead.

### 2026-11-23: verification pair (dev × r2), first attempts (planner record)

Authorized by the user. Neither attempt produced outputs; neither consumed
the r2 slot's comparison.

- `answer-quality-dev-20261123T184848Z-47646-S8gwHC`: failed at `build`
  before any device step. A long-running Gradle daemon could not clone the
  dev fixture assets into `androidApp/build` ("Operation not permitted");
  the planner shell could. The idle daemon was stopped with `./gradlew --stop`.
- `answer-quality-dev-20261123T185019Z-47899-SHgIt8`: failed at `validation`
  (`instrumentation`). The v1 block's load completed, but available memory
  after load was 2.83 GB, under the 3.0 GB generation floor, so
  `post-load-generation-admission` refused and the engine unloaded
  (`LOW_MEMORY`). Baseline 4.45 GB, thermal status 0, no safety stop.
  In run 1 the same load left 4.07–4.46 GB. The memory gate behaved as
  designed; no answers were generated.
- `answer-quality-dev-20261123T190702Z-49327-HhN5bZ`: **passed** at
  `complete` (exit 0) after the user freed memory on the phone. Both blocks
  ran resident, with 40/40 rows each and no errors or rows at the output cap.
  The cooling waits were 105 s (v1) and 180 s (exact-fixed), each starting at
  status 1 and loading at status 0, with 5.64 and 5.16 GB available after
  load. Instrumentation reported `OK (2 tests)`, which includes
  `AnswerQualityCandidateTest`, and the run had no safety stop.

**Determinism and comparison validity.** r2's `v1` block is byte-identical
to r1's `v1` block on all 40 items (same prompt hashes, answers and output
hashes), so cross-run comparison is valid. r2's `exact-fixed` matches r1's
on the 27 items r1 completed; the 13 items r1 lost to the thermal stop are
now complete.

Literal aids (not labels): answerable 14/14 for both. Substitutions:
missing-detail v1 6/10 vs exact-fixed 3/10; near-miss 6/8 vs 5/8;
off-topic 0/2 for both.

QA labelling is blind: 89 unique (item, reply) rows cover all 280 rows of
r1's five blocks and r2's two. The code map is held in planner-owned
`build/codex-dev/s6b/blind/`, and r1's 13 `exact-fixed` error rows are
labelled `Error`.

### 2026-11-23: blind QA labels and selection (planner record)

A fresh QA agent labelled the 89 blinded rows by hand against the S6 rubric
(`build/qa-s6b-pair-labels-2026-11-23/`, `LABELS_REPORT.md`). The planner
mapped the labels back with the private code map. `answer-quality.mjs report`
was run on both runs with those labels and wrote `report.json` and
`report.md` into each run folder. The scorer's counts match the planner's:

| Candidate (run) | Supported /14 | Abstained /20 | missing-detail /10 | near-miss /8 | off-topic /2 | Bait W/U /3 | Contradictions noted /3 |
|---|---:|---:|---:|---:|---:|---:|---:|
| v1 (r1 = r2) | 14 | 6 | 4 | 0 | 2 | 1 | 3 |
| exact-match (r1) | 14 | 7 | 4 | 1 | 2 | 1 | 3 |
| near-question (r1) | 13 | 7 | 4 | 1 | 2 | 1 | 3 |
| fixed-refusal (r1) | 13 | 7 | 4 | 1 | 2 | 1 | 3 |
| exact-fixed (r2, complete) | 14 | 11 | 7 | 2 | 2 | 2 | 3 |

Applying the selection rule (QA labels, rubric as written):
1. Eligible (Supported at least v1's 14): exact-match and exact-fixed.
   near-question and fixed-refusal lose one answerable item (answer-two-02;
   see below).
2. Highest Abstained: exact-fixed with 11.
3. Margin over v1: 11 − 6 = **5 of 20**, which clears the bar of 3.

**The rule selects `exact-fixed`.**

**Sensitivity (planner note).** Five rows were labelled Wrong rather than
Abstained under the rubric's "a forbidden fact appears" clause. Each reply
states a neighbouring value with correct attribution and then says the asked
detail is absent, for example "shows a crating fee of 53 credits, but it does
not mention a tool locker fee". Four of these rows are v1's and one is
exact-fixed's. If they were counted as Abstained, v1 would be 10/20 and
exact-fixed 12/20, a margin of 2, which would not clear the bar. The margin
therefore rests on the rubric's literal reading, which is the rule fixed
before the run. Whether mentioning a correctly attributed neighbouring value
should count as abstaining is the rubric-v2 question planned for S6c.

Other observations:
- Near-miss items remain the main failure for every candidate. exact-fixed
  abstains on 2/8.
- exact-fixed is one bait row worse: diagnostic-bait-02 replies with the
  premise sentence only, which is labelled Unsupported.

Fixture issues found by QA (the fixture is frozen; these are recorded for v2):
- near-miss-04's forbidden list omits 8146, the entity's own desk
  extension, so the literal precheck misses that substitution.
- answer-two-02 forbids the intake fee (182 credits), which the passages
  support. A reply that correctly adds it is labelled Wrong. This is why
  near-question and fixed-refusal score 13/14.

### 2026-11-24: independent decision audit and user decision (planner record)

Audit: `build/qa-s6b-selection-audit-2026-11-24/audit-kEZee0/audit.txt`
(fresh reviewer, read-only). Its verdict is **PASS** for the mapping, report
reproduction and mechanical selection. It is not an application, S6 quality,
release or default-promotion PASS.
- It verified all 280 mapping rows, the 89 unique replies and both scorer
  reports, which reproduce exactly.
- It confirmed that the cross-run identity anchor holds: v1 is identical
  40/40, and exact-fixed matches on all 27 rows r1 completed.
- All 32 unique replies that contain a listed forbidden fact are labelled
  Wrong consistently.

Its findings:
- **S6b-DA1 (P2):** the five Wrong labels follow the frozen rubric and are
  kept. Each reply attributes the neighbouring fact correctly and explicitly
  declines the requested detail. They are not hallucinations or field
  substitutions.
- **S6b-DA2 (P3):** two fixture defects, answer-two-02 and near-miss-04.
  Correcting them does not change the frozen winner.
- **S6b-DA3 (P3):** exact-fixed's extra bait failure (bait-02) is a
  non-response, not general-knowledge leakage. The current vocabulary
  describes non-responses poorly.

**Decision (user, 2026-11-24).**
- `exact-fixed` is the valid winner under the frozen selection rule
  (14/14 Supported, 11/20 against 6/20 Abstained, +5).
- **The app default is not changed.** `grounded-prompt-v1` stays the
  production `SOURCES` prompt. Counting correctly attributed explicit
  refusals as Abstained gives +2 (10 against 12), below the bar. exact-fixed
  also loses one bait refusal. A valid experimental selection and a decision
  to change the app default are separate judgments.
- `exact-fixed` is retained as the selected QA candidate. Promotion to the
  default waits for the S6c qualification under rubric v2. If promoted
  later, it becomes `grounded-prompt-v2` for `SOURCES` only, with
  `ORDINARY` unchanged and v1 kept for rollback.
- No further S6b round. The historical labels, locks, reports and the failed
  S6 result are preserved unchanged; nothing is relabelled retroactively.
- The two fixture defects are corrected in v2 fixtures only.
