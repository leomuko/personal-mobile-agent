# Inline Recording and Attachments

27 September 2026; updated 28 September 2026. Status: core implementation complete with emulator regression evidence; S24 Ultra acceptance and advanced capabilities remain pending. The detailed contract below remains the target, not a claim that every recovery/analysis enhancement is complete. See [implementation results](../docs/COMPOSER_IMPLEMENTATION_RESULTS.md).

## Implementation Checkpoint

- C00 package organization is implemented within the existing three modules.
- Durable SQLDelight attachment links and encrypted media, Files/Camera/Gallery entry, image previews, combined messages, and inline recording/audio review are implemented.
- Local, instrumented and separate-process restart tests cover synthetic draft/sent attachment restoration, camera return, microphone capture, persistence failures and existing privacy/overlay regressions.
- No local transcription, OCR, document parsing or PDF page renderer is connected. Documents use stable filename/type/size tiles. Failed imports require removal and reselection; automatic resumable import is not implemented. Camera retake uses remove and capture again.
- Physical S24 Ultra/One UI picker behavior, reboot, interruption/audio-focus handling, accessibility and full adaptive-layout acceptance still require device QA. Capture stops on host pause; there is no silent background recording or automatic resumption.

## Outcome

One conversation and one draft across the floating drawer and full app. Users can record without leaving chat, or combine a prompt with multiple images/documents before sending. Preserve the mascot, white/mint/green palette, existing side navigation, and explicit user control.

## Composer Visual Revision: Implemented and Emulator-Tested

28 September 2026. The user's Gemini screenshots refine layout and positioning, not the app's identity or capabilities. This revision supersedes earlier composer tile/control and source-sheet presentation choices. The visual revision is implemented without storage/schema changes; verification and remaining device checks are recorded in [composer visual results](../docs/COMPOSER_VISUAL_RESULTS.md).

### Compact and expanded input

- With the keyboard closed and no attachments or multiline draft, show one compact pill-shaped composer: plus icon, flexible prompt field, microphone, circular upward-arrow Send. The mic is inside the same container, immediately left of Send, vertically centered on the input row. It must not float outside, stack above Send, or occupy a separate button row in this state.
- Keep microphone and Send positions stable for empty and short populated drafts. Send remains visible but disabled when there is no sendable content, storage is unavailable, or an attachment is importing/failed. Tapping the mic starts the existing inline recorder without opening the keyboard first.
- Focus/keyboard opening, multiline content or attachments expands the same container. Place attachment tiles at the top, the bounded multiline prompt below, and a bottom action row with plus on the left and mic/Send together on the right. Closing the keyboard collapses only when content fits the compact state; never hide attachments or truncate a multiline draft just to force a pill.
- Retain the existing white/mint/green identity. Do not add Gemini's model selector, Live button, branding, blue treatment or background. Account for keyboard and navigation insets without double padding in either host.

### Shared attachment presentation

- Images, documents and temporary audio attachments use a consistent compact tile height, rounded shape, spacing and top-right removal affordance within a horizontally scrolling strip inside the composer.
- Images show a thumbnail without filename/size furniture; documents show a type icon, shortened filename and extension; audio uses a waveform/file icon and short label with existing playback retained. Details remain available in preview. All tiles expose meaningful accessible names.
- Keep import progress and failure visible within each tile. Provide at least 48 dp interaction targets for removal and actions without overlapping adjacent hit regions. Preview shows the complete image even if the thumbnail is cropped; original bytes remain unchanged.
- Sent messages reuse the visual tile language, grouped with their text, without draft-only remove controls. Keep encrypted storage, ordering, draft restoration, existing file limits and explicit submission unchanged. No database migration is expected.

### Three-option source sheet

- Replace the camera entry icon with plus, labelled "Add attachment". Open a content-height bottom sheet with a drag handle and exactly three icon-above-label option cards, in this order: Photos, Camera, Files.
- Fit all three equally sized options across normal phone widths; use an adaptive wrapped layout for narrow windows or large text rather than clipping a fourth-looking partial card. Use comfortable spacing and reference-like rounded option shapes. Do not add a large empty sheet body.
- Photos replaces the displayed Gallery label and keeps the existing multiple-image system picker. Camera keeps capture-and-return behavior. Files keeps multiple selection with `*/*`. The Android picker presentation remains OS-controlled.
- Do not include Avatar, Images generation, Videos, Music, Canvas, descriptions or additional menu rows. Dismiss the keyboard when opening the source sheet; preserve the draft and attachments. Close the sheet before launching the selected platform flow. Cancellation and Back do not discard the draft or dismiss the chat underneath.

