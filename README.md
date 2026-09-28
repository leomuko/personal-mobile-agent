# personal-mobile-agent

Phase 0 foundation for the mascot-based local personal assistant. Plans and screenshot references live separately under [planning/](planning/IMPLEMENTATION_PLAN.md).

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

## Not implemented yet

The local language/speech/embedding engines are not connected. Chat saves your message; it does not produce an AI reply. The UI explicitly shows model-unavailable states. Memory and Sources are empty states. OCR, document parsing/analysis, retrieval, calendar writes, timers, model installation, and Gmail are subsequent work. Attaching a file stores it; it does not imply the model can understand it. No fake answers, transcripts, benchmark numbers or service results are presented.

Phase 0 has started; it is not complete until the device/runtime feasibility gates are measured. See [foundation decisions](docs/decisions/0001-phase0-foundation.md) and [benchmark instructions](benchmarks/README.md).

## Device tests

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
