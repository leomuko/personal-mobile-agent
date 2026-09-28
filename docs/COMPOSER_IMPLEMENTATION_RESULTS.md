# Composer Implementation Results

28 September 2026. Branch: `dev`. Implementation checkpoint, not full release approval or completion of Phase 0 model feasibility work.

## Delivered Behavior

- The microphone stays in the current chat, replacing the composer controls with a recording bubble, actual microphone waveform, elapsed time, Cancel and Stop. Stop produces an unsent WAV attachment with playback; Cancel discards capture. Existing text and other attachments are retained. Sending remains explicit.
- The attachment button opens Files / Camera / Gallery. Files supports multiple provider-exposed document types via `*/*`; Gallery uses the Android multiple-image picker without broad storage permission.
- CameraX capture returns directly to the originating chat with an unsent image. Images have thumbnails and previews; documents have filename/type/size tiles. Removing and capturing/selecting again replaces an unwanted item.
- Draft and sent attachment order, text and links persist in SQLDelight. Completed imports use app-owned encrypted originals, not a lasting dependency on a provider URI. Reopening restores pending tiles alongside the unsent prompt; sent attachments remain with their original message.
- Both chat hosts observe one application-scoped session. There is no second conversation/database for the drawer.

## Structure and Pattern

The existing MVVM-style presentation and unidirectional state flow remain: Compose emits events to the feature ViewModel/session, the controller validates state transitions, and storage implementations persist changes. Koin owns construction. Platform capture and picker APIs remain Android-specific.

- `ui/.../conversation/{screen,components,viewmodel,presentation}` owns conversation UI; `app`, `navigation`, `designsystem`, `settings`, `memory` and `sources` own their respective concerns.
- `shared/.../conversation/{model,data,session}` owns conversation state and persistence; `attachments/{model,data}`, `storage/database`, `time`, `inference` and `di` contain focused contracts and implementations.
- `androidApp/.../{attachments,capture,di,navigation,overlay,permissions,privacy}` contains platform bridges. Manifest entry-point class names remain stable. CaptureActivity is camera-only; MainActivity and DrawerActivity host inline audio.
- Module dependencies remain `androidApp -> ui -> shared`, with Android also depending directly on shared for platform composition. Existing application IDs, conversation database identity and conversation encryption key are retained.

## Persistence and Bounds

SQLDelight schema version 3 adds ordered attachment records and a draft epoch through migration, preserving existing messages and draft text. Sending inserts the message, links attachments, clears the submitted draft and advances the epoch in one database transaction. A retry after a committed send but failed UI refresh reconciles state rather than duplicating the message.

Android originals are stored in the no-backup directory using AES-GCM and an Android Keystore key. Files use opaque identifiers, authenticated IDs and atomic writes. Thumbnails are decoded off the UI thread and regenerated in memory; no plaintext thumbnail disk cache is added. Missing keys are not silently replaced over existing encrypted media.

Limits: 10 attachments, 25 MiB per file, 100 MiB per draft, and five minutes per recording. Import work is serialized and input sizes are checked. Draft epochs reject stale picker results; removal/clear prevents late imports from resurrecting deleted attachments. Interrupted imports become failed items on restoration and block Send until removed/reselected. Startup cleanup runs only after database restoration succeeds.

Database transactions and file writes are separate operations, so failure/reconciliation paths remain necessary. This is not a claim of absolute privacy, secure deletion from flash, universal file decoding or universal offline provider availability.

## Verification

Synthetic fixtures only; no personal documents or audio uploaded.

- Passed: debug and release APK builds and JVM UI compilation. All 23 shared JVM tests and six Android local tests passed, with no failures or skips. Debug lint passed with zero errors and 23 dependency/target freshness warnings; those upgrades remain outside this change.
- Secure-QA instrumentation: 25 tests passed on the designated headless Pixel 7 Pro emulator. This includes real emulator CameraX capture returning to chat, inline AudioRecord capture/review, recreation, source-sheet/presentation checks, attachment encryption/tamper/key-loss tests and existing overlay/privacy regressions.
- Separate-process restoration: seed and verify each passed in different application processes. Encrypted synthetic image bytes and pending/sent metadata are readable after the QA process is stopped and restarted. This is process-restoration evidence, not a reboot or full device UI acceptance claim.
- Manually inspected synthetic component screenshots: [recording](../planning/screenshots/qa-inline-recording.png) and [attachment draft](../planning/screenshots/qa-attachment-draft.png).

```sh
./gradlew :androidApp:assembleDebug :androidApp:assembleRelease \
  :shared:jvmTest :ui:compileKotlinJvm :androidApp:testSecureQaUnitTest \
  :androidApp:lintDebug -PtestBuildType=secureQa --console=plain
ANDROID_SERIAL=emulator-5554 bash scripts/verify-secure-qa.sh
ANDROID_SERIAL=emulator-5554 bash scripts/verify-attachment-restart.sh
```

Reports are in module `build/reports` and `build/test-results`, including `secure-qa-instrumentation.txt` and `attachment-process-restart.txt`. The scripts target the isolated `.secureqa` package; they do not clear the normal app's data.

The QA emulator was shut down after verification without wiping its data.

## Remaining Acceptance and Limitations

- S24 Ultra physical testing is not completed: One UI picker presentation, overlay transitions, camera/microphone permission recovery, calls/audio focus, background/lock behavior, reboot restoration and encrypted-storage behavior need device evidence.
- System Files/Gallery selection across real providers has not received end-to-end automation. Provider-hosted cloud content may need connectivity before it can be imported. The app itself has no internet permission.
- Full large-font, RTL, landscape, keyboard and accessibility interaction matrices remain pending. Screenshot inspection covers selected synthetic component states only.
- No speech-to-text, local model inference, OCR, PDF page rendering or arbitrary document analysis is connected. Audio review supports the app's PCM WAV recordings; attachment support does not imply playback of every audio format.
- Failed imports use remove/reselect, not automatic resume. Advanced camera editing/cropping, dedicated retake, EXIF normalization of imported gallery images and richer interrupted-recording presentation remain follow-up work.
- Memory and Sources still have placeholder content. Phase 0 runtime/latency/memory feasibility gates are not complete.

No commit, push or branch change was performed.
