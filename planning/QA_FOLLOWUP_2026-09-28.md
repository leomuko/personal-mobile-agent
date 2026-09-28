# Camera Permission and QA Follow-up

28 September 2026. Scope: implement the requested camera-permission placement, review the supplied QA findings, document deferred audio retention, and plan the remaining composer correction. This is not authorization to implement AUDIO-002.

Follow-up: the user subsequently authorized the composer and camera recovery corrections. They are now implemented; see [remediation results](../docs/QA_COMPOSER_PERMISSION_RESULTS.md). The findings and initial 31/32 result below describe the earlier camera-only change, not the current validation status. AUDIO-002 remains deferred.

## Camera Permission: Implemented

Selecting Camera from the source sheet now requests Android camera permission from the current Assistant host, before CaptureActivity is launched. MainActivity and DrawerActivity share this attachment launcher. Denial leaves the user in the same conversation with a dismissible explanation and app-settings recovery. Grant opens the camera for the original draft epoch; completed capture retains the existing return-to-chat behavior.

The permission request flag and draft epoch are saved across host recreation. Result handling is tied to the host lifecycle. CaptureActivity retains a defensive permission check, but no longer requests permission itself: a missing grant offers Return to chat. No camera preview is initialized while permission is absent.

Targeted regression: first request in the full app, denial without launching CaptureActivity, preserved draft, a second request in the floating drawer, grant followed by exactly one camera launch, and return to the same drawer without adding a cancelled image. The test script revokes/resets only the isolated QA package's camera permission before instrumentation; connected tests without that preparation skip this dedicated scenario.

## Finding 1: Composer Expansion (P1, Open)

The independent QA run reported an instrumentation timeout. Inspection confirms that compact/expanded selection depends on IME bottom inset, attachments and text wrapping; text focus alone does not expand the input. This is a plausible cause for software-keyboard suppression, hardware-keyboard configurations and delayed/missing inset delivery. A prior successful run does not establish reliability or invalidate the new failing run.

Implementation plan:

1. Reproduce and record keyboard configuration, focus state, inset transitions and host (without logging draft content). Separate a missing-IME environment from a lost-focus or consumed-insets defect.
2. Represent active editing explicitly in the composer, driven by the text field's focus/edit events. Expand on entering editing even when the IME inset is zero; continue using attachment and multiline state to prevent inappropriate collapse. Keep the same mounted field so selection/focus survive layout changes.
3. Define exit behavior rather than blindly OR-ing focus with IME visibility: keyboard dismissal may leave the field focused. Clear/end editing on explicit dismissal/focus loss and on an observed visible-to-hidden IME transition, preserving text and attachments. Opening the source sheet or recorder also exits editing. Do not interpret the initial zero inset as a dismissal.
4. Split tests into deterministic focus-without-IME layout coverage and a real-keyboard integration scenario. Cover hardware keyboard enabled/disabled, delayed insets, empty/short/multiline drafts, Back dismissal, reopen, both hosts and recreation. Do not merely increase timeouts or force the emulator to show its keyboard to hide the issue.
5. Re-run the secure-QA suite repeatedly under both keyboard configurations, then verify the S24 Ultra. Mark this finding resolved only with that evidence.

No composer implementation or timeout change is included in the camera-permission task.

## Finding 2: Recording Retention (P1, Deferred)

Confirmed: MainActivity.onPause calls stop(cancel=false); recorder finalization invokes the encrypted attachment import callback for buffered audio. close() also preserves by default. Nothing is sent automatically, but audio can become a durable draft without the user's Stop action.

Recorded as AUDIO-002 in [deferred issues](DEFERRED_ISSUES.md), with the current behavior disclosed in README. When authorized, distinguish explicit Stop from cancellation/interruption, default interruption to no durable retention, and test Home/lock/rotation/destruction/calls/permission revocation plus Stop/pause races. Audio transcription (AUDIO-001) remains a separate deferred change.

## Finding 3: QA Command (P3, Documentation Clarified)

The claim that the task never exists is too broad. The project sets `testBuildType` from the Gradle property; a dry-run confirms `:androidApp:testSecureQaUnitTest` resolves with `-PtestBuildType=secureQa`. Without that property, the default task is `:androidApp:testDebugUnitTest`.

README now uses the default local-test command and the isolated secure-QA script separately. This removes the easy-to-miss property dependency from the main copy/paste command, while explaining the alternate task. No build-system or dependency change is needed.

## Validation Gate

Run debug build, shared JVM tests, default Android local tests, lint and the isolated camera/secure-QA suite. Record results separately from the independent review: a green repeat run alone does not close the intermittent composer finding. Physical S24 Ultra, reboot, provider-specific pickers and recorder interruption acceptance remain open. Camera permission behavior must be checked both when first requested and when already granted/denied; OS dialog wording and available grant choices remain Android-controlled.

### Results From This Change

- Passed: `./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug --console=plain`. Shared JVM tests: 23 passed; Android local tests: 6 passed. Lint: no errors, 23 warnings.
- Passed: `:androidApp:testSecureQaUnitTest -PtestBuildType=secureQa --dry-run`, confirming conditional task availability, not execution of those tests.
- Passed: secure-QA application/test APK assembly and the new camera permission regression on the existing `emulator-5554`. Denial in the full app, grant from the floating drawer, camera cancellation and retained draft were exercised.
- Failed: `env ANDROID_SERIAL=emulator-5554 bash scripts/verify-secure-qa.sh`: 32 tests run, 31 passed, 1 failed. The remaining failure is `CaptureFlowTest.keyboardExpansionKeepsFocusAndCollapseKeepsDraft`, timing out at line 79 waiting for `composer-expanded`. Report: `androidApp/build/reports/secure-qa-instrumentation.txt`.
- Environment observation: `show_ime_with_hard_keyboard` was `0`; it was not changed. This is consistent with the missing-IME hypothesis but does not alone establish the complete cause.
- Not run: physical S24 Ultra acceptance, reboot restoration, real provider picker matrix and recorder interruption/call/lock matrix. Recording behavior remains unchanged and deferred as AUDIO-002.

The camera change is emulator-verified; the application is not QA-clear while the composer regression remains red. No emulator was started, stopped or wiped for this change.
