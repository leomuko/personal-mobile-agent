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
- Shared encrypted conversation/draft storage, full-text index, schema migration and clear-conversation control.
- Durable encrypted draft and sent attachments, multiple document/image selection, image thumbnails and document tiles restored after reopening.
- Unified compact/expanded composer with the microphone beside Send, mixed attachment tiles inside the input container, and a three-option attachment sheet. Dictation-first audio remains deferred; current audio review/playback is preserved.
- Inline microphone waveform, timer, cancel/stop and unsent WAV playback; CameraX capture returning to chat. Capture stops when backgrounded; no automatic sending or transcription.
- Feature-first packages for conversation screens/components/ViewModels, storage, capture, platform bridges and dependency injection. Existing module boundaries and application-scoped session are preserved.
- Common tests, SQL tests, Android encryption tests, inference contracts and device-report tooling.
- Internal Models panel with pinned Gemma 4 E2B import/hash verification, explicit CPU/GPU controls, a LiteRT-LM adapter, application-scoped runtime management, and opt-in synthetic benchmark tooling. CPU emulator and S24 CPU/GPU native smoke are verified; 20-sample S24 GPU latency and one supervised 20-minute synthetic workload passed. Full Phase 1 acceptance and ordinary assistant chat remain pending. See [Phase 1 implementation evidence](docs/PHASE_1_IMPLEMENTATION_RESULTS.md).

## Not implemented yet

The language runtime adapter is connected only to internal synthetic tests; full S24/resource acceptance is still pending despite passing GPU measurements. Chat saves your message; it does not produce an AI reply. The UI explicitly shows chat-unavailable states independently of installed test assets. Speech/embedding engines, OCR, document parsing/analysis, retrieval, calendar writes, timers, public model downloads, and Gmail remain subsequent work. Memory and Sources are empty states. Attaching a file stores it; it does not imply the model can understand it. No fake answers, transcripts, benchmark numbers or service results are presented.

The current slice is [P1A: Runtime Foundation](planning/PHASE_1_PLAN.md), followed by P1B: Conversation Integration. Development began on the emulator and continued on the connected S24. Several native viability gates have passed, but P1A is not fully accepted. See the [Gemma 4 / LiteRT-LM decision](docs/decisions/0003-gemma4-litert-lm.md), [historical foundation decisions](docs/decisions/0001-phase0-foundation.md), and [benchmark instructions](benchmarks/README.md).

The 2026-10-13 S24 GPU smoke passed after the runtime QA revisions. Full S24 instrumentation still has camera-permission/accessibility and notification harness failures, and the revised monitor needs sustained validation. The unsigned release APK currently includes LiteRT-LM native libraries despite disabled runtime controls; isolate that dependency before distribution. See [QA revision results](docs/PHASE_1_QA_REVISIONS.md).

## Device tests

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
