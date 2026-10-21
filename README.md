# personal-mobile-agent

Mascot-based local personal assistant: Phase 0 shell with a Phase 1 internal runtime foundation in progress. Plans and screenshot references live separately under [planning/](planning/IMPLEMENTATION_PLAN.md).

## Build

Open this folder in Android Studio, or use JDK 17+ and the Android SDK (platform 37, build tools supplied by AGP):

```sh
./gradlew :androidApp:assembleDebug :shared:jvmTest
./gradlew :androidApp:lintDebug
```

Set `sdk.dir` in your local, git-ignored `local.properties`, or set `ANDROID_HOME`. The wrapper is pinned to Gradle 9.4.1. Dependency downloads require internet at build time; the installed app currently has no internet permission.

Debug APK: `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

## Run

```sh
./gradlew :androidApp:installDebug
adb shell am start -n dev.edgecompanion.app/.MainActivity
```

The app opens into Chat. The top-left menu contains Chat, Memory, Sources and Settings; the bottom navigation and header Settings gear have been removed. Settings contains Floating mascot and the actual device/model status. Enabling the mascot requests overlay access and, on Android 13+, notification permission when needed. If notification controls are unavailable, enabling requires explicit confirmation; Settings, drawer Stop and mascot long-press remain alternative stop paths.

The mascot is hidden while an assistant screen is visible. Go Home or to another app to use it: tap to open the independent drawer, drag to move, or hold to stop. Expand reuses the full app and the same conversation. Close/Back dismisses the drawer without opening the full app. The microphone records inline with a live waveform. The plus button opens Photos, Camera and Files cards; camera capture returns an unsent image to the originating chat. Permissions are requested when needed.

## Implemented

- KMP shared core, Compose Multiplatform UI, and Android application host.
- Koin dependency modules, lifecycle-aware host ViewModels, and Navigation Compose side-drawer navigation. See [dependency and navigation architecture](docs/decisions/0002-koin-navigation-lifecycle.md).
- Approved mascot asset, Chat/Memory/Sources shell, settings, drawer and floating service.
- System/Light/Dark appearance with an app-scoped preference owner, themed conversation/capture hosts, and distinct amber `Assistant (QA)` launcher branding. See [P2 appearance evidence and remaining gates](docs/P2_APPEARANCE_RESULTS.md).
- Test-only, run-correlated native QA supervision with device-local expiry and emergency process termination; synthetic S24 loss/hung-cleanup checks are documented in [P2 supervision results](docs/P2_SUPERVISION_RESULTS.md). This does not claim a new native acceptance pass.
- Shared encrypted conversation/draft storage, full-text index, schema migration and clear-conversation control.
- Text-only Sources pilot: paste or explicitly import UTF-8 text (64 KiB/source), rename, inspect immutable text, paged keyword search and confirmed deletion. A persisted context fence prevents pre-delete chat and retries from re-entering model requests. See [scope and focused evidence](docs/P2_SOURCES_RESULTS.md).
- Durable encrypted draft and sent attachments, multiple document/image selection, image thumbnails and document tiles restored after reopening.
- Unified compact/expanded composer with the microphone beside Send, mixed attachment tiles inside the input container, and a three-option attachment sheet. Dictation-first audio remains deferred; current audio review/playback is preserved.
- Inline microphone waveform, timer, cancel/stop and unsent WAV playback; CameraX capture returning to chat. Capture stops when backgrounded; no automatic sending or transcription.
- Feature-first packages for conversation screens/components/ViewModels, storage, capture, platform bridges and dependency injection. Existing module boundaries and application-scoped session are preserved.
- Common tests, SQL tests, Android encryption tests, inference contracts and device-report tooling.
- Internal Models panel with pinned Gemma 4 E2B import/hash verification, explicit CPU/GPU controls, a LiteRT-LM adapter, application-scoped runtime management, and opt-in synthetic benchmark tooling. CPU emulator and S24 CPU/GPU native smoke are verified; 20-sample S24 GPU latency and one supervised 20-minute synthetic workload passed. Full Phase 1 acceptance remains pending; internal text-chat status is described below. See [Phase 1 implementation evidence](docs/PHASE_1_IMPLEMENTATION_RESULTS.md).

## Not implemented yet

Production speech/embedding engines, OCR, PDF ingestion, semantic retrieval/grounded answers, calendar writes, timers, public model downloads, and Gmail remain subsequent work. Memory is still an empty state; Sources supports the limited text-only pilot, not the complete P2 library. Full mobile/release acceptance remains open.

## Internal Text Chat

Debug/secure-QA builds connect text chat to the local runtime through request-specific generation and conservative budgeting verified against the pinned tokenizer. With the model installed and sufficient resources, submission attempts a streamed reply; blocked or failed requests leave the saved user turn with a recoverable status. Stop, retry, host handoff and process-restoration checks are covered in [P1B results](docs/P1B_IMPLEMENTATION_RESULTS.md). The artifact-specific v2 bound admits up to 1312 ASCII bytes of current input without history, and less for multibyte text or retained history; the 2048 context and 512 output reservation are unchanged. See the [revised proof](docs/decisions/0004-p2-prerequisite-contracts.md). Attachments are preserved but not passed to inference. Release builds continue to save user messages without enabling generation.

Verify the prompt bound with `bash scripts/verify-chat-budget.sh`. This creates an isolated Python environment and installs pinned host dependencies; the approved model weights must already exist under `models/`, or be supplied with `--model PATH`. The setup does not download weights. Run native-wrapper regressions with `node --test scripts/tests/chat-resource-preflight.test.mjs`. The supervised native script retains stage/failure reports, waits briefly for stable resources, and keeps the app's 3 GiB safety floor unchanged. See the P1B results for prerequisites and remaining acceptance limits.

The internal [P1A runtime foundation](planning/PHASE_1_PLAN.md) and [P1B conversation integration](planning/P1B_CONVERSATION_PLAN.md) are implemented, with focused native evidence on the connected S24. Broader mobile and release acceptance remains open; this is not full Phase 1 acceptance. See the [Gemma 4 / LiteRT-LM decision](docs/decisions/0003-gemma4-litert-lm.md), [historical foundation decisions](docs/decisions/0001-phase0-foundation.md), and [benchmark instructions](benchmarks/README.md).

Before P2, the [P1 memory-reliability work package](planning/P1_MEMORY_RELIABILITY_PLAN.md) fixes retained-engine cleanup and adds opt-in allocation telemetry plus an app-exported shared policy. Existing thresholds remain unchanged. Current S24 calibration is blocked by that baseline policy, not certified by prior native passes; see [memory-reliability results](docs/P1_MEMORY_RELIABILITY_RESULTS.md).

The 2026-10-13 S24 GPU smoke passed after the runtime QA revisions. Full S24 instrumentation still has camera-permission/accessibility and notification harness failures, and the revised monitor needs sustained validation. The unsigned release APK currently includes LiteRT-LM native libraries despite disabled runtime controls; isolate that dependency before distribution. See [QA revision results](docs/PHASE_1_QA_REVISIONS.md).

## Device tests

P2 prerequisite work is recorded in [P2 prerequisite results](docs/P2_PREREQUISITE_RESULTS.md): authenticated document storage/protected PDF access and an opt-in isolated encoder lifecycle spike. The independent [text Sources pilot](docs/P2_SOURCES_RESULTS.md) uses the existing encrypted database, not PDF parsing or embeddings. [Verified encoder admission and foreground handoff](docs/P2_ENCODER_HANDOFF_RESULTS.md) now have focused host/S24 evidence at the unchanged 192-token limit. This internal prerequisite is not wired into production conversation or Sources; semantic retrieval and the separate final P2 acceptance campaign remain open.

The next [semantic-search execution contract](planning/P2_SEMANTIC_SEARCH_CONTRACT.md) is gate-first. Its frozen encoder qualification failed the required English relevance gates; [results and retained attempts](docs/P2_SEMANTIC_QUALIFICATION_RESULTS.md) record current-source runtime passes separately. Indexing/hybrid search remains unimplemented and disabled; keyword Sources is unchanged.

The shared Android host tests use Java 21 to load the pinned LiteRT-LM classes; compilation and shared JVM tests keep their Java 17 toolchain. Android Studio's bundled Java 21 runtime can supply the test launcher:

```sh
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :shared:testAndroidHostTest
```

On a connected emulator or dedicated test device:

```sh
./gradlew :androidApp:connectedDebugAndroidTest
```

For the full privacy/service regression suite, use the isolated `secureQa` variant on a designated emulator:

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease \
  :androidApp:testDebugUnitTest :shared:jvmTest :androidApp:lintDebug
ANDROID_SERIAL=emulator-5554 bash scripts/verify-secure-qa.sh
ANDROID_SERIAL=emulator-5554 bash scripts/verify-composer-keyboards.sh
ANDROID_SERIAL=emulator-5554 bash scripts/verify-attachment-restart.sh
```

