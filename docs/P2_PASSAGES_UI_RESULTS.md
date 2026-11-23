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

## 2026-11-19: S24 SQLCipher gate after migration 7 (schema 8)

Commit `caaa5c1`, clean tree, `secureQa` on the S24 Ultra (serial ending 8D6B),
via `scripts/verify-chat-context.sh sqlcipher`. Only the staged `.secureqa`
copies were installed; no model was loaded. **PASS: `OK (9 tests)`.**

- `GroundingSqlCipherTest` 6/6, including the new encrypted 7→8 case
  (`encryptedSchemaSevenDraftMigratesOffAndRestoresStickyScopeAfterReopen`).
- `PassageSqlCipherTest` 3/3 at schema 8.
- Raw output: 9 test starts (status 1) and 9 passes (status 0); no skipped or
  failed status.

Raw results (git-ignored):
`benchmarks/results/passage-20261119T130156Z-72466-4DwmWG/`.

Still pending on the S24 (each needs the user's go-ahead): the six
`PassagesUiTest` cases (needs a runner; the script has no UI mode) and the
screenshot list above. Open P3: S5-F6, the empty-library hint also shows while
source availability is unknown; ruling: show it only when the library is known
to be empty.

## 2026-11-19: S5 device runner and S5-F6 (host only)

Host work only; nothing in this section was run on a device or emulator.

- **S5-F6.** The **Add a source to use this** hint, and the disabled chip's
  matching state description, now appear only when the library is known to be
  empty (`hasSources == false`). Unknown availability still keeps an
  unchecked chip from turning on (`ready && (hasSources == true || checked)`)
  but shows no hint. `SourcesPresentationTest` pins all three states.
  `PassagesUiTest.emptyLibraryDisablesTheChipAndKeepsItsSavedChoice` asserted
  the hint for unknown availability; it now expects the hint only for the
  empty library, and checks empty and unknown in both hosts. The count stays
  at six cases.
- **Runner.** `scripts/verify-chat-context.sh ui` runs `PassagesUiTest` (6),
  `PassagesCaptureTest` (9), `ComposerLayoutTest` (7) and `HostRegressionTest`
  (5) in one instrumentation, and requires `OK (27 tests)`. It then pulls
  exactly 40 PNGs into `screenshots/` with their sha256 in `evidence.json`.
  The deadline is fixed at 900 s. See `docs/P2_DEVICE_PREP_RESULTS.md`.

### Capture files (`screenshots/NN-name.png`, NN = planned item)

All captures use synthetic state and render the shared `CompanionScreen` in a
Compose test window, not the app's secure activity window.

**Host framing (S5R-F4).** Before any content is set, the test activity
calls `enableEdgeToEdge()` and uses `adjustResize`, as `MainActivity`
declares. The capture frame applies the same status-bar, navigation-bar and
IME padding as `MainActivity`. Inside that padding, the full app fills the
padded height. The drawer is a rounded sheet at 86 % of the padded height.

Known differences from the real hosts:
- The PNG is the frame node only. The bar areas show the frame's background,
  not the status icons or navigation handle.
- The drawer's backdrop is plain grey. The real drawer window is
  translucent over whatever is behind it.
- The theme is `CompanionTheme`'s default, not the user's stored
  appearance.
- The narrow item 8 frame is 320 dp wide inside the full-width test window.
- The keyboard is never in a PNG. Whether it appears during "typing", and so
  whether the IME padding lifts the composer, depends on the device. The
  insets recorded per capture show it.

`capture-metadata.json` records, for each PNG:
- the pixel size, density, width in dp and font scale;
- the size and sha256 that the device wrote;
- the status-bar, navigation-bar and IME insets in pixels.

On the device, an inset of zero means that padding did not apply.

| Item | Files | Count |
|---|---|---|
| 1 | `01-{full,drawer}-{off,on}-{idle,typing}`, `01-drawer-on-after-host-switch`, `01-full-on-after-recreation` | 10 |
| 2 | `02-{full,drawer}-empty-chip-on`, `02-{full,drawer}-empty-chip-off-hint`, `02-full-unknown-availability-no-hint` | 5 |
| 3 | `03-{full,drawer}-passages-{collapsed,expanded}` | 4 |
| 4 | `04-{full,drawer}-source-detail-highlight` | 2 |
| 5 | `05-{full,drawer}-source-deleted`, `05-full-ordinary-no-passages` | 3 |
| 6 | `06-full-{no-match,not-indexed-retry,retrieval-incomplete-retry,passages-unusable}` | 4 |
| 7 | `07-{full,drawer}-large-font-passages-expanded` (font scale 2.0, default width) | 2 |
| 8 | `08-{full,drawer}-{off,on}-{idle,typing}-narrow` (frame 320 dp; actual width in the metadata) | 8 |
| 9 | `09-drawer-passage-open`, `09-drawer-chat-after-reentry-request` | 2 |

Total: 40.

"Typing" means a synthetic draft in a focused field, with the software
keyboard controller removed, as in `ComposerLayoutTest`. The composer
expands as it does while typing, but the PNG does not show the keyboard.
Item 6 is captured in the full app only; `PassagesUiTest` checks the same
four messages in both hosts.

### Lifecycle limits

The runner drives no real activity lifecycle for these items. All three
captures below are Compose-level stand-ins, not lifecycle evidence.

- **Item 1, after switching hosts.** The chip is turned on with a tap in the
  full-app layout, and the drawer layout then reads the same shared state in
  the same composition. Two separate activities are not shown.
- **Item 1, after recreation** (`01-full-on-after-recreation`). This is a
  re-render stand-in, not a restore.
  - `StateRestorationTester` recreates the composition, but the chip state
    lives in a test-held state outside the composition. The image would show
    "on" even with no saved-state handling at all.
  - It is not a process restart or a real activity reopen.
  - Persistence of the stored scope is covered elsewhere: by the host JDBC
    tests and by the S24 encrypted 7→8 case in the SQLCipher gate.
- **Item 9.** This shows the drawer with a passage open, then the
  new-conversation request that `MainActivity.onNewIntent` raises. Home,
  the mascot tap and the real `onNewIntent` delivery are not driven.
  Driving them would need the real secure activity, the secureQa app's stored
  conversation and the overlay, which this synthetic capture class does not
  touch.

### Host checks for this section

- The UI JVM suite passed 21 of 21.
- The shared JVM suite ran 289: 287 passed, and the 2 existing opt-in tests
  were skipped.
- Debug assembly passed. Debug lint reported 0 errors and 7 warnings.
- The `secureQa` app and androidTest APKs compiled. The new and changed
  instrumentation classes compiled only and did not execute.
- The node suites passed 81 of 81: 76 in `verify-chat-context`, 23 of them
  for `ui`, and 5 in `qa-supervision`. No synthetic process remained.
- QA round 1 fixes (S5R-F1 to F5) are in. Of the mutants that survived QA
  round 1, M4, M8, M9, both forms of M10, M12 and M15 now fail at least one
  node test each. M7 (the host-manifest hash check) still survives: the new
  device-record comparison (`screenshot-pull-mismatch`) catches the same
  changed or truncated file first, so `screenshot-manifest` is defence in
  depth with no dedicated node test (QA round 2, S5R-F6). The `/dev/null`
  stdin guard (M11) cannot be tested with the fake adb.
- The test and PNG counts are unchanged: 27 and 40.

None of the 27 `ui` tests and none of the 40 captures have run on a device.
Running them needs the user's go-ahead.

## 2026-11-20: S24 `ui` run failures (host diagnosis and fixes)

Run `benchmarks/results/passages-ui-20261120T090127Z-23894-8CuDBO/`:
25 of 27 passed, 36 of 40 PNGs pulled. Two failures. Host work only; nothing
below was run on a device.

### 1. `PassagesCaptureTest.item2EmptyLibraryChip` (line 143): test matcher

- **Symptom.** After the chip is turned off on an empty library,
  `onNodeWithText("Add a source to use this").assertIsDisplayed()` fails.
  The four later item-2 PNGs were not written (two `empty-chip-off-hint`,
  `02-drawer-empty-chip-on`, `02-full-unknown-availability-no-hint`).
- **Root cause: the test, not the UI.** By S5-F4 the disabled chip announces
  the hint in its state description ("Off. Add a source to use this"). So
  that it is not read twice, the visible hint `Text` has
  `clearAndSetSemantics {}` while the chip is disabled. The text is still
  drawn, but it has no text semantics. In compose-ui-test 1.12.1 a node that
  is not found counts as "not displayed", which gives exactly this message.
- **D5.4 is met.** The hint is drawn whenever `showEmptyHint` is true
  (`hasSources == false`), whether the chip is on or off.
  `02-full-empty-chip-on.png` shows it below the composer.
  In the same run, `PassagesUiTest.emptyLibraryDisablesTheChipAndKeepsItsSavedChoice`
  passed in both hosts. It asserts that the `sources-empty-hint` node is
  displayed in the disabled, chip-off state, and checks the chip's state
  description.
- **Fix (test only).** Line 143 now asserts the `sources-empty-hint` tag is
  displayed and that the chip's state description is
  "Off. Add a source to use this". This is the same check `PassagesUiTest`
  uses. The unknown-availability step is unchanged and still asserts no hint.
- **Host test.** The rule "the disabled chip carries the hint" is now one
  property, `SourcesChipState.hintAnnouncedByChip`
  (`showEmptyHint && !enabled`). The chip's state description and the
  hint's semantics clearing both use it, so behaviour is unchanged.
  `SourcesPresentationTest.disabledChipCarriesTheAddSourceHintOnlyForAKnownEmptyLibrary`
  pins the property:
  - empty library, chip off: the chip carries the hint;
  - empty library, chip on: the hint is its own text and is still shown;
  - unknown availability and has-sources: the chip does not carry it.

  Mutating the property to `!enabled` makes the test fail.

### 2. `ComposerLayoutTest.focusWithoutSoftwareKeyboardExpandsAndBackPreservesDraftInBothHosts` (line 71): not attributed to S5, pending the S24 rerun

- **Symptom.** After the field is focused and the composer is expanded,
  `sendKeyDownUpSync(KEYCODE_BACK)` is followed by a failed
  `composer-compact` `assertIsDisplayed`. The stack does not say which host
  iteration failed.
- **No layout or tag mismatch.**
  - `git diff 5818f15 HEAD` on `Composer.kt` leaves the expansion rule
    unchanged: `editing || imeVisible || attachments || multiline`.
  - The tag still follows that rule: `composer-expanded` or
    `composer-compact`.
  - The Back path is also unchanged: the pre-IME key handler on the field,
    `BackHandler(enabled = editing && …)`, and `endEditing()`.
  - S5 did restructure the composer, though. The root changed from
    `BoxWithConstraints` to `Column(BoxWithConstraints, hint)`. A focusable,
    toggleable chip now sits inside the input layout. The field is placed
    differently, with mic and Send on a second row while editing.
    `AssistantNavigation` gained a drawer `BackHandler`, which is disabled
    in this fixture because no span is open. None of these is known to
    block Back, but that is inference, not evidence: no S24 run of this
    test exists between `5818f15` and the failing run (the
    2026-11-20T083836Z run stopped on the Espresso `InputManager` error).
- **What the same S24 run proves.**
  - `PassagesCaptureTest` item 1 and item 8 ran 16 typing/idle cycles in
    both hosts, at default and 320 dp width. In each one, a focused field
    gave `composer-expanded`. Clearing focus then gave `composer-compact`
    and an unfocused field.
  - `imeBottomPx` was 0 in every capture, typing included, so no IME was
    involved.
  - So the S5 composer's expand and collapse work on the S24. The only step
    not exercised is the injected Back key reaching `endEditing()`.
- **What changed since the last pass.** This test last passed on the S24 on
  2026-10-01, four times (`docs/S24_ULTRA_QA_2026-10-01.md`). Since then:
  - the S24 OS changed: Espresso 3.5 failed there with
    `InputManager.getInstance` missing, which led to commit `52f3a10`;
  - `espresso-core` is now 3.7.0;
  - the test now runs as the 17th case of a 27-test instrumentation, not in
    a two-test run.

  Hypothesis, unverified: with `targetSdk = 36` the platform may hand the
  Back key to the window's top `OnBackInvokedCallback` early. The pre-IME
  stage, where the field's `onPreInterceptKeyBeforeSoftKeyboard` runs, is
  still view-tree dispatch, so this would not by itself explain a missed
  Back. The cause could not be pinned down without a device. These are
  candidates, not findings:
  - the key reached no focused test window;
  - another callback took it;
  - the activity's default Back ran;
  - it was delivered late.
- **Change (test only).**
  - The rule is now `createAndroidComposeRule<ComponentActivity>()`, the
    same activity and dispatcher that `createComposeRule()` launches, so the
    activity can be inspected.
  - Before sending Back, the test waits (up to 5 s) for the test window to
    have input focus. It then asserts that the composer has claimed Back
    while editing: `onBackPressedDispatcher.hasEnabledCallbacks()`. This
    check is new and stronger.
  - After Back (QA round 2, UIF-F3) the composer must reach
    `composer-compact` within a 1 s budget, counted from when the
    synchronous key injection returns. The original test asserted it at
    the next idle, so this is slightly looser than before: a collapse
    delayed by under 1 s would now pass. The elapsed time is in the
    failure message. The 5 s wait applies only to the window-focus
    precondition.
  - On failure the message reports: whether the composer is still
    expanded, whether the input is focused, whether the activity is
    finishing, window focus, and whether Back is still claimed.
  - Every original assertion is kept: expand on focus, compact and
    unfocused after Back, draft "Keep" displayed, and the sheet-Back
    sequence, in both hosts.
- **Host test.** Not feasible. `ui` commonTest has no Compose UI test
  harness, and Back key routing is platform behaviour. This needs the S24
  rerun.

### QA round 2 (`build/qa-s24-ui-fixes-2026-11-20/review.txt`)

- **UIF-F1.** In the disabled, known-empty step, `PassagesCaptureTest` and
  `PassagesUiTest` now also assert
  `onNodeWithText("Add a source to use this", useUnmergedTree = true).assertDoesNotExist()`.
  QA's mutant M3 (the hint's semantics never cleared) would then fail on the
  device, as a double TalkBack read would. No host check: `ui` commonTest
  has no Compose UI test harness, so the `Composer` wiring cannot be
  exercised there. `SourcesPresentationTest` still pins only the property.