### Delivery and validation

1. Refine common Composer layout and explicit compact/expanded presentation; keep draft ownership in the existing session.
2. Refactor attachment tiles for mixed types and both draft/sent contexts; allow thumbnail crop versus full preview fit without changing storage.
3. Extract/refine AttachmentSourceSheet, update resource-backed Photos labels, icons and accessibility semantics. Preserve platform launchers and permission behavior.
4. Test empty/short/multiline input with keyboard open/closed, mixed attachments, loading/failure, remove/preview, sheet dismissal, Send enablement, and inline recording. Check full app and floating drawer, narrow widths, large fonts, RTL and S24 Ultra keyboard/picker behavior. Re-run persistence and process-restoration regressions.

Dictation-first audio is explicitly deferred as AUDIO-001 in [DEFERRED_ISSUES.md](DEFERRED_ISSUES.md). This visual revision must not remove working audio review before a local transcription path is implemented and verified.

Recording reference: [approved inline concept](design/approved/05-inline-recording.png). The mockup is design evidence, not evidence of model or recording functionality.

Before implementation, follow [C00: codebase organization](CODEBASE_ORGANIZATION_PLAN.md). Organize by feature inside the existing modules, with feature-local screen/components/viewmodel packages and module-owned di packages. This is a behavior-preserving prerequisite, not a new product phase.

## Pre-Implementation Foundation

- `ui` commonMain: CompanionScreen currently has a text composer whose microphone and camera callbacks both launch CaptureActivity. ConversationViewModel observes the application-scoped ConversationSession.
- `androidApp`: MainActivity and DrawerActivity share the conversation but own separate lifecycles. CaptureActivity previews an in-memory photo or microphone amplitude; neither flow currently creates a durable attachment or transcript.
- `shared`: SQLDelight currently persists messages, a text draft, and full-text search. Android uses the encrypted SQLCipher driver. There are no attachment tables or encrypted media repository yet.
- Koin already wires storage, session, and lifecycle ViewModels. Extend those boundaries; do not introduce a second database/session or another navigation framework.
- UI-001/UI-002 have implemented emulator-tested fixes; preserve independent drawer task behavior and mascot visibility during picker/camera transitions. S24 Ultra acceptance is still pending.

## Interaction Contract

### 1. Inline recording

Tap the microphone to request permission, if needed, then replace only the composer input controls with a compact mint recording bubble. Keep the conversation and header visible in both hosts. Do not navigate to CaptureActivity for audio.

- Show microphone state, elapsed time, and a waveform derived from actual microphone samples, not a decorative looping animation.
- Cancel stops capture, discards its temporary audio, and restores the pre-existing text and attachments.
- Stop finalizes audio into an unsent review state with playback, remove/re-record, and an explicit option to keep the audio. If a local speech model is ready, expose cancellable transcription and an editable result. Otherwise retain a usable audio review with an honest transcription-unavailable state.
- The user explicitly sends a voice attachment or accepts transcript text into the prompt. Transcription-only processing deletes temporary raw audio when no longer needed; retaining a voice note is an explicit choice.
- Never auto-send, execute an action, or add partial transcription to a committed message. Preserve existing draft text; let the user accept transcript insertion rather than overwriting it.
- Disable attachment launch and host expansion while actively recording; stop/review first. Back asks whether to discard the active clip. Home, lock, host loss, permission revocation, or audio interruption releases the microphone and marks the clip interrupted; valid buffered audio may be reviewed on return, but capture never resumes silently.
- Rotation must never start a second recorder. Use one capture owner and an explicit reattachment policy; if continuity cannot be retained, recover to an interrupted review state. Process death never restores an active microphone session.

### 2. Attachment entry sheet

The plus button is the attachment entry point, labelled "Add attachment". It opens a compact app-owned bottom sheet containing only three icon-above-label option cards in this order: **Photos**, **Camera**, **Files**, as specified in the visual revision above.

Opening/cancelling the sheet preserves prompt text, attachments, and chat position. Dismiss it before launching a system picker or camera. This sheet is distinct from the floating conversation drawer. Back closes the topmost sheet/preview first, not the conversation.

### 3. Files

