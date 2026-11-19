# S5 dev handoff: "Passages consulted" UI and the sources chip

2026-11-19. From the planner to the dev agent. This handoff is self-contained.
Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. Background:
- the slice plan, S5: [../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md);
- the decisions: [../../P2_S5_S6_DECISIONS_DRAFT_V1.md](../../P2_S5_S6_DECISIONS_DRAFT_V1.md), Part 1;
- the S4 handoff, for the data it produced: [S4_DEV_HANDOFF.md](S4_DEV_HANDOFF.md).

**Authorization:** host work only. No device or emulator runs. The S24
screenshots come later, with the user's go-ahead.

**Start condition: met on 2026-11-19.**
- The S4 clean-up is committed (`319e084..ea179bb`, planning `8883c9c`).
- The user approved D5.1–D5.7 as recommended, with a **sticky** chip.
- The user chose S24 screenshots as S5 evidence. Each run needs their go-ahead.

## Part 0: carried-over S4 issue to fix first (S4-F7, P3)

`GroundedPrompt` excludes passages that contain the chat control sequences or
lines that look like the `@@@` delimiter. That check has two problems:
- **Bypass:** lookalikes that use Unicode spaces get through. Examples are
  NBSP, U+2000–U+200A, U+3000, and tab between marker tokens. The real marker
  itself cannot be forged.
- **Over-exclusion:** passages are dropped that merely contain a code-diff hunk
  line (`@@ -1,3 +1,4 @@`), an `@@handle`, or an all-caps "PASSAGE 1" heading.

Required behaviour:
- Normalize Unicode whitespace before matching delimiter lookalikes.
- Match only lines that could actually be read as the block's structural
  lines: `@@@ BEGIN PASSAGES`, `@@@ END PASSAGES`, and the per-passage label
  format.
- Do not exclude passages that merely contain `@@` text or diff hunks. Any `@@@` still excludes the passage, as it does today.
- Keep `grounded-prompt-v1` byte-identical for passages that were already
  accepted. If the label or delimiter format must change, bump the version
  and say why.

Tests:
- each Unicode-space lookalike is excluded;
- diff hunks, `@@handle` and similar text are included;
- the existing packing tests are unchanged.

S4-F3, retry after a temporary no-answer, is closed by D5.5 below.

## Where things stand

- S4 stores, per user turn, the `RequestScope` (`ORDINARY` or `SOURCES`).
- Per assistant attempt, S4 stores the grounding status and the consulted
  passage references: source, revision and passage IDs, UTF-16 start and end,
  and rank. These are references only, not passage text.
- A `SOURCES` request with no usable passage records a typed `SourcesNoAnswer`
  result and loads no model. The possible values are `NO_MATCH`,
  `NOT_INDEXED`, `RETRIEVAL_INCOMPLETE` and `PASSAGES_UNUSABLE`.
- Scope is exposed through the session/assistant API, and it defaults to
  `ORDINARY`. There is no UI for it yet.
- The conversation UI is in `ui/src/commonMain/kotlin/dev/edgecompanion/ui/conversation/`:
  - `Composer.kt`
  - `ConversationList.kt`
  - `ConversationScreen.kt`
  - `viewmodel/ConversationViewModel.kt`
- Sources UI is in `ui/.../sources/`. Strings are in
  `ui/src/commonMain/composeResources/values/strings.xml`.
- The drawer and the full app share one conversation and its draft.
- Approved visual reference:
  `planning/design/approved/02-conversation-memory.png`. It shows source chips
  under an answer, and a composer with `+`, a text field and a mic.

## What to build (D5 approved as recommended, 2026-11-19)

1. **Sources chip (D5.1–D5.4, D5.7)**
   - A toggle chip in the composer row, between `+` and the text field, with a
     sources icon.
   - Label: "My sources". It shows a check mark when on.
   - Accessibility: the role is a toggle, and the name is "Answer from my
     sources only", with on/off state.
   - **Sticky:** the chip stays on until the user turns it off. Its state is
     saved with the draft (schema or draft-store change as needed), so the
     drawer, the full app and a restart all show the same state.
   - Sending while the chip is on submits `RequestScope.SOURCES`.
   - **Empty library:** the chip is disabled, with the hint "Add a source to
     use this".
   - A sent `SOURCES` user message shows a small "From my sources" label.
2. **"Passages consulted" row** under an assistant answer that has consulted
   references.
   - The row is compact and expandable. Each item shows the source title
     resolved **live** from the references, plus a short span preview.
   - Tapping an item opens that source in the Sources screen at the span,
     highlighted. A live read is fine; the conversation stores no copies.
   - A deleted source or revision shows "Source deleted" and is not tappable.
   - An answer with no references shows no row.
   - The wording is "Passages consulted". Never use "verified", "supported by"
     or "cited".