- **UIF-F3** and **UIF-F4.** See failure 2 above: the 1 s collapse budget,
  the "not attributed to S5" wording, the S5 structural changes, and the
  routing explanation marked as a hypothesis.
- UIF-F2 (pixel evidence for the chip-off hint) closes with the rerun's
  `02-*-empty-chip-off-hint` PNGs. UIF-F5 needs no action.

### Host checks for this section

- `./gradlew --offline :ui:jvmTest --rerun :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug`:
  passed. UI JVM 22 of 22. Shared JVM 289: 287 passed, 2 opt-in skipped.
  Lint 0 errors, 7 warnings.
- `./gradlew --offline --rerun-tasks -PtestBuildType=secureQa :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest`:
  passed. The changed instrumentation classes compiled only; they did not
  run.
- `./gradlew --offline :androidApp:testDebugUnitTest --rerun`: passed, 32 of
  32.
- Expected `ui` counts are unchanged: 27 tests and 40 PNGs. No test or
  capture was added or removed. The four missing item-2 PNGs come back once
  item 2 gets past line 143.
- Not run: any device or emulator step. Whether failure 2 is fixed is
  unverified until the S24 `ui` rerun, which needs the user's go-ahead.

## 2026-11-20: S5-F3 user visual decision — refine the composer