Use Android's Storage Access Framework through `OpenMultipleDocuments` with `*/*`. Allow multiple selectable document types, not a PDF-only filter: text, PDF, office files, spreadsheets, presentations, and other provider-exposed files can be attached.

Show each item as a compact filename/type/size chip with remove and import status. All-types selection does not mean universal parsing: unsupported, encrypted, or malformed formats remain clearly marked as unavailable for analysis. Do not execute scripts, macros, embedded code, or external document links. Formats beyond the existing text/PDF roadmap need separately tested local extractors, not implied support.

### 4. Camera

Launch the existing CameraX host only after the user selects Camera. Request camera permission just in time. On successful capture, finish the capture host and return directly to the originating chat with an unsent image thumbnail in the composer. Do not require a standalone post-capture confirmation screen or open the full app behind the drawer.

The thumbnail can show import progress while encrypted storage completes; submission waits for completion. Tapping it opens an optional preview with remove/retake. Capture failure stays recoverable in camera; cancellation returns without altering the draft. Return a small capture/request identifier, never a Bitmap or image bytes through navigation/Binder.

### 5. Photos (Gallery Picker)

Use `PickMultipleVisualMedia` with `ImageOnly` for Android's privacy-preserving system image picker. Prefer its native sheet presentation; the exact height/layout and fallback are OS-controlled and require S24 Ultra/One UI verification. Do not recreate a full media library or request broad photo/storage access merely for selection.

Append multiple chosen images to the current draft. Apply the app's own count/size validation even when a picker fallback does not enforce selection limits. Cancellation keeps the draft unchanged.

### 6. Combined message composer

Place selected image thumbnails and document/audio chips in an attachment strip above the editable prompt, inside the same composer. Provide add-more, preview, individual removal, import progress, retry, and error states. Keep the text field and Send visible with the keyboard open; cap strip height and allow scrolling rather than covering the chat.

Send commits one message containing text plus an ordered attachment list. Attachment-only messages are valid; an empty prompt with no attachments is not. Never silently drop failed items: block Send until each is ready or explicitly removed. Disable duplicate submission and keep the full draft on failure. On success, clear only the submitted draft revision, preserving newer edits/imports. Render its images/files together with its text in history.

### 7. Reopening chat and restoring previews

Every successful selection through Files, Camera or Gallery becomes a durable draft attachment, not a temporary preview. Returning from a picker/camera and later reopening the app or mascot drawer must show the same attachment tiles in the same order beside the restored unsent prompt. Sending is not a prerequisite for saving a draft attachment, and reopening never auto-sends it.

- Images selected through any entry point, including Files, show actual image thumbnails. A supported document renderer may provide a first-page thumbnail; otherwise show a stable document tile with file-type icon, filename, and size. Arbitrary documents must not disappear merely because thumbnail generation or analysis is unsupported. Retained voice notes use an audio tile with duration/playback, not an invented image preview.
- Render restored pending attachments in the composer. Render sent attachments in their original message together with its prompt text; do not reattach sent files to a new draft. Both hosts observe the same durable state, not separate attachment lists.
- Persist the ordered draft link and encrypted file before marking an import Ready. Show per-item progress until then. Copy selected provider content into app-owned encrypted storage; completed imports remain usable if the original is moved/deleted or provider permission later expires.
- On startup, load draft text and attachment metadata from SQLDelight, show stable placeholder tiles immediately, then load/decrypt thumbnails off the main thread. Regenerate missing thumbnails from the protected original when supported. Preview failure must not delete the underlying attachment or block valid sending.
- Restore completed imports after process death/relaunch and reboot. If termination occurred mid-import, resume only with valid access to the selected source; otherwise retain a visible interrupted item with Retry/reselect and Remove. Never label partial bytes as Ready or silently retry network access. Persist available selection metadata as early as possible, but do not promise recovery of a picker result that was never delivered or saved.
- Explicit removal/clear deletes the draft reference and any now-unreferenced media. Cancelling a picker does not remove existing selections. A late import callback cannot restore an item the user already removed. Missing/corrupt originals show an unavailable tile with recovery controls; key failures follow the existing non-destructive storage error path.
- Persist only opaque storage/thumbnail keys and metadata, never Bitmaps or content bytes in saved navigation state. Keep generated thumbnails encrypted if stored on disk; no plaintext image-loader disk cache. Bound decoding and render a small visible preview set rather than loading full-resolution attachments at startup.

