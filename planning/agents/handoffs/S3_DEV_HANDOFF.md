# S3 dev handoff — keyword passage retrieval

2026-11-11. From the planner to the dev agent. Self-contained: you need no
prior conversation. Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first;
its boundaries and handback format apply. Slice definition:
[../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md), S3.

**Authorization:** the user accepted the route and decisions D1–D4 on
2026-11-11. This handoff becomes active only when the user or planner tells
you to start. It never authorizes commits, device runs or scope beyond this
file.

## Where things stand

- Repository `/Users/kezileo/Desktop/personal_mobile_agent`, branch `dev`,
  `HEAD` `f04b77b`. Uncommitted planning files exist
  (`planning/P2_SLICE_PLAN_V1.md`, `planning/agents/`, an edited
  `planning/P2_SHORTER_ROUTE_PROPOSAL_V1.md`); leave them alone.
- The app has working local chat and a text-only Sources pilot. Sources are
  stored in the encrypted SQLDelight database and searched by keyword at the
  whole-source level. Nothing retrieves passages for the model yet.
- Why this slice: the next product step is grounded answers. That needs
  ranked, prompt-sized passages with exact provenance. Later slices put them
  in the prompt (S4) and show them in the UI (S5).

## What exists today (verify before relying on it)

- Schema: `shared/src/commonMain/sqldelight/dev/edgecompanion/db/Library.sq`,
  migrations `migrations/0.sqm` … `4.sqm`, `verifyMigrations` on.
  - `source`, `source_revision` (one per source; full `text`; `status`
    default `'KEYWORD_READY'`), `source_chunk` (`revision_id`, `start_offset`,
    `end_offset`, `text`).
  - `source_search`: FTS4 over `title, text`, `docid` = source ID.
  - `context_eligibility` (`epoch`, `message_fence`); `advanceContext` runs
    inside source deletion.
- `core/sources/data/SourcesRepository.kt`: synchronous owner used only on
  `ConversationSession`'s serial storage lane; `list`, `text`, `importText`,
  `importUtf8`, `rename`, `delete`; quota and capacity checks; errors as
  `SourceException(SourceError)`.
- `core/sources/data/SourceTextRules.kt`: `chunks()` cuts fixed
  `SourceLimits.CHUNK_CHARS` (2048) pieces, surrogate-safe but not word-aware;
  `query()` builds a literal AND of at most eight quoted terms.
- `core/sources/model/SourceModels.kt`: limits (64 KiB per source, 500
  sources, 20,000 chunks) and model types.
- Tests: `shared/src/jvmTest/.../core/sources/data/SourcesRepositoryTest.kt`,
  `SourcesSessionTest.kt`, `shared/src/commonTest/.../SourceTextRulesTest.kt`.

## What to build

1. **A retrieval unit ("passage").** Today's 2048-character chunks are for
   paging the reader and are too large for a prompt. Add a separate, smaller
   unit derived from the revision text, with `start`/`end` offsets into that
   text in the same offset convention the existing chunks use. Prefer
   boundaries at whitespace or sentence ends; never split a surrogate pair.
   Choose and state the target size and any overlap, and make them named
   constants. Do not change, re-cut or delete existing `source_chunk` rows.
2. **A full-text index over passages**, added by migration `5.sqm`, with
   `Library.sq` kept consistent so migration verification passes.
3. **Backfill for sources that already exist.** SQL cannot cut passages, so
   the migration cannot populate them. Provide a bounded, restart-safe
   backfill owned by the repository (the `status` column on
   `source_revision` is available to mark progress). Until a revision is
   backfilled its passages are simply absent; nothing crashes and source-level
   search still works. New imports write passages in the same transaction as
   the rest of the import and stay within the existing quota and capacity
   checks.
4. **A retriever on `SourcesRepository`** that takes free user text and
   returns the best passages:
   - each result carries source ID, source title, revision ID, passage ID,
     start and end offsets, passage text and a score;
   - results are ranked best-first with a deterministic tie-break;
   - the caller passes a maximum count and a maximum total size, each also
     capped by named constants;
   - the result includes the context epoch read in the same transaction, so a
     later slice can detect that a source was deleted in between;
   - a natural-language question must not need every word to match: use
     any-term matching with relevance ranking, not the existing all-terms
     `query()`. Leave `query()` and source-level search behaviour unchanged.
   - user text never reaches SQLite as FTS syntax. Extract literal terms as
     `query()` does; cap term count and input length; an input with no usable
     terms returns an empty result, not an error.
5. **Deletion and rename.** Deleting a source removes its passages and index
   rows in the existing delete transaction. Rename keeps passage results
   showing the current title.

## Contracts tightened after review (2026-11-11)

These three override anything looser above.

### A. Bound the work, not only the output

