# Composer and Camera Permission QA Remediation

28 September 2026. Authorized scope: composer focus/Back behavior, stale camera permission recovery and permission-test resilience. No audio retention, database or navigation redesign.

## Implemented

- Composer expansion now includes explicit input focus. The same text field remains mounted, preserving selection and draft while the layout changes. A zero initial IME inset does not cancel editing.
- Keyboard-mode repetition exposed a second input bug: the value-based field received its edited value only after asynchronous persistence, allowing stale keyboard edits to erase newly typed text. The composer now echoes edits synchronously, ignores older storage acknowledgements while the newest edit is pending, and enables Send only when the displayed and stored drafts agree. New draft epochs reset the buffer; draft-save errors reconcile it with the stored draft.
- Back clears editing/focus without sending or discarding the draft. Hardware/pre-IME Back is handled at the field; the shared Back handler covers dispatched navigation events. A visible-to-hidden IME transition also ends editing. Attachments and multiline content still keep their expanded layout. Opening attachments or recording clears editing.
- Camera permission requests use unique Activity Result registry keys and capture their own draft epoch. Restored requests can receive a normal result, including a result queued before registration. A new explicit Camera action replaces a stale request; Files and Photos cancel it and remain usable. No arbitrary timeout, automatic re-prompt or automatic camera launch on resume is used.
- Consumed/cancelled requests unregister their callbacks. Old callbacks cannot complete a newer request, and permission is checked again before accepting a grant. Old saved boolean-only state is ignored rather than locking out attachment actions. The host unregisters on destruction.
- Permission dialog selectors are isolated in a helper, scoped to known AOSP/Google controller packages, and require an enabled, clickable button. Unsupported selectors/controllers fail with manufacturer/model/API/package diagnostics without collecting personal UI text or images. This is explicitly an emulator smoke test, not evidence of universal OEM compatibility.

## Regression Coverage

- Original composer test: focus, expansion, typing, Back collapse, retained draft, reopening and Activity recreation.
- Deterministic no-software-keyboard fixture: focus and Back in full and floating layouts, plus opening/dismissing attachment options without losing the draft.
- Delayed draft acknowledgements: immediate text echo, preservation of newer typing after an older acknowledgement, Send readiness, external draft updates and reset on a new draft epoch.
- Five controlled-registry tests: restored missing callback/retry/late result; queued restored result/original epoch/duplicate result; switching away from Camera; denial/retry/current permission recheck; legacy saved pending flag. These are Android registry integration tests, not actual OS process-death tests.
- Real system dialog: denied request remains over Assistant, grant from floating drawer opens one camera, cancellation returns to the original draft.
- `scripts/verify-composer-keyboards.sh` repeats two focused tests twice with each software-keyboard setting. The shown-keyboard scenario asserts actual IME visibility. The prior emulator setting is restored even after a failed test.

## Validation

- Passed: `./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:testDebugUnitTest :androidApp:lintDebug :ui:compileKotlinJvm --console=plain`. Shared JVM and Android local suites: 23 and 6 tests respectively. Lint: zero errors, 23 warnings. Compiler deprecation warnings remain, including the Compose Multiplatform BackHandler compatibility API.
- Passed: `env ANDROID_SERIAL=emulator-5554 bash scripts/verify-secure-qa.sh`: 39 tests, zero failures, including the previously failing composer test and delayed-input regression. Report: `androidApp/build/reports/secure-qa-instrumentation.txt`.
- Passed: `env ANDROID_SERIAL=emulator-5554 bash scripts/verify-composer-keyboards.sh`: all four two-test runs passed (eight executions). Both keyboard modes were tested twice. Reports: `androidApp/build/reports/composer-keyboard-{0,1}-{1,2}.txt`. The emulator's original keyboard setting, `0`, was restored.
- Not run: physical S24 Ultra/One UI permission controllers and predictive gestures, OS process-death during permission prompts, reboot restoration and provider-specific picker matrix.

AUDIO-002 remains explicitly deferred: backgrounding an active recording can retain buffered audio as an unsent attachment. Passing this regression suite does not clear that privacy issue or establish release readiness. No emulator was started, stopped or wiped; no commit, push or branch change was made.
