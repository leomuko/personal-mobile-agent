# P2-01 Appearance and QA Identity

Implementation and focused verification on 2026-10-15. This delivers the independent appearance/branding code slice in [the P2 plan](../planning/P2_IMPLEMENTATION_PLAN.md); it does not complete P2 or every P2-01 manual acceptance item.

## Implemented

- Shared `ThemeMode` and observable `AppearancePreferences`, registered once in Koin. Default/unknown values resolve to System. Android stores the non-sensitive selection in private preferences independently of the encrypted conversation database.
- Serialized saves publish committed state, expose recoverable load/save errors and reconcile uncertain writes. A started preference transaction finishes independently of its requesting UI. Preferences do not create another conversation or model owner.
- Appearance radio controls in Settings; explicit light/dark semantic palettes for conversation bubbles, warnings, navigation, recording and empty states. Theme content colors apply even outside a Material Surface. Media and mascot pixels are unchanged.
- Main, drawer and capture share the selected scheme. Android applies a package-local night-mode override, day/night launch resources, window backgrounds, bar-icon contrast and drawer dimming. The three activities handle `uiMode` changes without recreation; rotation behavior is unchanged. Secure flags are preserved.
- `secureQa` alone uses the label `Assistant (QA)` and an amber adaptive/round icon with QA lettering, plus a monochrome representation. Ordinary branding/package IDs, signing configuration and private data locations are unchanged. The conversation mascot is not recolored.

No schema migration, source import, memory records, embeddings, grounded generation, context fences or quota/retrieval implementation is included here. The existing QA memory-policy selection and deferred audio/release-packaging decisions remain unchanged. No native inference workload ran during this slice. Host-disconnect protection was the next prerequisite; its subsequent implementation and remaining native verification are linked in the follow-up below.

## Ordering Review Follow-Up

The subsequent P2 review correctly identified that independently dispatched selections could acquire the write mutex out of request order. `select` now allocates an atomic request sequence synchronously, before launching work. Under the existing mutex, superseded queued requests are ignored; an already-started short transaction still finishes and publishes its committed value before the latest request is processed. The latest queued choice wins, including a return to the already-committed mode.

Two deterministic reversed-dispatch regressions were added, bringing the appearance suite to 10 tests. The fresh focused S24 rerun passed **OK (14 tests)** in 20.47 seconds; its log is `androidApp/build/p2-appearance-ordering-review.log`. The review's unverified system-triggered changes, launch flashes and real recording/inference continuity remain unverified, not implicitly closed by this fix. The next prerequisite slice is recorded in [QA supervision results](P2_SUPERVISION_RESULTS.md).

## Passed

- `./gradlew :androidApp:assembleDebug :androidApp:assembleRelease :shared:jvmTest :shared:testAndroidHostTest :ui:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug --console=plain`
- Secure-QA app/test compilation and `:androidApp:testSecureQaUnitTest` with `-PtestBuildType=secureQa`.
- Eight new shared preference tests: mode resolution, reopen, bootstrap/save failure, retry, uncertain-write reconciliation, serialized changes and cancellation. One new UI palette test checks text pairs at 4.5:1 and essential outlines at 3:1. Existing local tests also pass. Lint reports 26 warnings, not a warning-free build; UI common tests run on JVM, with no UI Android host-test target enabled.
- S24 Ultra SM-S928B, Android 16: `AppearancePresentationTest`, **OK (2 tests)**. Synthetic fixtures cover navigation/draft/stream preservation, explicit theme selection, readable rendered dark text, all existing destinations at 320 dp/200% text, and recording controls in both palettes. No real audio is recorded by these fixtures.
- S24: `AppearanceHostTest`, `AssistantTopBarTest`, `AssistantPresentationTest`, `HostRegressionTest`, **OK (12 tests)**. Real host checks cover Main/drawer/capture survival through Dark/Light/System switching, saved-mode read/recreation, bar contrast, QA identity and secure flags. Existing tests cover drawer task isolation, header rhythm and assistant controls.
- Final combined S24 run on the corrected snapshot: all five focused classes above, **OK (14 tests)**, 17.348 seconds. The new presentation fixture uses the v2 Compose rule; existing tests are not batch-migrated.
- APK metadata inspection distinguishes ordinary `Assistant`/`ic_assistant` from `Assistant (QA)`/`ic_launcher_qa` and retains the existing application IDs. QA updates used data-preserving installs, not uninstall/reset.

The first connected 14-test Gradle run lost ADB after the three appearance host tests passed; its report remains an incomplete run, not a pass. A direct streaming retry also lost its host connection. Non-native runs retained their result logs on the device and were retrieved afterward. An additional repeat exposed a test-oracle error when the global phone appearance was dark: `Resources.getSystem()` could still reflect the process's forced Light override. The host test now reads Android's global `am get-config` night qualifier, without changing device settings. The corrected final combined run passed all 14 tests. Retaining non-native test logs is not evidence of native-workload supervision safety.

Reports: module `build/reports/` directories and `androidApp/build/p2-appearance-final.log` (final combined run), plus the earlier `p2-appearance-presentation.log` / `p2-appearance-hosts.log`. Final synthetic visual exports are under `androidApp/build/p2-appearance-final/`. Optional screenshots export only synthetic Compose roots or launcher drawables when `appearanceScreenshots=true`; no personal chat, camera frames, database, model data or keys are exported. Build outputs are ignored by Git. Both ordinary and QA APKs were updated on the phone with data-preserving installs; no emulator was started.

## Remaining Acceptance

- Manual TalkBack, real recording/inference continuity through appearance changes, launch-flash observation, system-triggered appearance changes, process death/reboot and landscape. Synthetic streaming/recording fixtures do not prove those native lifecycles.
- Complete attachment-sheet/preview, dialog, loading/error and disabled-state screenshot matrix. Current fixture coverage is deliberately narrower.
- Launcher/App Info/Recents and notification-attribution inspection on One UI, including an actually enabled themed-icon launcher. Resource/render and manifest checks are not the full launcher workflow.
- Complete mobile/secure-QA matrix and P2 source/memory/retrieval acceptance. No new native performance, privacy, compatibility or latency claim follows from these appearance tests.

## Reproduce Focused Device Checks

Build with `./gradlew :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest -PtestBuildType=secureQa`. On an authorized, unlocked test device, install both QA APKs with `adb -s DEVICE install -r PATH` to preserve data. Then run:

```sh
adb -s DEVICE shell am instrument -w \
  -e class dev.edgecompanion.app.AppearancePresentationTest,dev.edgecompanion.app.AppearanceHostTest,dev.edgecompanion.app.AssistantTopBarTest,dev.edgecompanion.app.AssistantPresentationTest,dev.edgecompanion.app.HostRegressionTest \
  dev.edgecompanion.app.secureqa.test/androidx.test.runner.AndroidJUnitRunner
```

Add `-e appearanceScreenshots true` only for the synthetic fixture/icon exports under the QA app's external-files `appearance-fixtures` directory. Real-host tests restore the original appearance selection in `finally`; they do not clear conversation history or change device-wide appearance.
