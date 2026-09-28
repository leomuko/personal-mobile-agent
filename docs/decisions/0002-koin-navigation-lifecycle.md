# Koin, Navigation and Lifecycle

Date: 2026-09-27. Scope: dependency wiring and the approved side-menu navigation change. No schema, encryption or database-file migration.

## Dependencies

- Koin core/Android/test: 4.2.2, Kotlin DSL without annotations or compiler plugin.
- JetBrains Navigation Compose: 2.9.2, stable Navigation 2 rather than the newer beta line.
- JetBrains lifecycle-viewmodel-compose and lifecycle-runtime-compose: 2.10.0.
- Versions are cataloged; shared and UI modules retain Android and JVM targets. No iOS compatibility claim is made for this currently Android/JVM-configured app.

## Ownership

`CompanionApplication` starts Koin once, registers Android lifecycle callbacks and starts the session. It no longer assembles the database/controller or exposes separate mutable conversation/error/overlay stores.

`sharedModule` defines the SQLDelight database holder, ConversationStore, ConversationController and ConversationSession. `androidStorageModule` supplies the existing SQLCipher/Keystore-backed driver factory and clock. `appModule` supplies the serialized I/O dispatcher, Android presence coordinator and host ViewModel factory.

Database construction is lazy. Only asynchronous session startup resolves the controller/store/database chain. Main and Drawer obtain separate lifecycle-scoped ConversationViewModels backed by the same application session and StateFlow. ViewModels delegate writes to the session, so finishing an Activity does not cancel persistence or close storage. UI collects state with lifecycle awareness; reusable composables receive state/callbacks rather than fetching dependencies themselves.

The session owns its coroutine scope and idempotent startup job. Its startup coroutine closes initialized storage on failure or cancellation. Tests await `shutdown()` before closing isolated Koin containers. Normal Android process cleanup does not depend on `Application.onTerminate()`. No Activity or camera resource is a Koin singleton, and no database key is registered as a Koin property or logged.

## Navigation

The full app uses a single NavController with stable enum-backed route IDs for Chat, Memory, Sources and Settings. A top-left menu replaces bottom navigation and the Settings gear. Selecting a destination avoids duplicate entries and saves/restores destination state. The chat scroll state is owned above the destinations; the draft/history remain in the application session.

The state-aware ModalDrawerSheet handles Back before route navigation, including predictive Back support provided by Material. Back from a destination returns to Chat. Explicit expand/open-conversation intents select Chat. The floating conversation does not gain a navigation menu and retains its independent task, Expand, Close and Stop controls.

## Verification

Run builds/local checks:

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease :shared:jvmTest \
  :ui:compileKotlinJvm :androidApp:testSecureQaUnitTest :androidApp:lintDebug \
  -PtestBuildType=secureQa --console=plain
ANDROID_SERIAL=emulator-5554 bash scripts/verify-secure-qa.sh
```

Added tests cover graph laziness/singletons, startup failures, isolated overrides, shutdown, shared session across distinct host ViewModels, ViewModel retention on recreation, menu Back priority, destination restoration, RTL, draft rendering, explicit Chat requests and floating-mode separation. Existing storage/privacy/service regressions remain in the suite. Component screenshots use only synthetic state and are written to the isolated QA app cache, without weakening secure-window policy on production hosts.

Koin's Android dependency introduces AppCompat lint checks. MascotView deliberately remains a platform ImageView hosted by a Service/WindowManager on API 31+, without an AppCompat Activity theme. Its targeted AppCompatCustomView suppression documents this distinction; accessibility tests remain enabled.

Physical S24 Ultra/One UI, full TalkBack/predictive gesture, process-death and broader layout/locale acceptance remain separate device gates. Build success alone does not establish them.

### Recorded Results

The commands above passed on 2026-09-27: debug/release assembly, shared tests, JVM UI compilation, Android local tests and lint. There are 15 shared JVM tests and 6 Android local tests, all passing. The permission-isolated API 36 Pixel 7 Pro emulator run reports `OK (17 tests)` with no failures or skips in `androidApp/build/reports/secure-qa-instrumentation.txt`.

Lint reports zero errors and 23 dependency/target freshness warnings, including two new lifecycle-version availability notices. These remain a separate toolchain compatibility update; no broad upgrade or lint baseline was introduced. The intermediate menu Back failure was corrected by using the state-aware drawer sheet, then tested from both Chat and Memory before the final passing run.

Synthetic component screenshots were visually inspected and saved under `planning/screenshots/qa-navigation-chat.png` and `planning/screenshots/qa-navigation-menu.png`. They are component captures, not evidence for all device configurations. SHA-256 source/build hashes are recorded separately in `docs/koin-navigation-source-snapshot.json`; the earlier remediation snapshot remains historical.

The schema, migration files and encrypted database implementation match the previous remediation hashes. The merged debug manifest still has no Internet permission. The pre-existing emulator was left running; only the isolated QA package was installed/permission-adjusted and its process stopped by the test script. No commit, push or branch change was performed.

References: [Koin startup](https://insert-koin.io/docs/reference/koin-core/starting-koin/), [Koin DSL](https://insert-koin.io/docs/reference/koin-core/dsl/), [multiplatform navigation](https://kotlinlang.org/docs/multiplatform/compose-navigation-routing.html), [multiplatform ViewModel](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html).
