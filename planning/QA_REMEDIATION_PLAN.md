# Phase 0 QA Remediation Plan

Date: 2026-09-26
Status: implementation authorized and completed on 2026-09-27; emulator verification performed. Physical-device acceptance is still pending. See `docs/QA_REMEDIATION_RESULTS.md` for scope and evidence.
Branch: dev. Baseline HEAD: 2eee2819c2447b8b11efd910358fa6dd5de87b9f.

This plan covers the four supplied QA findings and previously deferred UI-001/UI-002. These are six ordered work batches inside Phase 0, not six new product-development phases. The existing implementation roadmap remains authoritative. The acceptance criteria below remain the full checklist, not a claim that every device scenario has passed.

## Review Assessment

- Confirmed P1: CaptureActivity does not apply the release secure-window policy already used by MainActivity and its DrawerActivity subclass.
- Confirmed P2: notification permission and notification-channel availability are not modeled. Qualification: notification actions are not the only stop path; Settings and mascot long-press already stop the service. The defect is an undisclosed degraded state and unreliable access to the advertised notification controls, not an unstoppable service.
- P3 accessibility: the custom touch listener triggers ClickableViewAccessibility. It already calls performClick and has click/long-click listeners, so a real TalkBack failure has not been established. Formalize and verify the interaction contract rather than treating the warning as proof.
- Confirmed P3: presentation text is hardcoded, including errors in common core logic and English tab labels used as navigation identifiers.
- Confirmed UI-001/P1 and UI-002/P2: shared task affinity brings the full app forward under the drawer, and the floating mascot remains above the drawer controls.
- The supplied baseline reports a successful debug build, nine passing JVM tests, and lint with 24 warnings. These are prior results, not post-fix evidence. Device and secure-window validation remain incomplete.

## Architecture and Scope

Keep the existing androidApp, shared and ui modules. No DI framework, navigation framework, database migration, model integration or additional product feature is required for these fixes.

- androidApp owns secure windows, tasks/intents, permission launchers, foreground service, overlay view and Android lifecycle coordination.
- shared keeps conversation persistence and platform-neutral typed failures. Existing SQL data and the single conversation controller remain the source of truth.
- ui owns common Compose text resources and presentation mapping. Native service/manifest/platform copy uses Android resources.
- A small Android-owned presence coordinator is justified because drawer, capture and service currently have no common visibility contract. It must not become a general-purpose global event bus or a second conversation store.
- Sensitive window policy and presence state must be independently testable. Do not make correctness depend solely on Activity callback ordering.

## Batch 1: Secure Every Sensitive Window

Addresses QA P1. Files: MainActivity.kt, CaptureActivity.kt, androidApp build configuration, new small window-policy helper and tests.

1. Centralize the secure-window policy and apply it before rendering Main, Drawer and Capture, including camera preview, captured-photo review and microphone modes.
2. Use an explicit build-time secure-window setting: enabled by default/release, disabled only for screenshot-friendly debug builds. A release-like secure QA variant may retain testability but must keep the secure setting enabled independently of BuildConfig.DEBUG.
3. Exercise inherited policy for dialogs and inspect any separately created windows/surfaces. Do not assume protecting one Activity protects every window.
4. Keep the test build isolated from personal app data and signed only with a local test key. No release signing secrets are needed.

Acceptance: automated flag assertions for all three hosts and recreation; synthetic receipt/chat content is blocked or redacted in OS screenshots, recording and Recents on the designated emulator and S24 Ultra. Capture transitions must not expose an unprotected frame. Debug screenshot behavior remains deliberately testable. FLAG_SECURE is an OS mitigation, not a claim of absolute capture prevention on compromised devices.

## Batch 2: Independent Drawer and Coordinated Mascot Visibility

Addresses UI-001 and UI-002 together. Files: AndroidManifest.xml, MainActivity.kt/DrawerActivity, MascotService.kt, CompanionApplication.kt and a narrowly scoped Android presence coordinator. Keep any capture handoff in CaptureActivity consistent.

