# Codebase Organization and Refactor Plan

27 September 2026; implementation checkpoint 28 September 2026. Status: C00 implemented. Feature packages now separate screens, components, ViewModels, data, capture, storage and DI within the original three modules. The review and target layout below document the pre-change rationale; not every suggested filename is required. See [implementation results](../docs/COMPOSER_IMPLEMENTATION_RESULTS.md) for actual ownership and verification.

## Original Review Findings

Verdict: the existing module boundaries are a good fit; package organization and responsibility separation need revision before adding more capture/composer features. This is a scoped architecture review, not a fresh release-readiness or runtime audit.

- Medium maintainability risk: `ui/.../CompanionScreen.kt` contains theme/colors, reusable mascot/tool controls, the application shell, settings, destination placeholders, chat messages, and the composer. Feature changes currently share one file and broad root package.
- Medium maintainability risk: `shared/.../Conversation.kt` mixes message/state/error models, the storage contract, SQLDelight implementation, and controller. Split ownership without rewriting the working persistence/session design.
- Medium growth risk: CaptureActivity combines camera UI, camera acquisition, microphone acquisition, waveform state, and cleanup. Move its existing implementation into focused Android capture files during C00; behavior changes and new capture ownership belong to C04/C05, not the mechanical move.
- Low organizational risk: AppModule sits beside Activities, PresentationResources mixes destination definitions and error mapping, and MainActivity.kt contains DrawerActivity. Dedicated DI/navigation/feature files make these responsibilities discoverable.
- Functional gap relevant to the new requirement: Message has only ID/text/time and the draft stores only text. There is no attachment record, durable media store, or restored thumbnail pipeline. A package cleanup alone cannot make selected files survive restart; C01-C06 must implement and prove it.

What should stay: `androidApp -> ui -> shared`, plus Android application's direct dependency on shared for platform composition. SQLDelight, Koin, StateFlow session ownership, lifecycle ViewModels, and Android/JVM targets remain. There is no reason to introduce a Gradle module for every feature at this size.

## Organization Rules

Use feature-first packages with small responsibility folders within a feature. For example, keep chat's screens, components, and ViewModel together under `conversation/`, rather than putting every feature's ViewModel in a global folder far from its UI.

- Match Kotlin package declarations to directories under each source-set package root. Use lower-case package names and descriptive file names.
- Give independently owned classes a matching filename. Closely related small models can share a named model file; do not require one trivial enum per file.
- Keep `di/` in each module that already owns bindings. Construction belongs there; Clock and database lifecycle contracts should live with their owners, not be defined inside a DI file.
- Use `screen/`, `components/`, `viewmodel/`, and `model/` only where they contain real feature code. Do not pre-create empty feature layers or extra ViewModels for simple placeholder screens.
- Put genuinely reusable theme and UI controls in `designsystem/`. Keep chat-only attachment controls in the conversation feature; avoid generic `utils/` dumping grounds.
- Keep native types, permissions, launchers, and lifecycle bridges in Android source sets. Keep SQLDelight-generated types behind data implementations where practical.
- Use `internal`/`private` for implementations that have no cross-module consumers. Packages improve organization, not access enforcement: Kotlin `internal` remains module-scoped. Keep deliberately public host APIs and platform contracts usable.
- Tests mirror their owning packages within existing commonTest, jvmTest, test, and androidTest roots. Resources stay in Compose/Android resource directories; do not move them into Kotlin feature folders.

## Target Package Layout

Paths below start at their existing Kotlin package roots. Entries marked future are created only when their composer work package needs them.

```text
ui/src/commonMain/kotlin/dev/edgecompanion/ui/
  app/CompanionScreen.kt                 # thin shared app shell
  navigation/
    AssistantNavigation.kt
    AssistantDestination.kt
  designsystem/
    theme/CompanionTheme.kt
    components/Mascot.kt
    components/ToolButton.kt
  conversation/
    screen/ConversationScreen.kt
    components/ConversationList.kt
    components/Composer.kt
    components/AttachmentStrip.kt        # future
    components/AttachmentSourceSheet.kt  # future
    components/RecordingBubble.kt        # future
    viewmodel/ConversationViewModel.kt
    presentation/ConversationErrorResources.kt
  settings/screen/SettingsScreen.kt
  memory/screen/MemoryScreen.kt          # existing empty state only
  sources/screen/SourcesScreen.kt        # existing empty state only

shared/src/commonMain/kotlin/dev/edgecompanion/core/
  conversation/
    model/ConversationModels.kt
    session/ConversationSession.kt
    session/ConversationController.kt
    data/ConversationStore.kt
    data/SqlConversationStore.kt
  storage/database/
    DatabaseDriverFactory.kt
    SessionDatabase.kt
  time/Clock.kt
  inference/Inference.kt
  di/SharedModule.kt
  attachments/                           # future
    model/Attachment.kt
    data/AttachmentRepository.kt
    data/AttachmentStore.kt
    data/AttachmentImporter.kt

shared/src/androidMain/kotlin/dev/edgecompanion/core/
  storage/database/EncryptedDatabase.kt
  storage/files/EncryptedAttachmentStore.kt  # future
  di/AndroidStorageModule.kt

androidApp/src/main/kotlin/dev/edgecompanion/app/
  CompanionApplication.kt                # stable system entry point
  MainActivity.kt                        # stable host name
  DrawerActivity.kt                      # split out; same package/class
  CaptureActivity.kt                     # stable host; delegates capture UI
  MascotService.kt                       # stable service; overlay lifecycle
  di/AppModule.kt
  navigation/AssistantIntents.kt
  overlay/MascotView.kt
  overlay/PresenceState.kt
  overlay/PresenceCoordinator.kt
  permissions/NotificationAccess.kt
  privacy/WindowPrivacy.kt
  capture/camera/CameraCaptureContent.kt
  capture/audio/AudioCaptureContent.kt    # temporary existing prototype
  capture/audio/AndroidAudioRecorder.kt   # extraction/new owner in C05
  attachments/AttachmentLaunchers.kt      # future host-owned launchers
```

