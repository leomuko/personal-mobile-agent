# S5 composer refinement dev handoff (S5-F3), with small S5 carry-overs

2026-11-23. From the planner to the dev agent. This handoff is self-contained.

Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. Background:
- [S5_DEV_HANDOFF.md](S5_DEV_HANDOFF.md), including its rulings;
- [S5_DEVICE_RUNNER_DEV_HANDOFF.md](S5_DEVICE_RUNNER_DEV_HANDOFF.md);
- [../../../docs/P2_PASSAGES_UI_RESULTS.md](../../../docs/P2_PASSAGES_UI_RESULTS.md),
  especially "2026-11-20: S5-F3 user visual decision" and the S24 rerun
  section that follows it.

**Authorization:** the user asked for this dev round on 2026-11-23. Host work
only. An S24 `ui` run is a separate step: the planner asks the user for a
go-ahead after QA.

## Where things stand

- `dev` is at HEAD `6c1226a`, and the working tree is clean.
- S5 is committed. The S24 `ui` run passed with 27 of 27 tests and 40 PNGs, in
  `benchmarks/results/passages-ui-20261120T094151Z-27972-TFUKrG/`. Those
  screenshots show the layout as it was before this refinement.
- Today's layout is in
  `ui/src/commonMain/kotlin/dev/edgecompanion/ui/conversation/components/Composer.kt`,
  in `ComposerInputLayout`:
  - Compact: one row of `+`, chip, field, mic and send.
  - Expanded, or when the compact field would be narrower than 72 dp: row 1 is
    `+`, chip and field; row 2 is mic and send.
  - The result is that while typing, the field is squeezed beside the chip. At
    about 320 dp it gets about half the width and wraps awkwardly. See
    `01-full-on-typing.png` and `08-drawer-on-typing-narrow.png` in that run.

## Part A: S5-F3 composer refinement (user decision, 2026-11-20)

These are the user's words, settled:

> Keep the compact idle bar where it fits. While typing/expanded, give the
> message field a dedicated full-width row and place controls, including
> **My sources**, in a separate row. Adapt narrow and large-font layouts to
> prioritize readable input rather than squeezing the field beside the chip.
> Apply consistently in the drawer and full app, preserving sticky scope,
> draft/attachment and accessibility behavior.

Required behaviour:

1. **Compact (idle, not editing, no attachments, single line)**
   - Keep the one-row bar when the field keeps a usable width.
   - The planner's floor for "usable" is the existing 72 dp. If you propose a
     different floor, state it and why, with the widths at 320 dp, 360 dp and
     the S24's width, at font scale 1.0 and 2.0.
2. **Expanded (editing, IME visible, attachments, or multiline)**, and
   **compact that does not fit**:
   - The attachment strip comes first, when present.
   - Then the **message field on its own full-width row**, using the Surface's
     inner width.
   - Then **a controls row**: `+` and **My sources** at the start; mic and send
     at the end.
   - The empty-library hint stays below the composer surface, as today.
3. **Narrow and large font:** the field row always gets the full width. The
   chip must never squeeze the field. If the controls row cannot fit the chip
   label at full length, the chip may shrink or ellipsize its label. It must
   keep its 48 dp touch target and its accessible name and state.
4. **Both hosts identical:** the drawer and the full app use the same
   composable and rules.
5. **Preserve:**
   - **One stable text field.** Keep the existing approach of moving one field
     instead of recreating it. See the comment on `ComposerInputLayout`.
     Focus, selection, IME and the draft must survive the switch between
     compact and expanded in both directions. Do not swap in a different
     composable tree for each mode.
   - Sticky scope, draft and attachment continuity. Back while editing
     collapses and keeps the draft (`ComposerLayoutTest`). Recording mode is
     unchanged.
   - RTL mirroring through `placeRelative`.
   - All accessibility semantics from S5 and the UI-fix round: the chip's name
     and state description, and the hint semantics rule
     (`hintAnnouncedByChip`).
   - Strings stay the same. **No new strings**, unless a new label is truly
     needed. If it is, stop and ask.
6. **Visuals:** follow `planning/design/approved/` (02 conversation and 05
   inline recording) for corner radius, surface colours and spacing. Do not
   restyle anything beyond the layout change and Part B.

## Part B: small carry-overs (P3, in scope)

