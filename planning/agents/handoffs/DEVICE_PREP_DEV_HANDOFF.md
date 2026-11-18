# Device-prep dev handoff — loaded-context observability and device test readiness

2026-11-18. From the planner to the dev agent. Self-contained. Read
[../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first; its boundaries, helper-agent
rules and handback format apply. Slice plan:
[../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md), "S2b-prep".

**Authorization:** host work only. This handoff authorizes writing and
compiling device tests, never running them. Device runs are on the S24 only (no
emulator for now) and each needs the user's separate go-ahead, which the
planner requests.

## Where things stand

- Branch `dev`, `HEAD` `9edb6af`, clean tree. S3 (passage retrieval) and S2a
  (ordinary-chat context profiles) are committed and host-tested.
- `ModelManager` records the loaded engine's `ModelConfig` (`loadedConfig`), but
  nothing outside it can see which context size is loaded:
  `ModelRuntimeState` has no context or output fields, `MemoryTracePoint` has
  none, and the Models panel prints the manifest values
  (`ModelCatalog.gemma4.configuredContextTokens`) whatever is loaded.
- Larger profiles (4096, 8192) are reachable only in `secureQa` builds through
  `-PchatContextTokens`. Nobody has measured them on a device.
- Retrieval (S3) is tested on the JVM SQLite driver only. Android SQLCipher
  behaviour (FTS4 `matchinfo`, migration 5, backfill) is unverified.

## Part A — loaded-context observability

1. Add the loaded engine's context and output token sizes to
   `ModelRuntimeState` (nullable; `null` when no engine is loaded). Set them
   exactly where `loadedConfig` is set or cleared, so state never shows a size
   for an engine that is not resident. Cover every load path: panel `load()`,
   the ordinary chat path including the S2a release-and-reload, prepared and
   verification paths, idle release, public and host-loss release, failures.
2. Add the same two values to `MemoryTracePoint` and to the JSON written by
   `MemoryTelemetryRecorder` (additive keys, e.g. `contextTokensAtEvent`,
   `outputTokensAtEvent`, `null` when unloaded). Do not remove or rename
   existing keys. Check every consumer of these traces under `scripts/` and
   `androidApp/src/androidTest/`, and keep their tests passing.
3. Models panel: show the loaded sizes from runtime state when an engine is
   loaded; when unloaded, keep showing the manifest configuration but make
   clear which is which (resource strings, not hard-coded text). When the
   panel refuses a test prompt because the loaded engine is not 2048/512
   (S2a-F1), the panel must make that cause visible instead of a generic busy
   message, using a new string. Keep the UI change minimal and consistent
   with the approved design.
4. Memory requirements: do not change `MemoryPolicy` floors or admission. Add
   a short code comment at the admission point and a line in the results note
   that the memory floors are **not calibrated** for 4096 or 8192.

## Part B — S2b measurement test (write and compile only)

Write the instrumentation test the S2b run will use, modelled on
`ModelRuntimeTest` and the existing supervised QA harness
(`scripts/qa-supervision.mjs`, `scripts/monitor-model-thermal.mjs`); reuse
them rather than adding a parallel harness.

- For the profile baked into the `secureQa` build, on the GPU backend: cold
  load time, first text time, decode rate, input/output tokens, peak PSS/RSS
  from the telemetry recorder, thermal status before and after, and confirmed
  cleanup (release, then state `UNLOADED` with null loaded sizes).
- Synthetic prompts only: one near the profile's byte bound
  (`GemmaChatBudget`) and one short control, generated deterministically in
  the test; no personal data.
- The report records the loaded context/output sizes from runtime state, so
  each result is tied to what was actually resident.
- Results go to a JSON file in the app's test output for the harness to pull
  into `benchmarks/results/`; no automatic retries.
- Document the exact commands for 4096 and 8192 runs in the results note.

## Part C — Android SQLCipher passage gate test (write and compile only)

**Target device (user decision, 2026-11-18): the S24, not an emulator.** The
test must therefore be safe on the user's own phone:

- it runs in the `secureQa` build (`applicationIdSuffix ".secureqa"`, built
  with `-PtestBuildType=secureQa`), never the debug build, because Gradle's
  connected tests reinstall and then uninstall the app under test, which would
  erase the debug app's data;
- it uses only its own randomly named databases (as `SourcesLibraryTest` does)
  and deletes them afterwards; it never opens the app's real database, keys or
  files.
- **Planner ruling (2026-11-18, dev question):** the isolation requirement
  protects the debug and release apps' data. `CompanionApplication` starting
  in the `.secureqa` process and opening that QA app's own storage before
  JUnit runs is acceptable; no runner or startup change. The test methods must
  still not read or modify that storage, and installs use `adb install -r` /
  `am instrument` (no connected-test uninstall) so the QA app's imported model
  survives between runs.

Add an instrumentation test beside `SourcesLibraryTest` that runs on the
encrypted Android database:

- fresh import → passages indexed → retrieval returns the expected passage
  for a multi-word question (FTS4 `matchinfo` ranking works under SQLCipher);
- a version-4 encrypted database with existing sources migrates through 5,
  backfill completes, coverage becomes complete, and retrieval matches a fresh
  import;
- hostile query text is safe; deleting a source removes its passages and
  advances the context epoch.
Reuse the existing synthetic version-4 fixture approach in
`SourcesLibraryTest`. Synthetic text only.

## Allowed paths

- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ModelManager.kt`,
  `ModelManifest.kt`, `MemoryPolicy.kt` (types and comment only)
- `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/`
- `androidApp/src/main/kotlin/dev/edgecompanion/app/inference/MemoryTelemetryRecorder.kt`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/` (new tests; edits
  to existing tests only where an added field requires it)
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/settings/screen/ModelsPanel.kt`,
  `ui/src/commonMain/composeResources/values/strings.xml`, and `ui/src/commonTest/`
- `scripts/` consumers of the telemetry JSON and their tests, only if an added
  key requires it
- New note `docs/P2_DEVICE_PREP_RESULTS.md`

## Out of scope

Memory policy values, the 512 output reservation, changing the ordinary default
(stays 2048), V3/V4/constrained decoding behaviour, S4 work, any device or
emulator run, dependency changes.

## Done when

- Shared tests show the loaded sizes in runtime state and telemetry for every
  transition above, including S2a's reload, idle release and failure paths,
  and `null` whenever unloaded.
- A UI test shows loaded sizes when loaded, the manifest configuration when
  unloaded, and the specific refusal message.
- Parts B and C compile in the `secureQa` and debug test APKs.
- Existing tests pass unchanged except where an added field forces an edit
  (list each).

## Checks to run

```sh
./gradlew --offline :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest
./gradlew --offline :androidApp:assembleDebugAndroidTest
./gradlew --offline -PtestBuildType=secureQa -PchatContextTokens=8192 :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest
node --test scripts/tests/   # only if a script or its test changed
```

Use `--rerun` on test tasks. One Gradle invocation at a time.

## Report back

Handback format from the dev brief, plus: the new state and telemetry fields;
the panel strings added; the exact S24 command for Part C (secureQa) and the
exact S24 commands for Part B at 4096 and 8192, confirming that neither touches
the debug or release app's data; and anything that would make those
runs inconclusive.