1. Give the private DrawerActivity an explicit task affinity distinct from the launcher task and exclude its temporary task from Recents. Keep it non-exported. Use an explicit launch contract that reuses an existing drawer instead of creating duplicate tasks.
2. Opening the drawer must retain Home or the previously foreground app underneath. Do not clear the main task, relaunch MainActivity, or add MULTIPLE_TASK as a shortcut.
3. Close/Back dismiss the drawer task and return to the prior surface. Expand intentionally brings up/reuses the main task and removes only the temporary drawer task. Preserve conversation, draft, keyboard and capture return state; prevent duplicate MainActivity instances.
4. Track service-running, overlay permission, unlocked state, pending drawer launch and active assistant surfaces. Derive mascot visibility in one place. The service owns the overlay View and observes the coordinator; activities report lifecycle/surface transitions without retaining Activity references.
5. Hide the mascot while the drawer or its capture flow is visible; keep it hidden when expanding into the full app. Restore it outside assistant surfaces only when the service remains enabled, permission is valid and the device is unlocked.
6. Handle rapid repeated taps, failed/blocked activity launch, recreation and overlapping start/stop callbacks. A launch-pending state must have failure recovery and cannot leave the mascot permanently hidden. Screen unlock must recompute visibility rather than blindly set VISIBLE.

Acceptance: open from Home and another app, with main task present/absent; close, system Back, expand and repeat. Assert task IDs/top activities and absence of an extra Recents entry. Test capture round trips, rotation, Home, lock/unlock, service stop and permission revocation. No overlay covers header controls or reappears after Stop. Verify continuity after process recreation without expecting a force-stopped app to restart itself.

## Batch 3: Explicit Notification Permission and Recovery

Addresses QA P2. Files: MainActivity.kt, MascotService.kt, Android presence/permission state, CompanionScreen.kt, Android string resources and tests.

1. Request POST_NOTIFICATIONS contextually when enabling the mascot on API 33+, not at unrelated app startup. Keep notification authorization separate from overlay authorization and actual service-running state.
2. Distinguish granted, denied/dismissed, blocked in Settings, and channel-disabled states. Inspect both app-level notification enablement and the mascot channel. API 31/32 still require checking app/channel settings, but no runtime notification prompt.
3. Proposed behavior: denial does not disable full-app use. Leave mascot enablement pending/off initially, show the unavailable-notification state, and allow a deliberate choice to continue without notification controls or open Settings. Do not repeatedly prompt after denial.
4. Preserve alternative stop paths: main Settings toggle, accessible mascot stop/long-click action, and an accessible Stop mascot command from the drawer. Closing a conversation is distinct from stopping the mascot service.
5. Reconcile current permissions on return from Settings, before service start and before subsequent interactions. Permission UI effects must survive rotation without duplicate prompts or duplicate starts. Revocation must not silently grant permission or change the user's explicit choice.
6. Always create the required foreground-service notification even if notification permission is denied. Keep its content generic, actions idempotent and PendingIntents immutable. Missing notification permission alone must not be treated as an Android prohibition on starting a foreground service.

Acceptance: API 31/32 and API 33+ coverage for allow, deny, dismiss, channel disabled, grant/revoke in Settings, overlay denial and successful enablement. Verify notification Open/Stop when visible and alternative Stop paths when hidden. A failed start leaves truthful state; stopping cannot restart the service through a stale callback.

## Batch 4: Accessible Mascot and RTL-Safe Movement

Addresses QA P3 accessibility and related RTL lint. Files: MascotService.kt and a focused MascotView or equivalent input component, native resources, Android tests.

1. Move gesture handling into an explicit View interaction contract with performClick/performLongClick delegating to super and the existing action handlers. Keep drag, tap and long-press mutually exclusive.
2. Provide localized accessible labels/actions for Open and Stop. Verify keyboard, switch access and TalkBack can invoke the same actions without dragging. Clean pending gesture callbacks on detach/cancel/service shutdown.
3. Preserve bounds and snapping under rotation/window changes. Resolve physical screen coordinates and RTL layout deliberately: replacing LEFT with START alone would change the origin and can break the existing drag calculations.
4. Remove the accessibility warning through correct behavior, not a broad suppression. If physical gravity is intentionally retained, document and narrowly justify it with LTR/RTL tests rather than disabling RTL checks globally.

Acceptance: accessibility ACTION_CLICK opens exactly one drawer; stop/long-click stops without opening it; canceled gestures do neither; dragging never also clicks. Manual TalkBack and keyboard checks supplement instrumentation. No off-screen mascot after orientation or layout-direction changes.

## Batch 5: Resource Strings and Typed Failures

Addresses QA P3 localization. Files: Conversation.kt and common tests, CompanionApplication.kt, CompanionScreen.kt, CaptureActivity.kt, MainActivity.kt, MascotService.kt, new ui/commonMain Compose string resources and Android values/strings.xml.