A common word can match much of the library. Carry forward the bounds already
agreed in `planning/P2_IMPLEMENTATION_PLAN.md` (P2-05, "Bound query working
sets…") and `planning/P2_SEMANTIC_SEARCH_CONTRACT.md`:

- Scan matches in bounded pages, scoring from index data only, and keep a
  fixed-size best-candidates heap (at most 50 score/ID pairs). Do not load
  passage text while scanning, and do not hold all matches in memory.
- Fetch text only for the final candidates, in batches of at most 8 passages
  and at most 64 KiB of text per query.
- Check a caller-supplied continue/deadline signal between pages (inject the
  clock; the repository stays synchronous). The operation limit is 5 seconds.
  Also check that the context epoch has not moved; if it has, stop.
- Cap the total number of matches scanned with a named constant.
- **Never truncate before ranking and call the result the best passages.** If
  the scan stops early (scan cap, deadline, cancellation, epoch change), the
  result says so with a typed status and reason. A complete result means every
  match was scored.
- Bound open cursors and temporary arrays. State each constant you choose.

### B. Coverage is part of the result

"No passages" must be distinguishable from "not indexed yet".

- The result carries coverage: how many current revisions are passage-indexed
  out of how many exist, and whether coverage is complete.
- A revision's passages, their index rows and its ready marker are published
  in **one transaction**. A partially built revision is never searchable.
- Backfill is idempotent: rerunning it, or restarting after an interruption
  at any point, never duplicates passages and converges to the same rows a
  fresh import would produce.

### C. Passage storage has its own limits

The existing `SourceLimits.CHUNKS` counts display chunks only.

- Add a named passage-count limit sized for the maximum admitted library
  (500 sources × 64 KiB) at your chosen passage size and overlap, and show
  the arithmetic.
- Apply the count limit and the existing capacity checks
  (`LIBRARY_BYTES`, `DISK_RESERVE`, `TRANSACTION_RESERVE`) to passage and
  index writes in **both** import and backfill.
- A quota or capacity failure during import rolls the whole import back, as
  today. During backfill it leaves existing sources, chunks and already
  published passages intact, leaves that revision unindexed and resumable, and
  is visible through coverage.
- Units: offsets are half-open `[start, end)` UTF-16 code-unit positions into
  the revision text, the same convention as `source_chunk`. Result-size limits
  are in UTF-8 bytes of passage text. Name the unit in every constant and
  parameter.

## Design constraints

- The same code must work on both drivers: the JDBC SQLite driver used by
  `jvmTest` and SQLCipher on Android. The project uses FTS4 today. If you want
  FTS5 or its `bm25()`, first show it is available on both; otherwise rank in
  Kotlin from FTS4 `matchinfo`/`offsets` data or an equivalent approach. Say
  which you chose and why.
- Keep the repository synchronous and single-owner as it is now. No new
  coroutine scope, dispatcher, cache or background worker.
- Do not expose retrieval through `SourceLibrary`, `ConversationSession` or
  any UI in this slice; S4 does the wiring. A repository-level API with tests
  is the deliverable.
- No new dependency or version change.
- Passage text is user data: no logging of passage or query text.

## Allowed paths

- `shared/src/commonMain/sqldelight/dev/edgecompanion/db/Library.sq`
- `shared/src/commonMain/sqldelight/dev/edgecompanion/db/migrations/5.sqm`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/sources/data/`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/sources/model/`
- `shared/src/jvmTest/kotlin/dev/edgecompanion/core/sources/`
- `shared/src/commonTest/kotlin/dev/edgecompanion/core/sources/`
- A new short note `docs/P2_PASSAGE_RETRIEVAL_RESULTS.md` (what was built,
  constants chosen, checks run, limits).

If a compile fix is unavoidable elsewhere (for example a constructor call in
`core/di/`), make the smallest change and list it in the handback. Anything
larger: stop and report.

## Out of scope

Embeddings or semantic ranking; prompt assembly; `ModelManager`, inference and
conversation code; UI and strings; README; changing the display chunks or the
Sources screen's search; the retrieval comparison tooling (S1); anything in
`DEFERRED_ISSUES.md`.

## Done when

JVM tests (synthetic text only) show:

- passages cover the whole revision text, offsets map back to the exact
  substring, boundaries respect surrogate pairs, and size constants hold;
- a multi-word natural-language question returns the passage containing the
  answer terms ahead of unrelated passages, across several sources;
- count and size bounds are enforced; ties are ordered deterministically;
- hostile inputs (FTS operators, quotes, `NEAR`, `*`, column filters, very
  long text, no letters or digits) are safe and behave as specified;
- migration from schema version 4 with existing sources works: old data
  intact, source-level search unchanged, backfill produces the same passages
  a fresh import would, and an interrupted backfill resumes correctly;
- deleting a source removes its passages from results and advances the
  context epoch the retriever reports; rename is reflected;
- quota and capacity failures during import leave no partial passages;
- a query whose terms match far more passages than the scan cap returns a
  result marked incomplete with its reason, and a query under the cap is
  marked complete; a stop signal or expired deadline between pages ends the
  scan with the matching reason; text is fetched only for final candidates;
- searching while backfill is incomplete reports partial coverage, and a
  fully indexed library reports complete coverage;
- backfill interrupted at each stage and restarted yields no duplicates and
  the same passages as a fresh import; a passage-limit or capacity failure
  during backfill preserves all existing data and is resumable;
- the passage-count limit is enforced at its boundary for import and backfill;
- every existing Sources test still passes unmodified.

## Checks to run

```sh
./gradlew :shared:verifySqlDelightMigration
./gradlew :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug
```

Do not run emulator or device tests. On-device SQLCipher behaviour (including
FTS availability) is therefore **not verified** by this slice: say so plainly
in the handback and in the results note, and list the instrumentation test
that should be added or run when an emulator is designated.

## Report back

Use the handback format in the dev brief. In addition, state: the passage
size and overlap chosen and why; the ranking method and its driver
compatibility evidence; how backfill is triggered, bounded and resumed; and
any place where the existing schema or rules made a requirement awkward; every
bound from contract A with its value; the passage-limit arithmetic from
contract C; and the shape of the result type (status, reason, coverage,
context epoch, passages) that S4 will consume.