After reviewing the explanation and retained S24 captures, the user selected
**Refine it** rather than accepting the current adaptive layout unchanged.

Approved direction:

- Retain the compact idle bar where the controls and input fit comfortably.
- Give the message field a dedicated full-width row while typing/expanded.
  Move controls, including **My sources**, into a separate controls row.
- At narrow widths and large font sizes, prioritize readable message input;
  avoid squeezing it beside the source chip and awkward word wrapping.
- Use the same behavior in the drawer and full app. Preserve the sticky source
  choice, draft and attachment continuity, and accessible controls.

This settles the S5-F3 layout choice; implementation and verification of the
refinement remain pending. Keyboard/inset correctness remains a QA item, and
this decision does not establish that the retained fixture captures prove it.
Only documentation was updated to record this decision; no app code changed
and no tests, device runs or commits were performed.

## 2026-11-20: S24 `ui` rerun after the UI test fixes (planner)

Run on the S24 (`R5CX22V8D6B`, `secureQa`, staged APK copies only, nothing
uninstalled), at HEAD `52f3a10` plus the uncommitted, QA-passed test fixes
(tracked-diff sha256 `a33fb0cc2e25895a0ba3cb15b59d980c3e3bfba18fd7c12e6e2aa9646c89c729`).

| Run directory (`benchmarks/results/`) | Source | Result | PNGs |
| --- | --- | --- | --- |
| `passages-ui-20261120T090127Z-23894-8CuDBO` | `2bee021`, diff `73e496dd…` | Failed, 2 of 27 (item-2 matcher; Composer Back) | 36 |
| `passages-ui-20261120T093917Z-27642-aQ3IQk` | `52f3a10`, diff `a33fb0cc…` | Failed, 7 of 27: "No compose hierarchies found" in the first 7 cases | 35 |
| `passages-ui-20261120T094151Z-27972-TFUKrG` | `52f3a10`, diff `a33fb0cc…` | **Passed, `OK (27 tests)`**, exit 0, 900 s deadline | 40 of 40 |

