# S5 dev handoff: "Passages consulted" UI and the sources chip

2026-11-19. From the planner to the dev agent. This handoff is self-contained.
Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. Background:
- the slice plan, S5: [../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md);
- the decisions: [../../P2_S5_S6_DECISIONS_DRAFT_V1.md](../../P2_S5_S6_DECISIONS_DRAFT_V1.md), Part 1;
- the S4 handoff, for the data it produced: [S4_DEV_HANDOFF.md](S4_DEV_HANDOFF.md).

**Authorization:** host work only. No device or emulator runs. The S24
screenshots come later, with the user's go-ahead.

**Start condition:**
- The S4 clean-up must have been accepted and committed by the user.
- The user must have approved D5.1–D5.7, or changed them. If the planner's
  message does not confirm both, stop and ask the planner. Do not build from
  the draft.
- If the user changed any D5 item, the planner's message gives the change, and
  that change wins over this file.

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

## What to build (approved D5 items; confirm against the planner's message)

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

## Out of scope

- Semantic ranking.
- Model-written citations.
- S6 quality work.
- Changing the default context profile.
- Memory and PDF.
- S4-F7.
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
./gradlew --offline :ui:allTests --rerun   # or the ui test task the build defines; name it
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
