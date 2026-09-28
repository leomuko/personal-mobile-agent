# QA Remediation Results

Date: 2026-09-27. Branch: dev. Git base: `2eee2819c2447b8b11efd910358fa6dd5de87b9f`.
Application files remain untracked; HEAD alone does not identify this implementation. Source/build hashes are recorded in `docs/qa-source-snapshot.json`. No commit, push or branch change was performed.

## Implementation

- Capture, Main and Drawer use the same secure-window policy before composition. Release and secureQa enable it; debug deliberately permits screenshots.
- Drawer has a private, separate task affinity and is excluded from Recents. Close/Back removes its task; Expand reuses Main and selects the ongoing conversation.
- An Android-owned presence coordinator hides the floating mascot during assistant/capture screens, recreation and pending drawer launch. Stop, lock and overlay permission participate in visibility decisions. A rejected launch has bounded recovery.
- Notification permission, app-level disablement and channel disablement are checked. Continuing without notification controls requires explicit consent; drawer, Settings and accessible long-press provide alternative stop paths.
- MascotView implements accessible click/long-click and exclusive drag handling, with cancellation cleanup and physical-coordinate RTL behavior.
- Common UI and native UI strings use resources. Core errors are typed and localized at presentation. Stable tab IDs replace English navigation keys. Storage schema and conversation ownership are unchanged.

## Passed Checks

On the designated Pixel 7 Pro emulator, Android API 36, using synthetic secureQa data:

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease \
  :androidApp:testSecureQaUnitTest :shared:jvmTest :androidApp:lintDebug \
  -PtestBuildType=secureQa --console=plain
ANDROID_SERIAL=emulator-5554 bash scripts/verify-secure-qa.sh
```

- Debug and unsigned release APK compilation.
- 11 shared JVM tests: session/draft behavior, typed failures, cancellation, storage migration and FTS.
- 6 Android local tests: presence/lifecycle rules and permission decisions.
- 13 emulator tests: secure flags and host recreation, drawer task/expand behavior, gesture accessibility, RTL dragging/cancellation, UI error resources, tab identity, large-font controls, notification-degraded stop paths and encrypted storage.
- Direct mascot tap from Home opened a separate drawer task with Home underneath and Main hidden. Back dismissed it; a subsequent open/Expand reused the original Main task without duplicating it.
- A screenshot of secureQa's drawer was redacted by the emulator. This supplements window-flag assertions; it is not proof for every capture surface or device.

Final reports: `androidApp/build/reports/secure-qa-instrumentation.txt` (13 tests, no skips), `androidApp/build/test-results/testSecureQaUnitTest/`, `shared/build/test-results/jvmTest/`, and `androidApp/build/reports/lint-results-debug.html`.

An intermediate repeat run exposed a test-fixture defect: package/UID app-op changes did not reliably simulate denial after runtime permission had been granted. Revoking permission inside instrumentation killed the test process. The final script establishes actual denial before instrumentation starts; service tests run denial before grant. Direct Gradle connected-test reports from those intermediate attempts are superseded by the final script report, not evidence of a passing final run. Direct connectedSecureQaAndroidTest intentionally skips the external-fixture case.

## Lint Disposition

Lint passes with zero errors and 21 warnings. Accessibility, KTX and version-catalog findings were addressed. Physical LEFT gravity has a narrowly documented suppression because drag events and WindowManager positioning use physical coordinates; LTR/RTL gesture behavior is tested.

Remaining warnings are deliberately deferred, owned by project development at the Phase 0 toolchain/target compatibility gate before distribution:

| ID | Count | Rationale |
| --- | ---: | --- |
| OldTargetApi | 1 | Target behavior upgrade requires permission/background execution regression testing. |
| AndroidGradlePluginVersion | 3 | Gradle/AGP upgrades belong in a compatible toolchain update. |
| GradleDependency | 9 | Library upgrades are outside this focused behavioral fix. |
| NewerVersionAvailable | 8 | Review available versions and compatibility separately; no mass upgrade or lint baseline. |

## Remaining Acceptance

- S24 Ultra/One UI: actual version recorded, overlay/lock/revocation/capture lifecycle, notification controls, secure screenshots/recordings/Recents, encrypted storage and camera/mic shutdown.
- API 31/32 and 33 permission matrix, runtime denial/dismissal/channel settings and rotation during permission prompts. API 36 pre-revoked permission tests do not cover the full permission-dialog flow.
- Manual TalkBack, keyboard/switch access, pseudo-locales, landscape and keyboard visual checks. RTL and large-font semantic tests are not a substitute for all visual checks.
- Other-app drawer entry, main-task-absent entry, process-death continuity and repeated transitions on physical hardware.
- Independent QA re-review using `planning/QA_AGENT_PROMPT.md` against the full working tree, including untracked files.

No unresolved failing final automated check is being waived. Physical-device checks are not run, not passed. Local model integration and measured latency/thermal behavior remain outside this remediation; Phase 0 and release readiness are not declared complete.
