# S3 keyword passage retrieval

Implemented 2026-11-11; updated 2026-11-12. **S3 host checks now pass**, including
migration verification after the scoped fresh-schema index correction below.
The schema correction was accepted for host-tested S3 scope. The ranking follow-up
below is separate. SQLCipher remains a later emulator gate;
no emulator/device runs occurred.

## Implementation and API

`SourcesRepository` now exposes `retrievePassages`, `passageCoverage` and explicit
`backfillPassages` on its existing synchronous serial storage lane. No dispatcher,
background job, cache, UI, SourceLibrary/session wiring or inference path was added.
Source-level AND search and stored display chunks are unchanged.

Migration `5.sqm` adds separate passages and an FTS4 index. Offsets are half-open
UTF-16 positions into the immutable revision. Sentence/whitespace cuts are preferred;
surrogate pairs are never split and text is neither trimmed nor discarded.
Passages, FTS rows and `PASSAGE_READY_V1` are committed in one transaction, nested
inside the whole import transaction for new sources. Delete removes both indexes
and passages in the existing fence-advancing transaction; titles are joined live.

Backfill is explicitly caller-triggered, at most two revisions per invocation,
with one bounded revision transaction at a time. Pending revisions remain absent
from search. Interruptions and quota/capacity failures roll back that revision;
prior publications and sources/chunks survive. Retrying is idempotent. No automatic
startup/backfill invocation was added: S4 must call this API on the storage lane.

The original S3 implementation counted distinct literal terms using an ascending-ID
OR scan. The accepted review identified term-selection and common-term ranking
limitations; the 2026-11-12 follow-up below replaces that strategy. Ranking remains
keyword-based, **not BM25, semantic similarity or proof of answerability**.
SQLDelight 2.1.0's hidden FTS projection typing blocked the initial matchinfo
approach; typed passage IDs with index membership need no dependency or driver
change. JDBC tests verify ordering and native docid-range plans without a
whole-result temporary sort. SQLCipher compatibility remains unverified.

## Bounds and result contract

| Bound | Value |
| --- | --- |
| Passage target / minimum non-final span / overlap | 384 / 256 / 0 UTF-16 units |
| Maximum passages per source | 256 |
| Separate passage quota | 128,000 rows |
| Query input / distinct literal terms | 1,024 UTF-16 units / 8 selected terms; content words before question boilerplate |
| Scan page / total posting rows read | 64 / 4,096, including probes and overlapping IDs |
| Index queries per scoring page | At most 8 membership queries; each candidate page is read once, at most 9 queries including its scan |
| Initial probes | Up to 8 term pages plus one full-term intersection, at most 576 ID rows; charged to the same scan budget |
| Best-candidate heap / sorted copy | 50 score-ID pairs / 50 pairs |
| Temporary ranking data | At most 512 cached term-probe IDs, one 64-ID intersection/page, 64 scores, one 64-ID membership result and a deduplication set of at most 4,096 IDs |
| Final text fetch | At most 8 passages; sequential one-row reads, stricter than 8 per batch |
| Returned text | Caller budget capped at 65,536 UTF-8 bytes; no clipping |
| Open cursors | At most 1 |
| Query/backfill deadline | 5,000 monotonic milliseconds, clock and continue signal injected |
| Backfill revisions per invocation | At most 2 |

Quota arithmetic: UTF-16 length cannot exceed admitted UTF-8 bytes. With minimum
256-unit non-final progress and no overlap, each 65,536-byte source needs at most
ceil(65,536 / 256) = 256 passages; 500 sources need 128,000. A worst-case whitespace
fixture reaches that bound. Display-chunk quota remains separate. Import and
backfill both enforce this quota and the existing 512 MiB library / 512 MiB disk
reserve / 64 MiB transaction reserve, including intermediate writes.

`PassageRetrieval` carries context epoch, indexed/total revision coverage, passages
(source ID/current title/revision ID/passage ID/UTF-16 span/text/score), computed
COMPLETE/INCOMPLETE status, typed reason, scanned/fetched counts and UTF-8 size.
No-hit and not-yet-indexed are distinguished by coverage. Truncated term selection
is explicit. Reasons include scan limit, cancellation, deadline, context change,
query limit and text budget; backfill additionally reports revision limit/quota.

