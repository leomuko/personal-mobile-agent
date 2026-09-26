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

The app opens into Chat. Settings contains Floating mascot and the actual device/model status. Turning on the mascot opens Android's overlay access screen if needed; return to Assistant after granting access. Tap the floating character to open its drawer, drag it to move, or hold it to hide. The expand icon returns to the full app. Camera and microphone open foreground capture hosts and request permission when used.

## Implemented

- KMP shared core, Compose Multiplatform UI, and Android application host.
- Approved mascot asset, Chat/Memory/Sources shell, settings, drawer and floating service.
- Shared encrypted conversation/draft storage, full-text index, schema migration and clear-conversation control.
- Real microphone waveform and camera capture/retake prototypes. Capture stops when backgrounded; media is not persisted.
- Common tests, SQL tests, Android encryption tests, inference contracts and device-report tooling.

## Not implemented yet

The local language/speech/embedding engines are not connected. Chat saves your message; it does not produce an AI reply. The UI explicitly shows model-unavailable states. Memory and Sources are empty states. OCR, retrieval, calendar writes, timers, document storage, model installation, and Gmail are subsequent work. No fake answers, transcripts, benchmark numbers or service results are presented.

Phase 0 has started; it is not complete until the device/runtime feasibility gates are measured. See [foundation decisions](docs/decisions/0001-phase0-foundation.md) and [benchmark instructions](benchmarks/README.md).

## Device tests

On a connected emulator or dedicated test device:

```sh
./gradlew :androidApp:connectedDebugAndroidTest
```

The storage tests create isolated synthetic databases. They verify encrypted bytes, full-text lookup, reopen, wrong-key rejection, and missing-key handling. Shared JVM tests exercise migrations and session behavior. Test reports are under each module's `build/reports/`.

Debug builds allow screenshots for UI validation. Release builds enable secure windows. Cloud backup and device transfer of personal data are excluded. The debug signing key and native-library notices must be replaced/reviewed before distribution.
