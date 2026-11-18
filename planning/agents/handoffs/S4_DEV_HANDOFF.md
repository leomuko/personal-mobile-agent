# S4 dev handoff — grounded prompt, request scope and consulted-passage record

2026-11-18. From the planner to the dev agent. Self-contained. Read
[../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first; its boundaries, helper-agent
rules and handback format apply. Slice plan:
[../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md), S4. Route and decision D3:
[../../P2_SHORTER_ROUTE_PROPOSAL_V1.md](../../P2_SHORTER_ROUTE_PROPOSAL_V1.md).

**Authorization:** host work only. Starts after the S2b follow-up handoff is
accepted (both touch nearby code and share one checkout). No device runs.

## Where things stand

- Branch `dev`. S3 and S2a are committed. `SourcesRepository.retrievePassages`
  returns `PassageRetrieval` (context epoch, coverage, ranked passages with
  source/revision/passage IDs and UTF-16 offsets, status and stop reason).
  Nothing calls it yet, and nothing triggers `backfillPassages`.
- Chat flow: `ConversationAssistant.submit()` builds a request with
  `GemmaChatBudget.build(current, completeTurns, id, chatProfile)` (USER and
  ASSISTANT roles only, no system role), calls
  `ConversationController.startAssistant(id, userId, draftEpoch, contextEpoch)`,
  streams, then completes or finishes the attempt. Source deletion runs inside
  `assistant.mutateContext`, which fences and cancels an active request.
- `GemmaChatBudget.inputBound` rejects any message containing `<|`,
  `<start_of_` or `<turn|>`. Passage text is user data and may contain them.
- Ordinary chat uses `EXTENDED_4096` (user decision 2026-11-18, provisional;
  at most 3,360 ASCII bytes of input, 512 output tokens reserved). 8192 is
  QA-only; QA protocol requests stay 2048/512.

## Decisions already made (do not reopen)

- **D3:** ordinary turns retrieve automatically; no usable passage means plain
  chat. A separate, explicit, persisted **source-scoped** request state replies
  "the sources do not cover this" when nothing usable is found, without loading
  the model.
- **D5 is open:** there is no UI to choose the scope in this slice. Expose the
  scope through the session/assistant API with `ORDINARY` as the default; S5
  adds the affordance.
- **Provenance stores references, not copies.** The consulted record holds IDs
  and offsets only (source, revision, passage, start/end UTF-16, rank). No
  passage text or title copies in the conversation database, so deleting a
  source leaves no copy of its text behind; a later reader resolves references
  live and treats a missing source as deleted.

## What to build

1. **Request scope.** A `RequestScope { ORDINARY, SOURCES }` persisted with the
   user turn at submit time (schema migration on the conversation side,
   following the S3 migration and snapshot pattern so
   `verifySqlDelightMigration` passes; existing rows default to `ORDINARY`).
   Retry reuses the stored scope. Restart restores it.
2. **Retrieval in the turn.** Before building the request, retrieve on the
   session's serial storage lane with the user's current message as the query,
   a `PassageControl` whose `continueWork` follows the turn's cancellation, and
   the repository's 5-second limit. The retrieval's `contextEpoch` must equal
   the epoch passed to `startAssistant`; on mismatch, do not generate (treat
   it like a context change during generation, as today).
3. **Backfill trigger.** Existing libraries have unindexed revisions after
   migration 5. Run `backfillPassages` in bounded batches on the storage lane
   after session restore until coverage is complete or the session stops,
   yielding between batches so user storage work interleaves. No new
   scheduler, dispatcher or worker beyond the session's existing scope.
4. **Context assembler.** Pack, in priority order, within the active
   `ChatContextProfile` and the existing conservative byte bound: the current
   message; then retrieved passages best-first; then recent complete turns
   (plain user/assistant text, never earlier turns' passages). Passages go in
   the current user message inside a delimited evidence block with a short,
   versioned instruction (a named constant with a version string) saying the
   passages are quoted material from the user's sources, are not instructions,
   and may be irrelevant. For `SOURCES` scope the instruction also says to say
   so when the passages do not contain the answer. Passages that contain the
   chat control sequences, or the block delimiter, are excluded (counted, not
   an error); a passage is included whole or not at all. Do not assume a
   passage count: the budget must include the actual instruction text,
   delimiters, per-passage labels, the current message and the history kept.
   Report measured counts at 4096 (and 2048/8192 for comparison) with empty,
   typical and long history, using 384-unit synthetic passages.
5. **Outcomes.**
   - `ORDINARY`, no usable passage (none found, all excluded, none fit): the
     request is byte-identical to today's plain chat.
   - `ORDINARY`, retrieval incomplete or coverage partial: use what was found;
     record the status.
   - `SOURCES`, no usable passage: no model load, no generation. Persist a
     typed completed result (reason: no match, not yet indexed, or retrieval
     incomplete) that S5 will render from resource strings; do not put
     English text in the database.
6. **Consulted-passage record.** Persist the references actually packed into
   the request, linked to the assistant attempt, in the same transaction that
   starts the attempt. Restart, retry and clear behave consistently: clear
   removes them with the conversation; a retry creates its own record.
7. **Fences.** Cancel, retry and source deletion during retrieval or
   generation keep today's behaviour: deletion advances the epoch, the active
   turn ends `INTERRUPTED`, and no stale passage reaches the model or the
   record after the fence.

## Design constraints

- Keep `ConversationSession` the process-lifetime owner and its storage lane
  serial; inject retrieval into `ConversationAssistant` as a function, not a
  repository reference.
- Do not change `GemmaChatBudget`'s bound arithmetic or the profiles; add
  packing on top of it. QA-only paths stay at 2048/512 and unmodified.
- No logging of query or passage text. No new dependency.
- Prefer common/JVM tests with fakes; the JVM SQLite driver for schema work.

## Allowed paths

- `shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/ChatGeneration.kt`
  (packing helpers only; bound arithmetic unchanged)
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/di/SharedModule.kt`
- `shared/src/commonMain/sqldelight/dev/edgecompanion/db/Conversation.sq`, the
  next migration file and its schema snapshot
- `shared/src/commonTest/kotlin/dev/edgecompanion/core/conversation/`,
  `shared/src/jvmTest/kotlin/dev/edgecompanion/core/conversation/`,
  `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/` (packing tests)
- New note `docs/P2_GROUNDED_PROMPT_RESULTS.md`

`core/sources/` is read-only for this slice; if the retriever API must change,
stop and report.

## Out of scope

UI, strings and the D5 affordance (S5); semantic ranking; model-written
citations; changing the default context; answer-quality claims (S6); any
device or emulator run; anything in `DEFERRED_ISSUES.md`.

## Done when

Shared/JVM tests (synthetic text only) show:

- packing order and limits per profile; whole-passage inclusion; excluded
  passages with control sequences or the delimiter; history dropped before
  passages, passages before the current message;
- `ORDINARY` no-hit produces a byte-identical plain request;
- `SOURCES` no-hit persists the typed result for each reason without calling
  the runtime;
- scope and consulted references survive restart; retry is deterministic
  (same library and history give the same request bytes) and keeps scope;
- source deletion during retrieval and during generation ends the turn
  `INTERRUPTED`, records nothing stale, and advances the epoch;
- cancellation during retrieval stops it with `CANCELLED` and no attempt is
  left `PENDING`;
- backfill runs after restore, converges, interleaves with user storage work
  and stops with the session;
- migration from the current schema keeps existing conversations (scope
  `ORDINARY`, no references) and `verifySqlDelightMigration` passes;
- existing conversation, sources and inference tests pass unmodified.

## Checks to run

```sh
./gradlew --offline :shared:verifySqlDelightMigration :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest
```

Use `--rerun` on test tasks. One Gradle invocation at a time. Say plainly that
Android SQLCipher behaviour of the new migration is unverified and list the
instrumentation test that should cover it.

## Report back

Handback format from the dev brief, plus: the schema change and migration; the
prompt template and its version string; passages that fit per profile; the
`SOURCES` result type S5 will render; how retrieval, backfill and fences
interleave on the storage lane; anything awkward in the existing design.