- An independent QA check of the passing run confirmed 27 distinct passing
  tests with no skipped or failed status, 40 of 40 PNGs matching the recorded
  hashes, sizes and inventory, and visual inspection of all 40. The item-2
  empty-library hint is visible in both hosts. **UIF-F2 closes**; S5-F6 stays
  resolved.
- **Repeatability is not shown.** The `093917Z` attempt used identical source
  and APK hashes and failed 7 of 27 because no Compose root was available. The
  planner attributes this to the phone screen being off or locked, but that
  cause is a hypothesis, not a verified one. Both failed run directories are
  kept as evidence beside the pass.
- **S5R-F7 stays open (non-blocking):** the metadata for ordinary captures
  records font scale `1.0` as a supplied value rather than measuring it. The
  explicit `2.0` large-font captures (item 7) are not affected.
- The S5-F3 composer refinement (decision above) is not implemented. These
  screenshots show the layout as it was before that refinement.

## 2026-11-23: S5-F3 composer refinement, host implementation (dev)

Implements the 2026-11-20 user decision on the host only, at HEAD `6c1226a` plus an
uncommitted diff. No device or emulator was run, so nothing below is runtime-verified,
and the S24 captures listed in the sections above still show the old layout.

**Layout (`Composer.kt`, `ComposerInputLayout`), the same composable in both hosts:**

