# S2b follow-up dev handoff — 4096 default, run-binding fixes, fuller measurement

2026-11-18. From the planner to the dev agent. Self-contained. Read
[../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first; its boundaries, helper-agent
rules and handback format apply. Context:
[../../../docs/P2_CONTEXT_MEASUREMENT_RESULTS.md](../../../docs/P2_CONTEXT_MEASUREMENT_RESULTS.md).

**Authorization:** host work only. Device runs need the user's go-ahead, which
the planner requests after QA passes.

## Where things stand

- Branch `dev`, `HEAD` `b489d27` (plus uncommitted planner files: the results
  doc above, `planning/P2_SLICE_PLAN_V1.md`, this handoff; leave them alone).
- First S24 pass: 4096 and 8192 both completed; 8192 used about 0.45 GB more
  peak PSS with the same ~10 s load. SQLCipher gate passed.
- **User decision (2026-11-18):** 4096 is the provisional ordinary-chat
  default for the current Gemma/GPU setup on the S24. 8192 stays
  experimental (QA builds only). QA protocol requests stay 2048/512.
- An external review of `scripts/verify-chat-context.sh` found two defects
  (below). They do not invalidate the retained runs, whose reported profiles
  match, but must be fixed before further runs.

## Part A — 4096 ordinary default

- Ordinary chat uses `ChatContextProfile.EXTENDED_4096` in every build type
  (debug, release, secureQa default). `-PchatContextTokens` stays QA-only and
  accepts 2048/4096/8192, so 8192 remains reachable only in `secureQa`.
- Unchanged: `ChatRequest` defaults (2048/512); V3, V4, constrained decoding,
  foreground handoff and verification paths at 2048/512; the Models panel
  load and test prompts at the manifest 2048/512 (so a panel load followed by
  ordinary chat performs S2a's confirmed reload — keep that behaviour);
  memory policy values (still uncalibrated for 4096; say so in the note).
- Tests: the app's ordinary chat profile resolves to 4096 in debug and
  release; QA paths stay 2048/512; existing S2a tests still pass.

## Part B — bind runs to what was requested (review finding, P2)

1. The script hashes `androidApp/build/outputs/...` APKs before installing
   them, and those outputs can change between hash and install. Copy both
   APKs into a private run directory right after the build, verify their
   package IDs there, hash those copies, and install only those copies.
2. Result validation does not check that the device reported what was
   requested. Reject the run (failed, specific reason) unless the reported
   baked context and every case's resident `loadedContextTokens` equal the
   requested profile, `loadedOutputTokens` and reserved output equal 512,
   backend is GPU with no automatic fallback, the model SHA equals the
   manifest's, and `buildType` is `secureQa`.
3. Regressions (host, fake adb): APK bytes changed after the build are not
   what gets installed (the staged copy is), and a requested-4096 run whose
   report says 8192 (and each other mismatched field) fails.

## Part C — optional evidence markers (review finding, P3)

`pull()` (around `scripts/verify-chat-context.sh:95`) saves a missing-file
`cat` error as `supervision-stop.json`, because `adb exec-out` does not
reliably report the remote exit status. Check existence on the device first
(or equivalent), record an absent optional marker explicitly (for example in
`evidence.json`: `"supervisionStop": "absent"`), and treat a present but
non-JSON required file as malformed evidence (failed run). Regressions for
absent, present-valid and present-malformed.

## Part D — fuller measurement

- Accept `measure 2048|4096|8192` (2048 is the baseline; it uses the
  `STANDARD` profile in a `secureQa` build).
- Add a third case that produces a longer answer (synthetic prompt asking for
  a fixed-length list or passage, so output reaches at least 128 tokens) and
  report decode rate only from that case; mark the 6-token cases' decode rates
  as not meaningful in the report.
- Add `--repeat N` (1–5, default 3): N engine-cold iterations per case within
  one run, all recorded, with median and range in the report; the deadline
  scales with N (state the formula). No automatic retries: a failed iteration
  fails the run.
- Record user-visible cold-to-first-text alongside engine load, as today.

## Allowed paths

- `androidApp/build.gradle.kts`, `androidApp/src/main/kotlin/dev/edgecompanion/app/di/ModelModule.kt`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/di/SharedModule.kt` only if needed
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ChatContextMeasurementTest.kt`
- `androidApp/src/test/` and `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/` (tests)
- `scripts/verify-chat-context.sh`, `scripts/tests/verify-chat-context.test.mjs`
- `docs/P2_DEVICE_PREP_RESULTS.md` (procedure updates) and a dated section
  appended to `docs/P2_CONTEXT_MEASUREMENT_RESULTS.md`

## Out of scope

Memory policy calibration, S4, any device or emulator run, dependency changes,
changing `GemmaChatBudget` arithmetic, deferred issues.

## Checks to run

```sh
./gradlew --offline :androidApp:assembleDebug :shared:jvmTest --rerun :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest --rerun
./gradlew --offline :androidApp:generateReleaseBuildConfig   # confirm 4096 in release BuildConfig
./gradlew --offline -PtestBuildType=secureQa -PchatContextTokens=2048 :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest
node --test scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs
/bin/bash -n scripts/verify-chat-context.sh
```

One Gradle invocation at a time. After the node tests, no `SYNTHETIC` process
may remain. Never invoke real adb.

## Report back

Dev-brief handback format, plus the exact S24 commands for the next run set
(2048, 4096, 8192 with `--repeat 3`), the expected duration and deadline of
each, and confirmation that they install only staged `.secureqa` copies.