- **UIF-F9 (new, cosmetic).** In `02-*-empty-chip-off-hint.png` from the
  passing run, the disabled chip shows a lighter **square-cornered rectangle**
  behind its rounded shape. The enabled chip in other captures does not.
  - Find the cause. Likely candidates are an unclipped `toggleable`
    indication, `heightIn` padding outside the `Surface` shape, or a
    disabled-state overlay.
  - Fix it so the disabled chip shows only its rounded shape.
  - Add a cheap guard if one is practical, such as a modifier order the test
    can see, or a capture node check. Pixel assertions are not required.
- **UIF-F6 (optional, recommended).** In `PassagesCaptureTest`, at the
  chip-on known-empty step, add
  `onNodeWithText("Add a source to use this").assertIsDisplayed()`. It is the
  positive counterpart of UIF-F1.
- **UIF-F7 (optional).** In `ComposerLayoutTest.pressBackWhileEditingAndAwaitCompact`,
  assert `elapsed <= BACK_COLLAPSE_BUDGET_MS` on success, with the existing
  diagnostic message.
- **S5R-F7.** `PassagesCaptureTest.capture(...)` writes the `fontScale`
  argument (default `1f`) into the capture metadata. Record the **measured**
  value from the rendered content's `LocalDensity.current.fontScale`, or from
  an equivalent real source, instead. The explicit `2.0` captures must still
  record 2.0, and must record it because it was measured.
- **UIF-F8 is not in scope.** It asks for a docs heading change in an earlier
  section, and that note is append-only.

## Tests

- Update `ComposerLayoutTest` for the new geometry:
  - In expanded mode, the field is full width. Its bounds span the composer's
    inner width within a small tolerance.
  - The controls row sits below the field.
  - `+` and the chip are at the start; mic and send are at the end.
  - Cover both hosts, 320 dp at font scale 2.0, and RTL. The existing
    `narrowRtlLargeTextSheetWrapsAndBackRetainsDraft` is the natural place for
    the RTL case.
  - In compact mode at a normal width, the one-row bar is kept, and the mic
    sits beside send.
  - Focus, selection and draft are kept across compact → expanded → compact
    (Back).
- A test must fail against today's `ComposerInputLayout`. Name it in the
  handback.
- Do not change the expected S24 counts unless you must. If a capture is
  added, renamed or removed, update the `ui` mode's expected test and PNG
  counts in `scripts/verify-chat-context.sh`, and its node tests, and state
  the new numbers.
- No pixel assertions.

## Allowed paths

- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/conversation/components/Composer.kt`
- `ui/src/commonTest/kotlin/dev/edgecompanion/ui/conversation/**`: new or
  changed host tests only
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/ComposerLayoutTest.kt`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/PassagesCaptureTest.kt`
- `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/PassagesUiTest.kt`
  and `CaptureFlowTest.kt`: only if the new geometry breaks them. Justify each
  change.
- `scripts/verify-chat-context.sh` and
  `scripts/tests/verify-chat-context.test.mjs`: only if the counts change
- `docs/P2_PASSAGES_UI_RESULTS.md`: append only, as a new dated section at the
  end

## Out of scope

- Any behaviour change to the chip rule, scope, retrieval or no-answer logic.
- New dependencies, string changes and other screens.
- Deferred issues (`planning/DEFERRED_ISSUES.md`).
- Device or emulator runs.
- Commits, staging, stashing and branch changes.

## Checks to run

Run one Gradle invocation at a time:

```sh
./gradlew --offline :ui:jvmTest --rerun :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest --rerun
./gradlew --offline --rerun-tasks -PtestBuildType=secureQa :androidApp:assembleSecureQa :androidApp:assembleSecureQaAndroidTest
```

If the script changed, also run:

```sh
node --test scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs
/bin/bash -n scripts/verify-chat-context.sh
```

Never invoke real adb.

## Report back

Use the dev-brief handback format, plus:
- changed files with sha256 values, and the `git diff HEAD | shasum -a 256`
  digest;
- for each layout rule, the test that pins it, and the test that fails
  against the old layout;
- the UIF-F9 cause and fix;
- how S5R-F7 now measures the font scale;
- the expected S24 `ui` test and PNG counts, and the exact command;
- which existing captures will now look different, so the user knows what to
  compare.