1. Replace common-core display strings with typed errors for storage open, draft save, message save and clear failures. Type the encrypted-storage initialization error as well; map to safe localized copy at the UI boundary without exposing raw exceptions or sensitive content.
2. Migrate all current common UI copy, placeholders, tooltips, content descriptions, statuses and dialogs to Compose resources. Put native notifications, permission errors, toasts and manifest labels into Android resources. Keep the source of each string clear and avoid competing translations for the same shared UI label.
3. Replace English-string navigation identity with stable tab enums/IDs. A translated label must never change routing or state restoration.
4. Parameterize device/version messages and apply locale-aware presentation formatting. Never translate stored user messages, internal action IDs or database keys.
5. Add default English resources and pseudo-locale testing. Shipping human translations is separate scope; extraction alone is not multi-language product support.

Acceptance: common tests assert typed failures and unchanged persistence behavior. Resource-backed UI tests cover all error cases and configuration/locale changes. Pseudo-locales, RTL and large font scaling show no clipped labels, hidden controls or broken tab selection. User drafts/history remain byte-for-byte unchanged.

## Batch 6: Regression Evidence and Focused Lint Cleanup

Complete after each batch's targeted tests, then rerun against the final working-tree snapshot.

1. Address UseTomlInstead without changing dependency versions and UseKtx where the existing dependency supports it. Inspect the resolved Material3 dependency before removing or changing any declaration. Do not mass-upgrade libraries just to remove freshness warnings.
2. Classify every remaining lint warning as fixed or explicitly deferred with rationale and owner/next milestone. Review target-SDK/dependency compatibility separately; no blanket lint baseline or warning suppression to manufacture a pass.
3. Resolve adb from local.properties or ANDROID_HOME/ANDROID_SDK_ROOT; on this machine it is `/Users/kezileo/Library/Android/sdk/platform-tools/adb`. Absence from PATH does not by itself block testing. Check device identity, authorization and free storage before deployment.
4. Use a designated synthetic-data emulator for destructive permission/task scenarios. Do not wipe existing AVD data or stop user-started emulators. Obtain separate authorization for S24 Ultra install/permission manipulation and record its actual Android/One UI versions.
5. Preserve the existing nine tests and encryption integration tests. Add new tests in the layers below. Tie reports to HEAD plus hashes of changed/untracked files because HEAD alone does not identify this application baseline.

| Layer | Required evidence |
| --- | --- |
| shared/commonTest and jvmTest | Typed errors, unchanged draft/history recovery, duplicate submit, failure transitions, migrations and FTS |
| androidApp local tests | Secure policy selection, permission decision transitions, presence visibility reducer and interaction rules |
| Android instrumentation | Actual host window flags, drawer task/Back/expand behavior, accessibility actions, permission/service recovery, encrypted storage |
| Compose UI tests | Resource/error rendering, tab identity, controls and state continuity |
| Manual device checks | S24 Ultra secure capture/Recents, One UI overlay/lifecycle behavior, notification denial/revocation, TalkBack, camera/microphone shutdown |
| Visual checks | Screenshot-friendly debug build with synthetic content, keyboard, landscape, large fonts and pseudo-locales; verify no overlapping controls |

Current baseline commands:

```sh
./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug
./gradlew :androidApp:connectedDebugAndroidTest
```

Wire and discover additional local/UI/secure-QA tasks as their test configurations are introduced; do not claim a task exists or passed in advance. Include release compilation and secure-policy device checks, not just debug tests. A locally signed QA build is not a production-signed release.

## Completion and Delivery

- Deliver each batch as a small, reviewable change with its targeted regression tests. No automatic commits or pushes; work remains on dev until authorized.
- UI-001/UI-002 stay open until their behavior is verified. Update the deferred-issue register with evidence only when implementation is requested and completed.
- Report passed, failed, blocked and not-run checks separately. Do not call the work release-ready while required physical-device/privacy evidence remains missing.
- All six reported issues must meet their acceptance criteria, with no new critical regressions. Existing encryption, drafts and conversation continuity must remain intact.
- Refresh README claims and request an independent QA re-review of the exact final snapshot.
- This remediation does not complete all of Phase 0: local model/runtime integration and S24 performance/thermal benchmarks remain separate gates.

## Platform References

- [Android secure sensitive activities](https://developer.android.com/security/fraud-prevention/activities): secure-window mitigation and limitations.
- [Notification runtime permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission): permission denial affects notification-drawer visibility, not the requirement to create an FGS notification or the ability to start an FGS by itself.
- [Tasks and back stack](https://developer.android.com/guide/components/activities/tasks-and-back-stack): affinity and task reuse.
- [Activity manifest reference](https://developer.android.com/guide/topics/manifest/activity-element): task affinity, export and Recents attributes.