- **One row** (`+`, **My sources**, field, mic, Send) is kept only when the composer is
  not expanded and the field left between the chip and mic is at least
  `max(72 dp, the field's one-line content width)`. The content width is the field's
  measured unwrapped width: the placeholder, or an idle draft, plus its padding. The old
  rule used the 72 dp floor alone, which allowed the S24 chip-on idle bar to wrap
  "Ask anything..." onto two lines (`01-full-on-idle.png` in the 2026-11-20 run).
- **Stacked** (expanded, or a one-row bar that does not fit): attachment strip, then
  the field on its own row across the full inner width, then a controls row with `+`
  and **My sources** at the start and mic and Send at the end. The empty-library hint
  stays below the composer surface. The chip is measured against the controls row only
  (inner width minus three 48 dp buttons) and its label is a single line that
  ellipsizes when that row is too narrow; its accessible name, state and 48 dp target are
  unchanged.
- The same six children are placed in both arrangements, so the text field is moved,
  not recreated. The field now fills its slot, so the full-width row is its touch
  target. Placement goes through `placeRelative`, so RTL mirrors it.

**Estimated widths, not measured on a device.** Chip and placeholder widths come
from pixel bounds in the 2026-11-20 S24 PNGs (density 2.625): chip about 96 dp off and
116 dp on, and the placeholder about 113 dp including padding. The 2.0 values are those
labels doubled. The inner width is the screen width minus 40 dp. Field width means
what one row would leave.