Checks occur between pages/index queries/fetches and at write checkpoints. An
in-flight SQLite statement is not forcibly interrupted; actual device latency is
not established. A stop/deadline/context loss returns no excerpts. Scan-cap results
may return the best of the scored subset, explicitly incomplete. Exactly reaching
the cap on a full scan page is conservatively incomplete, without another lookahead query. COMPLETE
never means that unindexed revisions were searched: coverage remains explicit.

## Verification and remaining gate

- PASS: `./gradlew --offline :shared:jvmTest --tests 'dev.edgecompanion.core.sources.data.*'`
  during development; final suite includes **39 Sources methods**, all passing
  (25 new passage tests and 14 unchanged existing Sources tests).
- PASS: `./gradlew --offline :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`.
  JVM: **196 passed, 2 skipped, 0 failed/errors**. Both skipped methods are
  `UseTokenizerReferenceTest` opt-ins requiring unset `P2_ENCODER_COUNTING_DIR`;
  no archived-input failure is inferred. Lint: **0 errors, 7 warnings**.
- INITIAL ATTEMPT BLOCKED: the migration task in `./gradlew --offline :shared:verifySqlDelightMigration
  :androidApp:assembleDebug :shared:jvmTest :androidApp:lintDebug`.
  Exact cause: "Verifying a migration requires a
  database file to be present." At that point no schema `.db` snapshot existed.
  Nothing was disabled to obtain a pass. The authorized addition below resolves
  this prerequisite, but verification still fails on the newly exposed drift.
- Development SQL interface generation initially failed on hidden FTS expression
  typing; `:shared:generateCommonMainCompanionDatabaseInterface
  :shared:compileKotlinJvm` passed after the typed-ID query correction. The
  final build/test checks above use that corrected code.
- JVM migration regression passes against a genuine pre-passage database created
  by migrations 0–4, preserving chat/draft/attachments/source search/display chunks,
  then backfilling the same spans as fresh import. SQLDelight schema version is
  **5 before / 6 after**; migration filenames are numbered **4 before / 5 added**.
  The new DDL is idempotent because an unchanged older test reconstructs a prior
  schema by dropping only the previously known library tables.
- File reopen after interrupted backfill, every cooperative interruption
  checkpoint, exact passage-quota boundaries, mid-write capacity failures, scan
  caps, text budgets, late better candidates and context deletion are covered.

## Approved baseline addition and exposed discrepancy

The user approved the one-file scope amendment on 2026-11-11. Added only
`shared/src/commonMain/sqldelight/dev/edgecompanion/db/5.db` alongside this results
update. No build configuration, Kotlin code, current schema definition or historical
migration was changed during this follow-up.

Baseline size: **126,976 bytes**. SHA-256:
`53a7b0406145c5abcef1974beb7f4a7b8f4f8ee6ce254886e020fdccf796938b`.

Generation: execute the unchanged `0.sqm` through `4.sqm`, in order, using
Python's SQLite module against a new empty database, set `PRAGMA user_version=5`,
commit and close. The existing QA-reviewed candidate was copied byte-for-byte;
it was not generated from today's schema or altered to hide a mismatch. Read-only
revalidation used Python 3.9.6 / SQLite 3.51.0: integrity check `ok`, zero rows in
all application tables, no `source_passage*` objects, and exact `sqlite_master`
schema equality with a separate replay of migrations 0–4.

| Generation input | SHA-256 |
| --- | --- |
| `migrations/0.sqm` | `a4aaa30eaf237b6482debc639e52797052f5b6913f8efcd75ec2585e66510357` |
| `migrations/1.sqm` | `15301828dad21505873c280207585fab070037010e9cbd9c8f0bbfca37c7b90e` |
| `migrations/2.sqm` | `763a34cc007c9bce9d86a0c1b0100bf2f7443dce28c0a0672fb07112dd4efd6c` |
| `migrations/3.sqm` | `8dbb620dbedf4f9428bdb1bd8f034b09b2412e7a4eb28b54caecf21c07eba87e` |
| `migrations/4.sqm` | `7ce255d10c0bb428df643fe7c751a07e634f1ad4b8daaadcd1e2bf6d4a962a0d` |

**FAILED:** `./gradlew --offline :shared:verifySqlDelightMigration --console=plain`
exited 1 after applying migration 5 to the copied baseline and comparing it with
fresh schema creation. Full output: `/tmp/pma-s3-migration-baseline-added.log`.

The historical failure reported differences concerning the existing message table:

- `Conversation.sq` declared `generation_id TEXT UNIQUE`, producing the implicit
  `sqlite_autoindex_message_1` unique index.