3. **No-answer rendering (D5.6)**
   - Show one assistant line for each `SourcesNoAnswer` reason, taken from
     resource strings. There is no passage row.
4. **Retry (D5.5, closes S4-F3)**
   - Offer Retry for `NOT_INDEXED` and `RETRIEVAL_INCOMPLETE` only.
   - Retry keeps the stored scope and creates a new attempt, as S4 retries do.
5. **Both hosts:** the drawer and the full app behave identically.

## Design constraints

- Follow the existing Compose patterns, view-model state and design system.
  Add no new navigation framework.
- Read the skills `kotlin-ui-compose-multiplatform`,
  `kotlin-ui-adaptive-resources`, `kotlin-project-state-management` and
  `kotlin-testing-kmp`. If the span opens through navigation, also read
  `kotlin-navigation-compose-multiplatform`.
- If draft scope needs a schema change, use the next conversation migration
  with its snapshot, following the S4 pattern so that
  `verifySqlDelightMigration` passes. Existing drafts default to off.
- Put all text in resource strings. Use no hard-coded English.
- Do not log any query, passage or source text.
- Keep the QA-only paths unchanged (D4).

## Allowed paths

- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/conversation/`
- `ui/src/commonMain/kotlin/dev/edgecompanion/ui/sources/` (open-at-span only)
- `ui/src/commonMain/composeResources/values/strings.xml`
- `ui/src/commonTest/`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/` (draft
  scope, retry rule, and the read model for consulted references)
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/sources/`, for a
  read-only lookup that resolves a reference to a title or span. Stop and
  report if more than that is needed.
- `Conversation.sq`, the next migration and its snapshot
- Tests under `shared/src/commonTest/` and `shared/src/jvmTest/` for the
  conversation area
- The `androidApp` drawer and full-app host wiring, only if a host does not
  already receive the shared UI. Say which files.
- A new note, `docs/P2_PASSAGES_UI_RESULTS.md`

### Planner rulings, round 1 (2026-11-19)

The dev's first handback stopped at the allowed-path boundary. These rulings
widen the paths as follows.

- **R1, host wiring allowed.** These files may change, for callback and
  navigation wiring only:
  - `ui/src/commonMain/kotlin/dev/edgecompanion/ui/app/CompanionScreen.kt`
  - `ui/src/commonMain/kotlin/dev/edgecompanion/ui/navigation/AssistantNavigation.kt`
  - `androidApp/src/main/kotlin/dev/edgecompanion/app/MainActivity.kt`

  The wiring covers the chip state and toggle, the empty-library signal, and
  opening a source at a span from either host. These files get no other
  behaviour changes.
- **R2, no new dependency and no `ui/build.gradle.kts` change.**
  - Compose coverage uses the existing Android Compose test setup
    (`libs.compose.test`, already in `androidApp`'s `androidTestImplementation`).
    Add new S5 test classes under
    `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/`, alongside
    `ComposerLayoutTest` and `HostRegressionTest`.
  - Compile them on the host with `:androidApp:assembleDebugAndroidTest`.
    Running them is a device step: it happens on the S24 together with the
    screenshots, after the user's go-ahead.
  - Put as much logic as possible where the host can run it:
    - chip state, the reason-to-text and Retry mapping, and the consulted-row
      presentation in `ui/src/commonTest` (view model and presentation
      classes);
    - persistence and lookup in the shared JVM tests.
  - In the handback, report compiled-only and executed tests separately.
- **R3, no script change in this slice.** List the S5 instrumentation classes
  and the expected test count. The planner arranges the S24 runner at the
  device stage.
- **R4, migration-only test updates allowed (2026-11-19).** Migration 7 raises
  the schema from 7 to 8. These files may change:
  - `shared/src/jvmTest/kotlin/dev/edgecompanion/core/sources/data/PassageBackfillTest.kt`
  - `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/PassageSqlCipherTest.kt`
  - `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/GroundingSqlCipherTest.kt`
  - `androidApp/src/androidTest/kotlin/dev/edgecompanion/app/SourcesLibraryTest.kt`

  The changes are limited to:
  - the expected schema version constants;
  - fixture steps that rebuild older schemas, so that they account for
    migration 7.

  Do not remove, loosen or skip any assertion. Do not touch the copies under
  `benchmarks/results/`.

  You may add **one** 7→8 case on an encrypted database to
  `GroundingSqlCipherTest`. If you do, say so and give the new expected
  count. The `sqlcipher` gate currently requires `OK (8 tests)`; the planner
  updates the gate at the device stage, and this slice makes no script change.

  List every change in these files in the handback. They are
  compile-checked on the host only. An S24 `sqlcipher` rerun after
  migration 7 is required before S5 is accepted, with the user's go-ahead.
### Planner rulings, QA round 1 (2026-11-19, `build/qa-s5-2026-11-19-r1/`, FAIL)

- **S5-F1, P2, fix.** A checked chip must always be able to turn off:
  `enabled = ready && (hasSources == true || checked)`. Turning it on still
  requires at least one source.

  D5.4's "no silent ordinary send" still holds. A send while the chip is
  checked stays `SOURCES`; only an explicit tap turns it off.

  Change these two tests to match the ruling, and say so in the handback:
  - `emptyLibraryDisablesTheChipAndKeepsItsSavedChoice`
  - `removingLastSourceDoesNotDowngradeStickyScopedSendToOrdinary`

  Add these tests:
  - a checked chip on an empty library can be turned off;
  - an unchecked chip on an empty library cannot be turned on;
  - `hasSources == null` behaves as an empty library.
- **S5-F2, P2, fix.** Add tests that go through the real
  `ConversationViewModel` and session, not fake lambdas. They must show:
  - a send with the chip on stores `SOURCES`;
  - toggling the chip persists the scope.

  These tests must fail if `submit()` ignores the scope (mutant M14) or if
  `setSourcesOnly` always stores `ORDINARY` (mutant M15). Also add a host test
  for the "From my sources" label mapping (mutant M17).
- **S5-F3, P3, document only. The user decides with the S24 screenshots.**
  - Make no layout change in this round.
  - Describe the composer precisely in `docs/P2_PASSAGES_UI_RESULTS.md`:
    - what sits in each row while idle and while typing;
    - the width at which it wraps;
    - that it differs from the single-row composer in approved image 02.
  - Add a screenshot item for the S24's default width and for a narrow width.
- **S5-F4, P3, fix.** This ruling amends D5.2: the accessible name must start
  with the visible label. Use the resource string "My sources, answer from my
  sources only". Also:
  - attach the "Add a source to use this" hint to the disabled chip's
    semantics;
  - give the "Passages consulted" expander an expanded or collapsed state.
- **S5-F5, P3, fix.** Clear the drawer's open passage when the activity
  receives a new intent, matching the full app. Add a test if the host makes
  it feasible; otherwise add an S24 screenshot item for re-entering the drawer.
- **N1, script gate, now allowed (supersedes R3 for this item only).** Update
  `scripts/verify-chat-context.sh` sqlcipher mode, and its test in
  `scripts/tests/verify-chat-context.test.mjs`:
  - require `OK (9 tests)`;
  - fix the comment to say `PassageSqlCipherTest` 3 + `GroundingSqlCipherTest` 6;
  - keep the `STATUS_CODE -1..-4` failure pattern.

  Run the node tests, and confirm afterwards that no `SYNTHETIC` process
  remains.
- **M7 (redundant revision check):** no action.
- **S4-F7 (Part 0) is accepted for QA as handed back.** That includes keeping
  bare `BEGIN PASSAGES` and `END PASSAGES` lines as excluded lookalikes.

## Out of scope

- Semantic ranking.
- Model-written citations.
- S6 quality work.
- Changing the default context profile.
- Memory and PDF.
- S4-F7 beyond Part 0.
- Device and emulator runs.
- Anything in `DEFERRED_ISSUES.md`.

## Done when

- Compose tests cover:
  - the chip: on/off, sticky across hosts and recreation, disabled on an empty
    library, and its accessibility semantics;
  - the consulted row in present, absent and deleted-source states;
  - the four no-answer reasons;
  - which reasons show Retry;
  - large font scale;
  - identical behaviour in the drawer and full-app hosts.
- Shared and JVM tests cover:
  - draft scope persisting and restoring;
  - a send with the chip on storing `SOURCES`;
  - Retry keeping the scope, and following the reason rule;
  - references resolving live, including a deleted source;
  - the migration, if added.
- All existing tests pass unmodified, or each change is justified as an
  assertion of replaced behaviour.
- The note lists the screens to capture on the S24, which is the next step and
  needs the user's go-ahead.

## Checks to run

```sh
./gradlew --offline :shared:verifySqlDelightMigration :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest --rerun
./gradlew --offline :ui:allTests --rerun
./gradlew --offline :androidApp:assembleDebugAndroidTest   # compile the S5 Compose tests (R2)
```

Run one Gradle invocation at a time.

## Report back

Use the dev-brief handback format, plus:
- the screens and states built;
- any schema change;
- how the reference lookup resolves live;
- the accessibility semantics;
- the S24 screenshot list;
- anything that departs from D5 or from the approved visuals.
