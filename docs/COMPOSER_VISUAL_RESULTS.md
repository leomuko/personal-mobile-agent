# Composer Visual Revision

28 September 2026. Implements the approved Gemini-inspired input and attachment-sheet layout while preserving the assistant identity and existing storage behavior.

## Implemented

- Compact pill: plus, prompt, microphone and circular upward-arrow Send in one row. The microphone stays inside the container immediately beside Send. Empty or unready drafts cannot send.
- Opening the keyboard, entering multiline content or adding attachments expands the same composer. Its text field remains mounted across the layout transition to preserve editing focus and selection. Closing the keyboard keeps draft content and collapses only when appropriate.
- Images, documents and existing voice clips use compact, consistently sized tiles within a scrolling strip above the prompt. Images crop only in thumbnails; full previews fit the image. Documents/audio display concise labels and type indicators. Per-item removal has a 48 dp target and a filename-specific accessible label; import/error state remains visible and blocks Send as before.
- Sent attachments reuse the tile styling without remove buttons. Attachment-only messages no longer render an empty text bubble.
- The plus button opens only Photos, Camera and Files, in that order, as icon-above-label cards. At normal phone widths all three share a row; large text/narrow layouts wrap. The sheet has no extra menu sections. Photos still invokes the existing multiple-image picker; Files and Camera retain their prior platform behavior.
- Theme surfaces are explicitly neutral/mint rather than the Material default lavender. Rounded input and option shapes follow the approved reference.

## Boundaries Preserved

No database migration, new dependency, session ownership change or attachment-storage change. The existing SQLDelight/Koin/MVVM-style pipeline remains. Recording stays inline with its current stop/review/playback behavior. Dictation-first transcription is still deferred as AUDIO-001; no speech recognition or new model capability is claimed.

## Validation

Passed: debug/release APK builds, JVM UI compilation, 23 shared JVM tests, six Android local tests and 31 emulator instrumentation tests. Lint passes with zero errors and 23 existing target/dependency freshness warnings. The two-stage separate-process encrypted-attachment restoration check also passes. UI tests use synthetic fixtures in the separate `.secureqa` application; no normal-app data is cleared. The already-running emulator is not owned by this task and remains running.

During validation, a subpixel card-width rounding issue was corrected so all three source cards fit a normal-width row. The sheet now preserves the host's density/layout direction in its dialog content. Tests use system Back for the dialog, rather than incorrectly bypassing it through the host Activity's dispatcher.

Added coverage includes compact mic/Send alignment, empty-draft Send disablement, mixed tiles and removal versus preview, pending/failed Send blocking, sent-message removal restrictions, large-text/RTL sheet layout and Back dismissal, and real keyboard expansion/collapse preserving focus and text. Existing camera, microphone, encrypted-storage and drawer regressions are retained.

S24 Ultra/One UI acceptance, the full landscape/keyboard/accessibility matrix, and provider-specific picker behavior remain physical-device checks. The large-font fixture covers the composer/sheet, not the entire app: the existing narrow drawer header can wrap its title awkwardly at 200% font scale and still needs a separate adaptive-header pass. Passing emulator checks is not a claim of release readiness.

## Screenshots

Synthetic test captures, stored separately from application source:

- [Compact composer](../planning/screenshots/composer-compact.png)
- [Mixed attachments](../planning/screenshots/composer-mixed.png)
- [Three-option sheet](../planning/screenshots/composer-source-sheet.png)
- [Large-text RTL sheet](../planning/screenshots/composer-sheet-large-rtl.png)

No commit, push or branch change is part of this work.
