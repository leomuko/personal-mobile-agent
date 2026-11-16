# S2a dev handoff — ordinary-chat context profiles (+ S3 query-scope correction)

2026-11-16. From the planner to the dev agent. Self-contained. Read
[../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first; its boundaries and
handback format apply. Slice definition:
[../../P2_SLICE_PLAN_V1.md](../../P2_SLICE_PLAN_V1.md), S2a.

**Authorization:** active only when the user or planner tells you to start.
The user authorized exactly the commits in Part 0 (2026-11-16). No other
commits, no push, no device or emulator runs, no scope beyond this file.

## Where things stand

- Repository `/Users/kezileo/Desktop/personal_mobile_agent`, branch `dev`,
  `HEAD` `f04b77b`. S3 (keyword passage retrieval) and its ranking follow-up
  are implemented, QA-passed and **uncommitted** in the working tree. Do not
  modify S3 files except as Part 1 below says.
- Ordinary chat is limited by `GemmaChatBudget` to `contextTokens == 2048`
  with 512 reserved for output. Its conservative byte bound
  (128 + Σ(96 + UTF-8 bytes per message)) leaves about 1,312 ASCII bytes for
  the current message plus history. S4 will add retrieved passages, which do
  not fit. The user approved enlarging the context for ordinary chat; the
  final size will be chosen by S24 measurement (S2b), not in this slice.

## Part 0 — commit the QA-passed S3 work first

Do this **before** any edit, so S3 is recorded exactly as reviewed and Part 1
lands as a separate change later. This is the only Git write you may make.

1. Confirm the starting state. Stop and report if any check fails.

   ```sh
   cd /Users/kezileo/Desktop/personal_mobile_agent
   test "$(git branch --show-current)" = dev
   test "$(git rev-parse --short HEAD)" = f04b77b
   git diff --cached --quiet
   python3 - <<'PY'
   import hashlib, json
   d = json.load(open('build/s3-ranking-followup-2026-11-12/host-checks.json'))
   bad = [p for p, h in d['sourceHashes'].items()
          if hashlib.sha256(open(p, 'rb').read()).hexdigest() != h]
   for p, v in d['preserved'].items():
       if hashlib.sha256(open(p, 'rb').read()).hexdigest() != v['sha256']: bad.append(p)
   print('changed since QA:', bad) if bad else print('S3 files match QA-checked hashes')
   raise SystemExit(1 if bad else 0)
   PY
   ```

2. Create these six commits in order, with exactly these paths and messages.
   **Do not add any `Co-Authored-By` trailer** (user instruction). Do not
   use `git add -A` or `git add .`.

   ```sh
   S=shared/src/commonMain/kotlin/dev/edgecompanion/core/sources
   DB=shared/src/commonMain/sqldelight/dev/edgecompanion/db
   T=shared/src/jvmTest/kotlin/dev/edgecompanion/core

   git add -- $DB/Conversation.sq $T/conversation/data/GenerationIndexSchemaTest.kt
   git commit -m "fix(db): align fresh message generation index with migration 3"

   git add -- $DB/Library.sq $DB/migrations/5.sqm $DB/5.db
   git commit -m "feat(sources): add passage table, FTS index and migration 5"

   git add -- $S/data/PassageIndex.kt $S/data/PassageRanking.kt $S/data/PassageRules.kt \
     $S/model/PassageModels.kt $S/data/SourcesRepository.kt
   git commit -m "feat(sources): add bounded keyword passage retrieval and backfill"

   git add -- shared/src/commonTest/kotlin/dev/edgecompanion/core/sources/data/PassageRulesTest.kt \
     $T/sources/data/PassageBackfillTest.kt $T/sources/data/PassageRetrievalTest.kt \
     $T/sources/data/PassageTestDatabase.kt
   git commit -m "test(sources): cover passage retrieval, backfill, quotas and migration"

   git add -- docs/P2_PASSAGE_RETRIEVAL_RESULTS.md
   git commit -m "docs(p2): record S3 passage retrieval results and remaining SQLCipher gate"

   git add -- planning/P2_SHORTER_ROUTE_PROPOSAL_V1.md planning/P2_SLICE_PLAN_V1.md planning/agents
   git commit -m "docs(p2): record revised shorter route, slice plan and dev/QA agent briefs"
   ```

3. Verify and include the output in your handback:

   ```sh
   git log --oneline f04b77b..HEAD
   git status --short
   git log f04b77b..HEAD --format=%B | grep -ci co-authored || true
   ```

   Expect six commits, an empty status, and `0`. Do not push.

## Part 1 — S3 correction: query truncation counts only content words

Small, separate fix found in planner review on 2026-11-16.

- **Problem:** `PassageRules.query` sets `truncated = terms.size > QUERY_TERMS`,
  where `terms` includes question filler. Ordinary questions such as
  "What is the deadline for the contract in my notes?" (9 distinct words,
  3 content words) therefore return `INCOMPLETE` / `QUERY_LIMIT` although
  every content word was searched. S4 must not read that as an incomplete
  search.
- **Fix:** `truncated` (and `PassageRetrieval.queryTermsTruncated`) is true
  only when a **content** word was dropped. Dropping filler alone is not
  truncation. If the fix needs to report dropped filler, use a separate
  field; do not reuse `QUERY_LIMIT` for it.
- **Allowed paths:** `core/sources/data/PassageRules.kt`,
  `core/sources/data/PassageIndex.kt` only if needed,
  `core/sources/model/PassageModels.kt` only if a field is added, the passage
  tests, and a short dated addendum in `docs/P2_PASSAGE_RETRIEVAL_RESULTS.md`.
- **Done when:** a test shows the example question above returns `COMPLETE`
  with no `QUERY_LIMIT`, and a question with more than eight content words
  still reports truncation. Existing passage tests pass unmodified, except
  where a test asserted the old filler behaviour; list any such change.

## Part 2 — S2a: ordinary-chat context profiles

### What exists today (verify before relying on it)

- `core/inference/ChatGeneration.kt`: `ChatRequest(id, messages, backend = GPU,
  contextTokens = 2048, outputTokens = 512)`; `GemmaChatBudget.validate`
  rejects `contextTokens != 2048`; `build(current, completeTurns, id)` packs
  history newest-first until the bound fails.
- `core/conversation/session/ConversationAssistant.kt` calls
  `GemmaChatBudget.build(...)` and `runtime.generateChat(request)`.
- `core/inference/ModelManager.kt`:
  - `verificationCall` builds `ChatRequest(id, listOf(...))` **from the
    defaults**; `prepareChat` and the constrained path also assume 2048/512
    (`request.outputTokens != 512` check; `ConstrainedDecoding.check`).
  - `chatFlow`: when it already owns an engine, it throws `BUSY` if
    `status != LOADED || backend != request.backend`. There is **no
    context-size identity check**: a 2048 engine would be reused for a
    request asking for another size, and the engine then rejects it.
  - Unowned path loads with `ModelConfig(path, sha, request.contextTokens,
    request.backend, request.outputTokens)`.
  - `load(backend)` (Models panel) loads with the manifest's 2048/512.
  - `cleanupOwnedEngine()` is the confirmed release (5 s timeout, poisons on
    failure). The `operation` mutex owns all of this; keep its rules.
- `shared/src/androidMain/.../litert/LiteRtLlmEngine.kt`: ordinary chat
  requires `current.contextTokens == chat.contextTokens`; the constrained
  path requires 2048/512. Probably needs no change.
- `core/inference/ModelManifest.kt`: Gemma artifact context 32,000,
  configured 2048, output 512.
- DI: `androidApp/.../di/ModelModule.kt`, `core/di/SharedModule.kt`.
  `androidApp/build.gradle.kts` already shows the pattern for a
  `secureQa`-only Gradle property (`androidPressureMemoryTest`).

### What to build

1. **Named chat profiles.** A small type naming context and output
   reservation, with exactly three values: standard 2048/512 (today's
   behaviour), extended 4096/512 and extended 8192/512. Validate each
   against the manifest's artifact context. No free-form sizes.
2. **Budget accepts profiles.** `GemmaChatBudget.validate` accepts a request
   whose `contextTokens`/`outputTokens` equal one of the profiles; anything
   else stays `CONTEXT_LIMIT`. The byte-bound formula is unchanged; only its
   ceiling follows the profile. `build(...)` takes the profile explicitly so
   extra room is used for history.
3. **`ChatRequest` defaults stay 2048/512.** QA requests inherit them. Do not
   change the defaults or any QA construction site.
4. **Conversation path supplies the profile.** `ConversationAssistant` gets
   the profile from its constructor (a value or provider), defaulting to
   standard, wired through `ConversationSession` and `sharedModule`.
5. **Build selection, QA only.** Add a Gradle property (e.g.
   `chatContextTokens`, allowed values 2048/4096/8192, default 2048) applied
   only to the `secureQa` build type, following the existing
   `androidPressureMemoryTest` pattern. Debug and release always use
   standard. An invalid value fails the build.
6. **Loaded-engine identity in `ModelManager`.** Record the backend, context
   and output the engine was loaded with (expose context in
   `ModelRuntimeState` if useful). In `chatFlow`, when the manager owns a
   `LOADED` engine on the **same backend** but a **different context or
   output**, perform a confirmed release (`cleanupOwnedEngine()`) and then
   load the requested configuration through the existing loading path and
   memory admission. A failed release is `RESTART_REQUIRED`, as today. A
   backend mismatch keeps today's `BUSY` behaviour; do not add backend
   switching. The QA paths that already release before loading keep doing so.
7. **Token counter report (no code use).** Inspect the pinned LiteRT-LM
   0.17.1 Kotlin API from the cached artifact (no network) and report whether
   it exposes a token-count call. Report only.

### Allowed paths (Part 2)

- `shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/` —
  `ChatGeneration.kt`, `ModelManager.kt`, `ModelManifest.kt`, and a new file
  for profiles if you prefer.
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/session/`
  — `ConversationAssistant.kt`, `ConversationSession.kt`.
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/di/SharedModule.kt`
- `androidApp/src/main/kotlin/dev/edgecompanion/app/di/ModelModule.kt` and
  the app's Koin wiring that passes the profile, if needed.
- `androidApp/build.gradle.kts` — only the `secureQa` property and
  `buildConfigField` for item 5.
- `shared/src/androidMain/.../litert/LiteRtLlmEngine.kt` — only if a check
  must change for ordinary chat; never the constrained path.
- Tests under `shared/src/commonTest/kotlin/dev/edgecompanion/core/inference/`,
  `.../core/conversation/session/`, and `androidApp/src/test/`.
- A new note `docs/P2_CONTEXT_PROFILES_RESULTS.md`.

### Out of scope

The V3, V4 and constrained-decoding paths and their 2048/512 pins;
`MemoryPolicy` thresholds and its export fields; memory telemetry format; the
Models panel UI (it may keep showing 2048); the 512 output reservation;
`scripts/verify-chat-budget.*` and its fixtures; retrieval or prompt
assembly (S4); any device or emulator run; anything in `DEFERRED_ISSUES.md`.

### Done when

Tests (fake engine, synthetic text) show:

- each profile's byte bound: the largest admitted ASCII message for 2048 is
  unchanged (1,312 bytes), and 4096 and 8192 admit their computed values;
  one byte more is rejected; any non-profile size is `CONTEXT_LIMIT`;
- history packing with the 8192 profile keeps more turns than with 2048 for
  the same conversation, newest-first, never splitting a turn;
- QA-2048 → ordinary-4096 → QA-2048 and ordinary-8192 → Models-panel
  `load()` transitions each perform exactly one confirmed release and one
  load, with no overlap and no reuse of a mismatched engine;
- a preloaded engine of the wrong context is never used for generation;
- cancelling during the reload ends with a consistent state (unloaded, or
  loaded with the requested profile), the operation lock released, and no
  second load racing the first;
- a release failure during reload yields `RESTART_REQUIRED` and poisons as
  today; memory admission still applies to the reload;
- a backend mismatch still returns `BUSY`;
- `ChatRequest` defaults and every QA construction site still produce
  2048/512, and existing V3/V4/constrained/foreground-handoff tests pass
  unmodified;
- the Gradle property defaults to 2048, rejects other values, and does not
  affect debug or release builds.

### Checks to run

```sh
./gradlew --offline :shared:verifySqlDelightMigration :shared:jvmTest :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest
./gradlew --offline -PtestBuildType=secureQa -PchatContextTokens=4096 :androidApp:assembleSecureQa
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew --offline :shared:testAndroidHostTest
```

The last one is needed only if `androidMain` changes; say so if skipped.
Nothing here measures device memory, load time or latency: state that the
larger profiles are **unmeasured** and must not become a default before S2b.

## Report back

Use the dev brief's handback format, for Part 1 and Part 2 separately. Also
state: the byte bound admitted by each profile; how the loaded identity is
stored and compared; the exact release/reload sequence and where it can be
cancelled; the Gradle property's name, values and default; and the token
counter finding with the class or method name you inspected.
