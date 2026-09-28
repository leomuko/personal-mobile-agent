# Deferred Issues

Originally deferred on 2026-09-26, then authorized for implementation with the QA remediation. Updated 2026-09-27: both fixes are implemented with emulator regression evidence. Physical S24 Ultra acceptance remains open; see `docs/QA_REMEDIATION_RESULTS.md`.

## UI-001: Mascot drawer brings the full app forward

- Priority: P1. Status: implemented; separate drawer task, close/Back cleanup and main-task reuse verified on API 36. Broader device acceptance pending.
- Locations: `androidApp/src/main/kotlin/dev/edgecompanion/app/MascotService.kt` (drawer launch), `androidApp/src/main/AndroidManifest.xml` (DrawerActivity task configuration), and `MainActivity.kt` (expand/close handling).
- Cause: DrawerActivity uses the main app's default task affinity and is launched with NEW_TASK, allowing the existing application task to be brought forward beneath the translucent drawer.
- Intended behavior: opening the drawer preserves the previous app/Home underneath; only Expand opens the full assistant.
- Acceptance: test Home and another app with main task present/absent, repeated opens, close/Back, expand, shared draft/history, and task cleanup. Do not create duplicate main activities or extra Recents entries.

## UI-002: Floating mascot obscures drawer controls

- Priority: P2. Status: implemented; coordinated overlay visibility and independent header actions covered by local and emulator tests. One UI lifecycle acceptance pending.
- Location: `androidApp/src/main/kotlin/dev/edgecompanion/app/MascotService.kt` and drawer lifecycle coordination.
- Cause: mascot overlay remains visible above the expanded drawer.
- Intended behavior: hide the floating mascot while the drawer is visible and restore it on dismissal only when the service and permission remain active and the device is unlocked.
- Acceptance: test close/Back, capture transitions, Home, rotation/recreation, lock/unlock, permission revocation, and service stop. Header actions remain visible and tappable.

## AUDIO-001: Dictation-first microphone input

- Status: explicitly deferred by the user on 2026-09-28. Do not implement as part of the composer visual revision.
- Direction: microphone input is primarily dictation. After Stop, local transcription should provide editable prompt text, with no recorded-audio attachment or playback tile by default and no automatic Send.
- Current behavior to preserve: inline waveform/timer, Cancel/Stop, and reviewable unsent audio with playback until local speech-to-text is available.
- Prerequisites: select and verify an on-device speech engine; define transcription progress/cancellation/failure, insertion without overwriting existing text, and temporary-audio retention/deletion. Do not silently lose capture on transcription failure or resume recording in the background.
- Acceptance when authorized: successful dictation updates the editable prompt; Cancel preserves the previous draft; errors are recoverable; audio is not retained as a message attachment by default; no automatic send or cloud fallback.

## AUDIO-002: Host loss retains recorded audio without an explicit Stop

- Priority: P1 privacy/consent. Status: explicitly deferred by the user on 2026-09-28; behavior intentionally unchanged in the camera-permission fix.
- Confirmed path: MainActivity.onPause() calls audio.stop() with cancel=false. AndroidAudioRecorder finalizes nonempty buffered PCM and invokes finished(bytes); the session imports the resulting WAV as an encrypted unsent attachment. close() also calls stop() without cancellation.
- Impact: Home, lock, Activity loss/recreation or other pauses may retain audio without an explicit Stop/review decision. Capture stops and nothing is automatically sent, but encrypted local retention still requires a clear consent policy.
- Proposed correction when authorized: distinguish UserStop, UserCancel and Interrupted/HostLost. Only UserStop may finalize a retained review clip by default; discard interrupted buffers, or offer a strictly temporary interrupted review with explicit Keep if the user chooses that policy. Make destroy/rotation/error cleanup follow the same policy without racing or undoing an explicit Stop.
- Regression gates: Home, lock, activity destruction, rotation, call/audio-focus loss, permission revocation and Stop-versus-pause races. Assert microphone release and no new persistent audio attachment on interruption; preserve the existing text and attachments. Explicit Stop must retain exactly one review clip; Cancel retains none.
- Independent of AUDIO-001: do not wait for transcription integration to specify consent on interruption. Still deferred until the user authorizes this change.