Acceptance includes normal close/reopen, drawer-to-full-app switching, rotation, process termination/relaunch, and reboot, both before and after Send. Uninstall, app-data clearing, cryptographic key loss, and explicit deletion are not recoverable-draft guarantees. Temporary conversations, when implemented, retain their separate non-persistence policy rather than using the durable draft by default.

## State, Storage, and Ownership

Extend the existing session/controller/repository pipeline, with immutable state and typed errors. UI emits events; repositories own persistence, import validation, and cleanup. No new domain module or pass-through use cases are needed.

- Composer state: text, ordered attachment IDs, draft revision, submission state; input mode Editing / PermissionPending / Recording / Finalizing / Reviewing / Interrupted.
- Per-item state: Importing / Ready / Failed, with a separate analysis capability/status. Successful storage is not successful OCR or model understanding.
- Events: choose source, import selected items, remove/retry, start/cancel/stop recording, accept transcript, and submit snapshot. Permission/picker launches are one-time effects with request IDs, not replayable state booleans.
- Application-scoped session owns the common draft. A single foreground-bound capture coordinator owns audio resources; Activities own launchers and lifecycle callbacks. Koin injects these collaborators without retaining Activity references in application singletons.
- Persist attachment metadata in SQLDelight: stable ID, display name, MIME type, byte size, hash, opaque storage key, origin, lifecycle state, and timestamps. Add ordered draft/message attachment relationships and an import journal. Store blobs outside SQLite in encrypted app-private storage.
- Use per-file authenticated encryption with platform-protected keys for original media and persistent thumbnails. Bound in-memory decoding; avoid plaintext image/audio files and disk caches. Keep file metadata in the encrypted database, not logs or raw filenames on disk.
- Import content on IO with bounded streams and actual-byte limits. Stage encrypted content, atomically finalize files, then mark metadata ready. Filesystem writes and SQL cannot share one transaction: recover interrupted imports and orphan files from the journal on startup.
- Send transfers ready draft references to the new message in a database transaction with an idempotency key and draft revision check. Existing messages/text drafts must survive forward schema migration; no destructive reset.
- Capture/picker results target the draft/request that launched them. Clear/delete/cancel invalidates late callbacks, so completed imports cannot repopulate a discarded draft. Retain URI permission only as needed for copying/recovery; source URIs are not durable content storage.
- Removing an unsent item cancels its import and deletes unreferenced files. Clear history deletes linked media only when no remaining draft/message/source references it. Process restart restores durable drafts and labels incomplete media rather than claiming it is ready.

Initial proposed, configurable guardrails: 10 attachments per draft, 25 MiB per item, 100 MiB total, and 5 minutes per recording. These are engineering starting values to validate on S24 Ultra, not model limits or fixed product promises. Also bound image dimensions/decode memory, parser pages, processing time, and decompressed bytes. Unknown file size is checked while streaming; low storage and oversized items produce per-item errors without losing successful imports.

## Platform and Privacy Boundaries

Keep shared contracts and UI platform-neutral; Android Uri, CameraX, AudioRecord, permissions, and Activity Result launchers stay in Android code. JVM tests use fake media adapters. No iOS host is added in this slice.

System Files/Gallery providers may expose cloud-backed content. Do not describe picker access as guaranteed offline: locally available content should import in airplane mode, while unavailable provider content gets a cancellable error/retry path. The app must not automatically upload attachments, invoke cloud inference, or initiate external lookup. An external provider's own network behavior and window security are outside the app's control.

Preserve secure-window policy for app-owned chat, camera, attachment previews, and sheets. Keep model readiness distinct from file import readiness. OCR can later extract image text; arbitrary visual understanding requires a separately integrated and evaluated local vision model. Until then, never claim an image/file has been understood merely because it is attached.

## Implementation Order

These packages fit the existing P0-P7 roadmap; they do not create extra phases.