- Unchanged `migrations/3.sqm` adds `generation_id TEXT` and separately creates
  the named unique index `message_generation_id`.
- The table-definition/index discrepancy repeats through the foreign-key
  references from `assistant_attempt` and `attachment`. The verifier reports no
  passage-table discrepancy.

This was a separately reported pre-existing mismatch. The 2026-11-11 baseline
addition did not alter any schema declaration to conceal it; the separately
authorized correction follows. The earlier full `/tmp` verifier log is no longer
present in the 2026-11-12 session. The failure description is retained here, with
an explicitly transcribed excerpt in
`build/s3-generation-index-correction-2026-11-12/historical-verifier-failure-excerpt.txt`.
That excerpt is not represented as the original raw log.

## Fresh-schema alignment, 2026-11-12

The scoped correction changes only `Conversation.sq`: `generation_id TEXT`
followed by `CREATE UNIQUE INDEX message_generation_id ON message(generation_id)`.
It aligns fresh creation with migration 3; no new migration is needed. All six
existing migrations (0–5), `5.db` and its checksum above remain unchanged.

New `GenerationIndexSchemaTest` compares fresh creation with a **temporary copy**
of the genuine schema-5 baseline migrated to the current schema. It compares
message columns and index names, uniqueness, origin, partial flag and columns.
Both paths reject duplicate non-null generation IDs while allowing multiple
null IDs and subsequent distinct IDs. The original baseline is never opened for
writing; only synthetic temporary copies are migrated.

- Before the fix, the focused regression ran 2 methods: metadata equality failed
  on the reproduced index mismatch; uniqueness behavior passed.
- After the fix, `./gradlew --offline :shared:verifySqlDelightMigration
  :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug --console=plain`
  **passed**. Migration verification executed successfully (not skipped).
- JVM: **198 passed, 2 skipped, 0 failures/errors** out of 200 methods. Both
  previously described tokenizer opt-ins remain skipped; all 39 Sources methods
  and both new schema methods pass. Debug build passes; lint has 0 errors and
  7 warnings.
- Byte/hash preservation checks confirm the schema-5 baseline and migrations
  0–5 are unchanged. No verification rule or dependency was relaxed.

Raw failing-regression and passing-verifier/build/test logs are retained in
`build/s3-generation-index-correction-2026-11-12/` as `regression-before.log`
and `checks-after.log`. Historical verifier failure evidence remains separately
labeled. No unrelated schema or source changes, device runs, data resets or
commits were made by this follow-up. S3 host acceptance checks are satisfied;
independent review of the correction and the later SQLCipher gate remain distinct.

NOT RUN, as required by S3: emulator/physical-device tests. Before S4 relies on
retrieval on Android, designate an emulator and add/run
`PassageRetrievalSqlCipherTest` under `androidApp/src/androidTest/...`: encrypted
schema-5 migration/backfill, literal FTS4 queries, docid range query plans, cursor
and fetch bounds, rollback/reopen, rename/delete/context fencing and coverage.
JDBC passing does not prove SQLCipher availability or behavior. No commits,
planning-file edits, native/model workloads or QA-only runtime changes occurred.
Grounded answers, semantic retrieval and complete P2 acceptance remain later work.

## 2026-11-12: authorized ranking follow-up (findings 1 and 2)

User scope: fix late informative query words and common-term/oldest-first starvation.
Backfill wiring and capacity measurement are unchanged. No UI, conversation,
inference, scheduler, dependency or schema migration changes were needed.

**Term selection:** inspect all distinct words in the bounded 1,024-UTF-16-unit
question (at most 512 distinct one-unit words separated by delimiters). Keep
content words before a small, explicit English question/function-word list.
Boilerplate is lower priority, not prohibited: it fills any remaining slots.
Preserve case-insensitive deduplication, literal quoting, numbers, negation, units
and non-English terms. At most eight terms are selected. If any distinct words
are omitted, `queryTermsTruncated` and `QUERY_LIMIT` remain visible; more than eight
content words still cannot all be searched. This is a deterministic heuristic,
not entity recognition or a multilingual stopword model.

