# S6b verification-pair run preparation (dev)

2026-11-23. From the planner to the dev agent (Codex). The parent handoff,
[S6B_REFUSAL_FIX_DEV_HANDOFF.md](S6B_REFUSAL_FIX_DEV_HANDOFF.md), still
applies: allowed paths (plus those named here), boundaries, contamination
rule, and the selection rule. Host work only: no device or emulator run, and
adb must never be invoked.

## Why

The first S24 run, dev × r1
(`benchmarks/results/answer-quality-dev-20261123T140548Z-62629-FOfkc1`),
captured 200 rows. In the last block, `exact-fixed`, a `THERMAL` error at
`unanswerable-near-miss-04` was followed by 12 `ENGINE_UNAVAILABLE` rows.
The other four candidates completed 40/40. `exact-fixed` is the only
candidate that clearly moved the literal aids, so it needs a complete run.

The user chose a short **verification pair**: `v1` and `exact-fixed` only,
run as a frozen r2 file. A user-requested review
(`build/qa-s6b-partial-run-2026-11-23/audit-tj0yfzif/review.txt`) found two
blockers, both confirmed by the planner:

1. **Late thermal stop escapes validation.** The host thermal monitor wrote
   `thermal/safety-stop.json` (status 3) at 14:18:22.062Z. That is the same
   second as the final `[[ ! -f "$THERMAL_REPORT_DIR/safety-stop.json" ]]`
   check (`scripts/verify-chat-context.sh`, quality branch near line 808), and
   the wrapper published `passed`. In addition, a row with error `THERMAL`
   does not fail the run.
2. **Two candidates are invalid today.** `loadCandidates` in
   `scripts/answer-quality.mjs` (about line 504) and `AnswerQualityFixture.kt`
   (about line 106) both require 4–6 candidates.

## Part A: thermal finalization (wrapper)

- After instrumentation exits, **stop the thermal monitor and wait for it to
  exit** before the final checks, with a bounded wait. Only then evaluate
  `safety-stop.json`. Re-check it as the last step before writing
  `STATUS=passed`. Apply this to `measure` and `quality` alike; do not change
  thresholds or the monitor's own logic.
- In `quality` mode, any row whose error is `THERMAL`, or a run whose capture
  shows a thermal stop, fails with a distinct reason (for example
  `thermal-stop`). Rows and evidence are kept, and `precheck` still runs on the
  retained capture so partial evidence remains inspectable. The run is never
  `passed`.
- Host regressions with the fake device:
  - the monitor writes the stop file after instrumentation exits but before
    finalization;
  - the monitor writes it during finalization (the race);
  - a capture with a `THERMAL` row and no stop file.

  Each must end not `passed`, with the distinct reason. A clean run still
  passes.

## Part B: a two-entry verification pair

- Allow **exactly two** entries only when all of these hold. Otherwise the
  4–6 rule stays.
  - The document declares `"purpose": "verification-pair"` and
    `"sourceRound": "r1"`.
  - Entry 1 is `v1`.
  - Entry 2's `id`, `version`, `instruction`, `reminder` and `rationale` are
    byte-identical to one entry of the frozen source round's file.
  - The source round's lock verifies.
- Enforce this in **both** loaders, the JS scorer and the Kotlin fixture, with
  tests: the valid pair; an edited text; a wrong source round; three entries;
  a missing purpose; `v1` not first.
- Create `planning/fixtures/grounded-prompt-candidates-r2/`, which is
  authorized:
  - `candidates.json` with `v1` and `exact-fixed` copied byte for byte from r1;
  - `candidates.lock.json`;
  - `candidateAssets/` links in the same form as r1.

  Do not modify anything in `grounded-prompt-candidates-r1/` or
  `answer-quality-dev-v1/`.
- The r2 slot is used by this pair. `build.gradle.kts` stays `r1|r2`. Do not
  add r3 or any way to extend the experiment.

## Part C: comparable cooled starting conditions

- Before each candidate block's GPU load, the harness waits for the device's
  thermal status to be **0 (none)**. It reads the same thermal signal the
  harness already records.
  - The wait is bounded: up to 15 minutes per block, polling every 15 s,
    with no model loaded.
  - If 0 is not reached, the run fails before that load with a distinct
    precondition reason. It is not an item error.
- Record per block: the thermal status at the start of the wait, the wait
  duration, the status at load, and the available memory before and after the
  load.
- The wrapper's deadline formula adds the maximum cooldown wait per block.
- The memory policy, thermal thresholds, profile, sampler and retry policy
  are unchanged. This adds a precondition; it does not relax any safeguard.

## Part D: records

- In `docs/P2_REFUSAL_FIX_RESULTS.md`, add a dated section covering:
  - the r2 verification pair, its hashes, the r2-slot consumption, and that
    there is no r3;
  - the two fixes;
  - the planned comparison: candidates are compared on QA labels, using r1's
    four complete blocks and r2's `exact-fixed`. r2's `v1` block is the
    anchor. Cross-run comparison is valid only if r2's `v1` outputs are
    byte-identical to r1's `v1` outputs item for item. If they are not,
    `exact-fixed` is compared only with r2's `v1`, and the difference is
    reported;
  - the r1 run is preserved unchanged and marked incomplete for
    `exact-fixed`.
- Do not edit any other results doc or any `benchmarks/results/` folder.

## Allowed paths for this round

The parent's allowed paths, plus `planning/fixtures/grounded-prompt-candidates-r2/**`.
The likely touch set is `scripts/verify-chat-context.sh`,
`scripts/tests/verify-chat-context.test.mjs`, `scripts/answer-quality.mjs`,
`scripts/tests/answer-quality.test.mjs`, `AnswerQualityTest.kt`,
`support/AnswerQualityFixture.kt`, `docs/P2_REFUSAL_FIX_RESULTS.md` and the
new r2 folder. Not allowed: production code, `GroundedPrompt.kt`, the memory
policy, thermal monitor thresholds, and retrieval.

## Checks before handback

- `node scripts/answer-quality.mjs validate` (v1, output unchanged) and
  `validate answer-quality-dev-v1`.
- `node --test` on the three suites, with nothing skipped. If the sandbox
  blocks `ps` or Gradle, report the check as **blocked**. Do not work around
  it; QA will run it.
- `./gradlew --offline --no-daemon :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`.
- `bash scripts/verify-chat-context.sh quality --set dev --candidates r2 --assets-only`.
- `bash -n` and `git diff --check`.
