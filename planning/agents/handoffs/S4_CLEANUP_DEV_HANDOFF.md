# S4 clean-up dev handoff: S4-F6 and the S4-F8 regression test

2026-11-19. From the planner to the dev agent. This handoff is self-contained.

Read [../DEV_AGENT_BRIEF.md](../DEV_AGENT_BRIEF.md) first. Its boundaries,
helper-agent rules and handback format apply. Background on the slice is in
[S4_DEV_HANDOFF.md](S4_DEV_HANDOFF.md).

**Authorization:** host work only. The user approved this clean-up on 2026-11-19.
No device or emulator runs.

## Where things stand

- Branch `dev`, `HEAD` `99aebc0`. S4 is committed (`0e35b99..dcbf40a`) and
  passed independent QA on the host.
- On the S24, the run passed 8 of 8 tests: `PassageSqlCipherTest` 3/3 at
  schema version 7, and `GroundingSqlCipherTest` 5/5.
- The latest independent QA evidence is in
  `build/qa-s4-followup-2026-11-19/independent-qa-0930/`. See `review.json`,
  `refresh-failure.log`, `S4FollowupProbe.kt` and `skip-gate-probe.test.mjs`.
  Read them. Do not modify them.

## Part A: S4-F6 (P3), finish the saved attempt when the post-commit refresh fails

**Scenario.**
1. `ConversationController.submit` calls `store.submitUserMessage(...)`, which
   durably commits the user message and its `PENDING` attempt in one
   transaction.
2. The following `mutable.value = snapshot()` throws. This happens around
   `ConversationController.kt:115`.
3. The catch block sets `MESSAGE_SAVE` and returns `null`.
4. `ConversationAssistant` returns early. This happens around
   `ConversationAssistant.kt:81`.
5. A durable `PENDING` attempt is left that nobody owns. The in-memory state
   still shows the draft.
6. A second send refreshes through the epoch guard without duplicating the
   message, but the attempt stays `PENDING`. There is no Retry until restore
   reconciles it to `INTERRUPTED`.

**Required behaviour.**
- Once the commit has succeeded, a refresh failure must not leave an unowned
  `PENDING` attempt.
- Separate "commit failed" from "commit succeeded, refresh failed".
- In the second case, finish that specific attempt as `INTERRUPTED`, so it
  shows Retry exactly as after a restore. Alternatively, reconcile it before
  returning.
- Then attempt one refresh so the UI reflects the stored state.
- If that refresh also fails, keep the error state. The attempt must still be
  terminal in storage.
- Keep these unchanged:
  - the existing epoch duplicate guard;
  - "commit failed means nothing durable";
  - the S4-F5 behaviour, where the `PENDING` attempt is written in the submit
    transaction.
- Do not add a new restore path or scheduler.

**Tests (JVM, synthetic).** Inject a one-shot read failure right after the
submit commit. Do this for both `ORDINARY` and `SOURCES` scope, and assert:
- there is exactly one user message and one attempt, now `INTERRUPTED`;
- no runtime call and no retrieval happen for that attempt;
- Retry is available without a restart, keeps the scope and creates no
  duplicate message;
- a later restore leaves the attempt unchanged.

Also add a test where the submit transaction itself fails: nothing is durable,
and the draft is kept.

## Part B: S4-F8, regression test only

The QA report found that the script already rejects
`INSTRUMENTATION_STATUS_CODE: -1` through `-4` through `FAILURE_PATTERN`, at
`scripts/verify-chat-context.sh:64` and around line 415. A skipped or
assumption-failed opt-in therefore already fails the run, even when the
transcript also says `OK (8 tests)`. The finding was a planning claim, not a
defect.

Make no script change unless a test shows one is needed. Add regression cases
to `scripts/tests/verify-chat-context.test.mjs` using the existing fake-adb
harness. In `sqlcipher` mode, a transcript that contains `OK (8 tests)` plus a
`-4` status, or plus a `-3` status, fails with reason `instrumentation`.

## Allowed paths

- `shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/session/ConversationController.kt`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/session/ConversationAssistant.kt`
- `shared/src/commonMain/kotlin/dev/edgecompanion/core/conversation/data/` only if
  a store method is genuinely needed to finish one attempt by ID; prefer the
  existing `finishAssistantTurn` or the equivalent
- `shared/src/jvmTest/kotlin/dev/edgecompanion/core/conversation/` (new or extended tests)
- `scripts/tests/verify-chat-context.test.mjs`
- A short dated section appended to `docs/P2_GROUNDED_PROMPT_RESULTS.md`

## Out of scope

- S4-F7, the Unicode-space marker lookalikes. It is left for later.
- S4-F3, retry after a temporary no-answer. It is an S5 design item.
- Any schema change, UI, strings, and the S5 or S6 work.
- Deferred issues.
- Device runs.

## Checks to run

```sh
./gradlew --offline :shared:verifySqlDelightMigration :shared:jvmTest --rerun :androidApp:assembleDebug :androidApp:lintDebug
./gradlew --offline :androidApp:testDebugUnitTest --rerun
node --test scripts/tests/verify-chat-context.test.mjs scripts/tests/qa-supervision.test.mjs
```

- Run one Gradle invocation at a time.
- After the node tests, no `SYNTHETIC` process may remain.
- Never invoke real adb.

## Report back

Use the dev-brief handback format. Also report:
- the exact state transitions for the refresh-failure case;
- the test names that would fail without the fix;
- the test counts before and after.