**Candidate discovery and scoring:** read one first page of at most 64 IDs for
each selected term, checking stop/deadline/epoch between queries. Each term's
weight is `importance / max(1, probe row count)`. Content importance is 1. If
content is present, fallback boilerplate importance is 1/512; otherwise its
importance is 1, preserving all-boilerplate search. A content hit has weight at
least 1/64; all at most seven fallback words combined have weight strictly below
that. An uncommon question word therefore cannot outrank any content hit merely
through rarity. Counts below 64 are exact for that term;
64 is censored, not an estimate of full corpus frequency or IDF. Before long
posting lists continue, seed up to 64 passages matching all selected terms and
score every term's probe, scarcer first. Then continue term posting lists in the
same stable scarcity order. Candidate score is the sum of matched-term weights;
equal scores use ascending immutable passage IDs.

Every probe/intersection/continuation posting row counts against the **same
4,096-row budget**, even repeated IDs. Probe pages are reused, not re-read.
A bounded ID set prevents duplicate heap entries. Membership SQL restricts both
ordinary IDs and FTS docids to the **exact candidate IDs**, as well as typed
bounds: a sparse rare-term page must not silently lose its later matches behind
unrelated common-term hits in the ID interval. No corpus-wide count query,
whole-result sort or passage text is used for ranking. Text is read only for
at most eight final candidates under the existing UTF-8 budget.

This recovers a late rare-term answer and a late full-term intersection even when
individual common-term lists exceed the cap. It does **not** establish complete
library-wide best results after the cap: all-common queries and unexamined partial
matches can still be incomplete, explicitly `SCAN_LIMIT`. Overlaps consume work
budget, so fewer than 4,096 unique candidates may be scored. The bounded heuristic
has not been qualified on the full admitted library or on a device.

**Verification:**

- PASS: focused passage tests; final full run contains **33 passage tests**
  (six common rules, seventeen JDBC retrieval, ten unchanged backfill tests).
  Regressions cover late details after boilerplate, a rare answer beyond the
  old 4,096 cutoff, both common terms exceeding that cutoff with a late
  conjunction, sparse candidate spans, rarity weighting, overlap deduplication,
  actual posting-row accounting, rare boilerplate versus common content,
  all-boilerplate fallback, cancellation during probes and continuation,
  stable ties, text/cursor bounds and existing deadline/context checks.
- PASS: `./gradlew --offline :shared:verifySqlDelightMigration :shared:jvmTest
  :androidApp:assembleDebug :androidApp:lintDebug --console=plain`.
  **208 JVM tests: 206 passed, two existing tokenizer opt-ins skipped, zero
  failures/errors.** All 47 Sources methods pass, including the 14 unchanged
  pre-S3 Sources methods. Lint: **zero errors, seven warnings**.
- Development attempts are retained separately: initial expectations still used
  the former score/omission behavior; the next run exposed a wrong stable-order
  expectation in the new test; the first combined run exposed an undersized
  continuation fixture (93 passages, not two full pages). Corrected assertions
  and a larger valid fixture passed; no production safeguard was relaxed.
- NOT RUN: emulator/SQLCipher and physical-device tests, semantic/retrieval-quality
  qualification or latency/capacity benchmarks. The later emulator gate above
  still applies before S4 uses retrieval on Android.

Logs and a source/preservation receipt are retained in
`build/s3-ranking-followup-2026-11-12/`. No Git mutations or planning-file edits
were performed. Independent review is tracked separately from these host checks.

Focused QA caught a rare-boilerplate ranking counterexample after the first full
passing run: `What` alone could outweigh two common content terms. The importance
bound above and an exact under-cap regression (64 contract passages, 64 deadline
passages, one lunch distraction and one answer, with a one-passage result limit)
close that issue. The regression also checks that an all-boilerplate query still
finds its literal fallback. The final acceptance counts above are after this fix;
earlier passing logs are retained as earlier snapshots, not current evidence.

Independent focused QA: **PASS**, no remaining actionable findings in the ranking
follow-up. The first review identified the boilerplate-weight issue recorded
above; the follow-up verified the bound and exact regression against the final
acceptance evidence. QA did not rerun tests or device work. SQLCipher and broader
retrieval-quality/capacity qualification remain outside this verdict.

## 2026-11-16: query truncation reflects omitted content words

`QUERY_LIMIT` and `queryTermsTruncated` now indicate that the eight-term
selection omitted at least one content word. Dropping only filler allows a
complete result when the remaining search finishes within its other bounds.
The JDBC regression uses “What is the deadline for the contract in my notes?”
and expects a complete result. The existing overflow test still checks that
more than eight content words produce `QUERY_LIMIT`. Two existing passage tests
now expect no truncation for long questions that omit only filler.