The handful of system entry classes intentionally remain at their existing root names as a compatibility exception. All supporting implementation gets a specific package. Do not rename manifest components merely to achieve a visually empty root; launcher identity, explicit intents, pending intents, service controls, and test tooling depend on them. CaptureActivity loses its audio route only when C05 delivers inline recording; do not leave both routes active afterwards.

No `ui/di` package is needed yet because ViewModel bindings currently belong to the Android app composition module. If shared UI later owns reusable bindings, add it then rather than creating an empty folder or relocating Koin ownership in a package-only refactor.

SQLDelight files remain at `shared/src/commonMain/sqldelight/dev/edgecompanion/db/`. Keep the generated database package/name and existing migrations unchanged during C00. New attachment schema changes are reviewed separately in C01.

## Concrete Moves

| Current file | Planned split/move | Invariant |
| --- | --- | --- |
| CompanionScreen.kt | App shell, design system, conversation screen/list/composer, settings and existing empty-state screens | Same appearance, callbacks, state ownership, scroll behavior and drawer Back behavior |
| AssistantNavigation.kt / PresentationResources.kt | navigation/ plus conversation/presentation error mapper | Same route IDs and navigation ownership; no new navigation library |
| ConversationViewModel.kt | conversation/viewmodel/ | Same host-scoped lifecycle, same application session |
| Conversation.kt | conversation/model, conversation/data, conversation/session | Same store contract, SQL transactions, error behavior and coroutine dispatcher |
| ConversationSession.kt | conversation/session/ | One shared session across hosts; startup and cancellation semantics preserved |
| SharedModule.kt | Keep module in di; extract Clock and database owner/contracts to named packages | Same lazy database creation, binding cardinality, close behavior and qualifier identity |
| EncryptedDatabase.kt / Inference.kt | storage/database/ and inference/ | Same keys/files/driver and inference contract; no runtime/model change |
| AppModule.kt / helper files | Android di/navigation/overlay/permissions/privacy packages | Same singleton and ViewModel scopes, task/presence behavior and permissions |
| MainActivity.kt / CaptureActivity.kt | Split DrawerActivity file; extract existing capture content/helpers | Preserve component names and current capture behavior until C04/C05 |

## Safe Execution Sequence

1. Inventory all tracked and untracked source files and current working changes. Capture baseline build/test outcomes and a source file hash inventory without staging, committing, or changing branches. Retain `dev`.
2. Move shared model/data/session and DI support files, updating imports and tests together. Compile shared JVM/Android and run store/controller/DI tests before proceeding.
3. Extract/move shared UI by responsibility with unchanged semantics and modifiers. Rewire both Android hosts and UI tests. Preserve resources, route IDs, saved-state keys, and current generated resource package.
4. Organize Android helpers and split root host files as above. Update imports and fully qualified test/script references where needed. Inspect the merged manifest to confirm component identity, exported flags, affinities, secure-window policies, service type, and permissions remain unchanged.
5. Run the full existing build/lint/test baseline, JVM UI compilation, secure QA variants and designated instrumentation. Compare pre/post synthetic UI screenshots. Preserve earlier QA snapshots as historical evidence and write a new inventory for this refactor.
6. Only after this behavior-preserving checkpoint, implement C01-C06 in the new packages. Add attachment schema/storage behavior in its own reviewable change set, not hidden inside file movement.

Never delete or regenerate the database, change its filename or Keystore alias, uninstall the user app, or alter applicationId/namespace as part of C00. No dependency upgrades, new Gradle modules, API redesign, or cosmetic reformatting sweep are required. Do not edit generated build outputs.

Rollback for C00 is limited to reversing this task's moves/import edits with user changes preserved; it requires no data downgrade because C00 changes no persistent schema. C01's forward migration needs its own compatibility tests and must not assume an older binary can read a newer schema. No Git operations are implicitly authorized by this plan.

## Attachment Restoration Gate

The companion [composer plan](COMPOSER_INPUT_PLAN.md#7-reopening-chat-and-restoring-previews) now requires durable previews for Files, Camera and Gallery before and after sending. File import success means protected bytes and draft/message references have reached durable storage, not merely that a URI exists in memory.

C00 is complete when the existing behavior passes unchanged. The full input change is complete only after both pending and sent attachments survive normal reopen, host switching, process death/relaunch, and device reboot using synthetic fixtures, with interrupted imports handled explicitly. Uninstall, clear-data, key loss and user removal are not persistence guarantees.

## Guidance

This is a project-specific organization, not a claim that Android prescribes one universal folder tree. It follows [Android's cohesion and module ownership guidance](https://developer.android.com/topic/modularization/patterns) and [Kotlin package/file conventions](https://kotlinlang.org/docs/coding-conventions.html). Keep the current three modules; revisit feature modules only when ownership, build performance, or dependency isolation warrants them.
