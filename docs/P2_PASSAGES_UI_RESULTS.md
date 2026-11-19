# S5 passages UI handback

2026-11-19. S4-F7 and S5 are implemented. Host verification passed. S24 Compose execution, SQLCipher migration execution, and screenshots require the user's go-ahead.

## Behavior

- **My sources** is a switch named **My sources, answer from my sources only**. Chat and the drawer use the same stored draft scope. An empty or unavailable library disables activation and shows the add-source hint. The disabled switch announces **Off. Add a source to use this**. A checked switch can still be turned off. Sending while checked stays scoped and can return the typed no-match result.
- **Passages consulted** announces its expanded or collapsed state and reads title and preview while expanded. The lookup validates the source, revision, passage ID and UTF-16 offsets against the live record. Sources opens the exact highlighted text with surrounding revision text. A deleted record is shown as **Source deleted** without a click action. The conversation database keeps references only. A new conversation intent returns the drawer to Chat.
- The four typed outcomes are assistant messages with resource strings. Retry starts a new attempt for `NOT_INDEXED` and `RETRIEVAL_INCOMPLETE`, using the original user turn's scope and the existing context fence.
- Migration 7 creates the singleton `draft_scope` table and advances schema 7→8. An absent row means ordinary chat. Sending clears the draft text and preserves the setting. The schema 7 snapshot contains no user records.
- The passage list uses compact outlined cards and the detail screen uses the theme's highlight color.

## Composer layout

- **Idle with room.** One row contains **+**, **My sources**, the text field, mic, and Send. When the remaining compact text-field width drops below **72 dp**, the first row keeps **+**, the chip, and the field. Mic and Send move to a second row.
- **Typing or expanded.** The first input row contains **+**, the chip, and the text field. Mic and Send occupy the second row regardless of width. Attachments, when present, sit above the input rows. The typing field shares its row with **+** and the chip, so it no longer spans the full composer width as it did before S5.
- **Wrap width.** The compact field gets the inner width minus three 48 dp buttons and the measured chip width. The inner wrap threshold is **216 dp + measured chip width**, or **256 dp + measured chip width** including the 40 dp of horizontal padding. The chip measurement varies with its checked state, text, and font scale. At exactly 72 dp of remaining field width, idle stays on one row.
- Approved image 02 shows a single-row composer with **+**, the field, and mic. S5 adds the chip and the adaptive second row. QA round 1 leaves the layout in place for review against the S24 captures.

## Verification

- Shared JVM ran 289 tests, with 287 passed and two existing opt-in tokenizer tests skipped. Android host unit tests passed 32 and the UI-module host suite passed 20. SQLDelight migration verification, debug assembly, and debug lint passed. Lint reported seven warnings and zero errors.
- The UI host suite exercises the real conversation ViewModel and session with a recording store. In disposable copies, the tests caught the previous chip eligibility rule, M14, M15, and the M17 label-mapping removal. The shared JDBC suite covers actual draft storage and the send before and after an explicit opt-out with an empty library.
- `PassagesUiTest` contains six Compose cases compiled with the existing Android dependency. Coverage includes toggle semantics and shared state across both host layouts and Compose recreation, empty and unknown library availability, expander state, live and deleted references, the exact highlighted span, no-answer and Retry states, narrow large-font layout, and resetting the floating passage on a new conversation request. Device execution is pending.
- `PassageBackfillTest` expects schema 8 and passed on the host. The encrypted downgrade fixtures in `PassageSqlCipherTest`, `GroundingSqlCipherTest`, and `SourcesLibraryTest` remove `draft_scope` before reopening. The schema assertions in the first two now expect 8. `GroundingSqlCipherTest` adds an encrypted 7→8 draft-scope case. The SQLCipher gate requires `OK (9 tests)` across the two classes and rejects instrumentation status codes -1 through -4. These Android tests compiled on the host and still require the S24 run.
- All 53 tests in `scripts/tests/verify-chat-context.test.mjs` passed with synthetic ADB and Gradle and normal host process access. The two count regressions failed as expected against the previous gate. A post-run process scan found no synthetic test helpers.

## Planned S24 captures

Use synthetic sources and chat in the `secureQa` build after the user authorizes the run. The app has an existing secure-window-aware Compose capture pattern in `AppearancePresentationTest`.

1. Full-app and drawer composers at the S24's default width, idle and typing, with **My sources** off and on. Include the on state after switching hosts and after reopening the app.
2. Empty library after deleting the last source with the chip on, then after explicitly turning it off. Show the disabled unchecked chip and **Add a source to use this**.
3. Source-scoped turn with the **From my sources** label. Capture **Passages consulted** collapsed and expanded, with a source title and span preview.
4. Sources detail opened from a passage in each host, with the exact span highlighted and in view.
5. The same answer after deleting its source, showing **Source deleted**. Capture an ordinary answer without consulted references separately.
6. The four source-scoped no-answer messages. Capture Retry for `NOT_INDEXED` and `RETRIEVAL_INCOMPLETE`, plus the `NO_MATCH` and `PASSAGES_UNUSABLE` states.
7. Large font in the drawer and full app, including the composer and expanded passage list.
8. The same idle and typing composer states in both hosts at a narrow width, aiming for 320 dp. Record the actual width and whether the mic and Send wrap.
9. Drawer with a passage open, then Chat after leaving via Home and re-entering from the mascot.