| Screen | Scale | Chip off: field left → result | Chip on: field left → result |
| --- | --- | --- | --- |
| 320 dp | 1.0 | 40 dp → stacked | 20 dp → stacked |
| 360 dp | 1.0 | 80 dp → stacked (placeholder would wrap) | 60 dp → stacked |
| S24, 411 dp | 1.0 | 131 dp → one row | 111 dp → stacked (placeholder would wrap) |
| 320, 360 or 411 dp | 2.0 | negative, or below 72 dp → stacked | negative → stacked |

**Carry-overs:**

- **UIF-F9.** The chip applied `Modifier.toggleable` in the modifier passed to a plain
  `Surface`, which places that modifier before its own background and shape clip. The
  toggle's ripple and state layer were therefore drawn over the chip's square layout
  bounds. The click that disables the chip on an empty library left such a layer in the
  capture. The chip now uses Material 3's toggleable `Surface(checked, onCheckedChange,
  enabled)` overload, which applies the toggle after the clip, and keeps `Role.Switch`
  through semantics. `PassagesCaptureTest` checks that the toggle comes after the clip in
  the chip's modifier chain. Pixels are not asserted.
- **UIF-F6.** The chip-on empty-library step asserts that the hint text is displayed.
- **UIF-F7.** A successful Back collapse must also land within the 1 s budget. It uses
  the same diagnostic message as the failure path.
- **S5R-F7.** Capture metadata records the font scale read from `LocalDensity` inside the
  capture frame at its last composition. Item 7 also asserts that the measured value
  equals the requested 2.0.

**Expected S24 `ui` run:** unchanged, 27 tests and 40 PNGs, so the script is untouched.
Captures expected to look different are listed in the dev handback. They are every
typing capture, every chip-on capture at S24 width, every narrow capture, both
large-font captures, and the chip-off hint captures, where the square should be gone.

## 2026-11-23: S24 `ui` run of the round-1 refinement, and round 2 (icon-only idle chip)

**S24 run** `passages-ui-20261123T030928Z-5197-SWxa3f` (planner). Source was HEAD `6c1226a`
plus the round-1 diff `ff0b9a88…`. It **failed 1 of 27 tests** and produced all 40 PNGs.

- The failure was `ComposerLayoutTest.compactMicIsInsideAndBesideSendForEmptyAndShortDrafts`
  at `assertOneRow`. At 411 dp and a forced font scale of 1.0, the chip-off idle bar stacked
  into two rows.
- The phone's system font scale is **0.9**. The measured capture metadata (S5R-F7) records
  0.9 for 38 captures and 2.0 for the two large-font captures. The round-1 width estimates
  in the section above were taken from 0.9-scale pixels, so they are too small for 1.0.
  At 1.0, the labelled chip-off bar leaves the field roughly 10 dp less than its one-line
  content needs.
- In the captures, the typing layouts look as intended in both hosts and at narrow
  width. UIF-F9 is visually confirmed: the `02-*-empty-chip-off-hint` captures show no
  square and no rounded tint behind the disabled chip.

**User decision (2026-11-23).** While the composer is not expanded and **My sources** is
off, enabled or disabled, the chip shows only its icon. The following stay the same: the
accessible name and state description, `Role.Switch`, the hint rule, a target of at least
48 dp, and the rounded surface. The label returns when the chip is on or the composer is
expanded. The round-1 one-row rule is unchanged but now sees the icon-only chip's width.
A chip-on idle bar may still stack.

**Round-2 changes (dev, host only, not run on a device):**

- `sourcesChipShowsLabel(expanded, checked) = expanded || checked`. Only the chip's own
  content changes, so the text field is not recreated. The chip has a 48 dp minimum width
  and centres its content, so the icon-only chip is a 48 × 48 rounded surface.
- **Corrected estimate.** At 411 dp the icon-only chip leaves 179 dp for the field at any
  font scale. The one-line content needs about 131–141 dp at 1.0 and about 128 dp at 0.9.
  The estimated margin is therefore about 38–48 dp at 1.0 and about 50 dp at 0.9.
- **Tests.**
  - The compact test still pins one row at 411 dp and 1.0. It now also checks the
    disabled chip on a known-empty library, and checks icon-only versus labelled by chip
    width.
  - The focus test checks the label while expanded and the icon after Back.
  - S5C-F2: after Back the field keeps "Keep", and its selection is collapsed to its end
    (3) by the framework's deselect on blur.
  - S5C-F1: the UIF-F9 guard requires the graphics layer directly after the Surface's
    shaped background, before the toggle.
- **Counts.** Unchanged: 27 tests and 40 PNGs.

## 2026-11-23: S24 `ui` run of round 2, and the round-3 test correction

**S24 run** `passages-ui-20261123T032810Z-8699-kNzQ2O` (planner). It ran HEAD `6c1226a`
plus the round-2 diff `2305f07a…`. It **failed 1 of 27 tests** and produced all 40 PNGs.

- The failure was `ComposerLayoutTest.narrowRtlLargeTextSheetWrapsAndBackRetainsDraft`, in
  `assertFieldRowThenControls` on the idle composer: `full 320dp 2x RTL idle: field width
  expected:<734.0> but was:<230.0>`.
- Everything else passed. That includes the one-row guard at 411 dp and font scale 1.0.
  The icon-only chip looks right in `02-*-empty-chip-off-hint` and `08-*-off-idle-narrow`.

**Planner ruling.** The product behaved correctly, and the test expectation was stale.

- At 320 dp, with the 48 dp icon-only chip, the idle bar leaves about 88 dp for the field.
- At 2.0, the short draft "Keep" fits in that space, so the R1 rule keeps the idle bar on
  one row, as the phone showed.
- The test's comment, "320 dp at 2x cannot fit the one-row bar", was only true while the
  chip carried its label.

**Round-3 changes (tests only; `Composer.kt` unchanged; host only):**

- The narrow RTL test at 320 dp and 2.0 now covers both idle outcomes in both hosts:
  - With an empty field, the placeholder (about 250 dp at 2.0) does not fit, so the idle
    bar must stack.
  - With the short draft "Hi" (about 52 dp at 2.0), the idle bar must stay on one row.
    `assertOneRow` now checks all five controls, and their order follows the layout
    direction.
  - The editing check (stacked) and the Back/draft check are unchanged. They use "Hi"
    because "Keep" fits 88 dp with only a couple of dp to spare.
- S5C-F3: the 411 dp one-row guard is now skipped by assumption on screens narrower than
  411 dp. The S24 (411.4 dp) still runs it.
- The other narrow-width device checks were reviewed. They involve the labelled chip-on
  case or only test tags, so they were not stale.
- Counts are unchanged: 27 tests and 40 PNGs.

## 2026-11-23: S24 `ui` run of round 3 (planner)

- Run `benchmarks/results/passages-ui-20261123T033948Z-10269-iUpNp2`:
  - S24 `R5CX22V8D6B`, `secureQa`, staged APK copies only, nothing uninstalled.
  - Built from HEAD `6c1226a` plus the round-3 tree that QA reviewed (tracked-diff sha256
    `58d379c95dd59a659382c397494adc677902f669b81b7b8ede11176d2fdc1885`).
  - **Passed: `OK (27 tests)`.** No failed or skipped status codes, and 40 of 40 PNGs.
- Independent QA passed all three rounds on the host:
  `build/qa-s5-composer-refine-2026-11-23-r1`, `-r2` and `-r3`.
  S5C-F1 to S5C-F3 are resolved.
- What the planner saw in the captures:
  - The chip-off idle bar is one row with the icon-only chip.
  - Chat captures with the chip off keep "Ask anything..." on one line.
  - The large-font capture (item 7) is stacked: the field row comes first, then `+`, the labelled chip, mic and send.
  - Item 2 still shows no square behind the disabled chip.
- Limits:
  - This is one passing run; repeatability is not shown.
  - The phone's system font scale is 0.9. The default scale (1.0) is covered only by the forced-density device tests, not by the captures.
  - Composer behaviour on narrower phones is covered only by those forced-width tests.