| Package | Work | Exit check |
| --- | --- | --- |
| C00: Package organization | Feature-local UI and ViewModel packages, module di packages, split mixed-responsibility files, mirrored tests | Existing behavior/build/tests unchanged; stable Android components, database identifiers and scopes |
| C01: Shared draft and storage | Attachment contracts, schema migration, encrypted blob repository, import journal, Koin wiring, atomic submit | Existing chats migrate; unsent text and ordered attachment references survive restart; failed/retried Send cannot duplicate or lose attachments |
| C02: Composer and sheet | Extract Composer, AttachmentStrip, AttachmentSourceSheet, RecordingBubble and review UI in commonMain | State-driven fixture previews in both hosts; Files/Camera/Gallery sheet and draft chips work without direct platform calls |
| C03: Files and Gallery | Android result launchers, multi-select, validation, encrypted imports, retry/removal | Select mixed documents and several images, retain prompt, cancel safely, send one combined message |
| C04: Camera return | Refactor camera-only CaptureActivity result flow and preview | Capture returns to the same chat with thumbnail; no duplicate app task or mascot overlap |
| C05: Inline recording | Extract recorder from CaptureActivity, lifecycle-bound capture, waveform, encrypted review/playback | Mic never opens another screen; Stop/Cancel/interruption work; speech integration remains capability-gated |
| C06: Integrated QA | Migration, cancellation/races, privacy, lifecycle, layout and S24 checks | Files/Camera/Gallery previews restore before and after Send across process restart/reboot; unsupported capabilities explicitly reported |

C01/C02 extend P1's persistent composer, C03 extends P2's source intake, C04 brings camera attachment UX forward ahead of P5 OCR/ledger work, and C05 belongs to P3. Local model/transcription/extraction work remains in its existing milestones. UI fixture work can overlap C01, but real personal-media capture/import waits for protected storage.

Likely changes, using current names before C00: CompanionScreen.kt, ConversationViewModel.kt, Conversation.kt, ConversationSession.kt, Conversation.sq/migrations, SharedModule.kt, AndroidStorageModule.kt, AppModule.kt, MainActivity.kt, CaptureActivity.kt, and common/Android string resources. Use the destination packages in CODEBASE_ORGANIZATION_PLAN.md for new attachment contracts, components, and Android adapters. Keep current module boundaries and avoid unrelated navigation refactors.

## Tests and Acceptance

- Shared commonTest: input-mode transitions, Stop/Cancel, draft revisions, attachment ordering, limits, partial failures, late callbacks, duplicate submit, and clear/import races using deterministic fake adapters.
- JVM SQL tests: upgrade existing text-only database, message/attachment transaction, draft restore, reference cleanup, interrupted import recovery, and idempotent retry.
- Android instrumentation: actual encrypted file bytes/reopen/key failure; denied/revoked microphone and camera permissions; URI access loss; camera result delivery; recorder release; process recreation and interrupted imports. Never use personal documents as fixtures.
- Compose tests: entry sheet choices, recording stays inline, waveform/timer semantics, attachment previews/removal, multi-image prompt and file-only send, no accidental submit on Stop, large text, RTL, keyboard, compact floating drawer and landscape.
- Preserve existing UI-001/UI-002 regressions, secure-window checks, and notification/mascot controls. Run baseline debug build, shared tests, lint, relevant JVM UI compilation and designated secure QA instrumentation; report not-run checks separately.
- S24 Ultra manual evidence: actual multi-image picker presentation; multiple mixed files; capture-return from drawer over another app; combined prompt submission; cancellation; rotate/lock/call interruptions; process kill; storage pressure; airplane-mode local import; TalkBack/reduced motion. A successful build is not proof of device behavior.
- Restoration matrix: for each of Files, Camera and Gallery, verify pending and sent previews after reopen, host switch, process restart and reboot; test original deletion after successful copy, revoked provider access, failed preview generation, missing thumbnail regeneration, interrupted imports, and explicit removal followed by a late callback. Use image, supported document and unsupported document fixtures, with offline checks after import completion.

Completion demo: type a prompt, add two Gallery images, add documents from Files, take a photo and return to the same draft, close/reopen without sending and verify every thumbnail/file tile and the prompt, then terminate/relaunch and verify again. Remove one item, send once, restart and inspect the combined message with no duplicate draft attachments. Repeat the persistence check after reboot. Separately record inline, cancel without losing the draft, record again, stop/review, then explicitly send. Demonstrate transcription/analysis only when its local model is actually integrated and ready.

## Platform References

- [Android photo picker](https://developer.android.com/training/data-storage/shared/photo-picker): multi-image selection, URI grants, provider content and fallback behavior.
- [Android document access](https://developer.android.com/training/data-storage/shared/documents-files): user-selected document access through system providers.
- [CameraX image capture](https://developer.android.com/media/camera/camerax/take-photo): capture callbacks and camera implementation boundary.