`secureQa` uses a separate `.secureqa` application ID and debug signing, but enables secure windows independently of `DEBUG`. Select your actual emulator serial for the script; it installs the QA/test APKs and revokes notification and camera permissions before instrumentation, since revoking them inside a test kills the process. Camera request flags are reset only for that QA package so first-request tests are reproducible. Only that QA package is changed; its service is stopped on exit. The command above uses the default `testDebugUnitTest`. The alternate task `testSecureQaUnitTest` exists only when invoked with `-PtestBuildType=secureQa`; the script supplies that property for its instrumented APK build. Direct connected instrumentation skips externally prepared permission scenarios when their arguments are absent.

Storage tests create synthetic databases and check encryption, lookup, reopen, wrong-key rejection and missing-key handling. Added regressions cover typed errors, permissions, mascot gestures/visibility, host windows, drawer tasks and resource-backed UI. Reports are under each module's `build/reports/`. See [QA remediation results](docs/QA_REMEDIATION_RESULTS.md) for verified scenarios and remaining device gates.

The composer keyboard script temporarily changes the designated emulator's software-keyboard setting, repeats the focused composer checks in both modes, and restores the original setting on exit. Camera request-state tests use controlled registry results independently of the system dialog. The real-dialog smoke test currently supports AOSP/Google permission-controller selectors only; unsupported OEM layouts fail with device/controller diagnostics, not a silent pass. S24 Ultra/One UI permission and gesture acceptance still require device evidence. See [composer and permission QA follow-up](docs/QA_COMPOSER_PERMISSION_RESULTS.md).

See [composer implementation results](docs/COMPOSER_IMPLEMENTATION_RESULTS.md) for attachment/recording behavior, limits, regression evidence and outstanding physical-device checks. The separate-process restart script requires the QA APKs installed by the preceding suite and uses only synthetic QA storage.

Debug builds allow screenshots for UI validation. Release builds enable secure windows. Cloud backup and device transfer of personal data are excluded. The debug signing key and native-library notices must be replaced/reviewed before distribution.

Known deferred privacy behavior: leaving/locking the app while recording stops capture but currently saves buffered audio as an unsent attachment without an explicit Stop tap. It is not sent automatically. See AUDIO-002 in [deferred issues](planning/DEFERRED_ISSUES.md); do not treat recorder interruption behavior as release-cleared.
