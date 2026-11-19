package dev.edgecompanion.core.conversation.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.conversation.data.ConversationStore
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.ChatContextProfile
import dev.edgecompanion.core.inference.ModelError
import dev.edgecompanion.core.sources.model.PassageCoverage
import dev.edgecompanion.core.sources.model.PassageRetrieval
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

/**
 * S4-F6: the submit transaction commits the user message and its PENDING attempt, then the controller's refresh
 * fails. In-memory JDBC SQLite (not SQLCipher), synthetic text only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubmitRefreshFailureTest {
    private val question = "When does the synthetic greenhouse vent open?"
    private val passage = "The synthetic greenhouse vent opens at twenty degrees."

    private class Harness(val driver: JdbcSqliteDriver, val sql: SqlConversationStore) {
        /** Number of snapshot reads to fail right after the next submit commits. */
        var failReadsAfterCommit = 0
        var failFinish = false
        private var failing = 0
        val store = object : ConversationStore by sql {
            override fun finishAssistantTurn(id: String, status: TurnStatus, error: ModelError?) {
                if (failFinish) error("Synthetic finish failure")
                sql.finishAssistantTurn(id, status, error)
            }
            override fun submitUserMessage(text: String, timestamp: Long, scope: RequestScope, attemptId: String?): Message =
                sql.submitUserMessage(text, timestamp, scope, attemptId).also { failing = failReadsAfterCommit }
            override fun messages(): List<Message> {
                if (failing > 0) { failing--; error("Synthetic snapshot read failure") }
                return sql.messages()
            }
        }
        val controller = ConversationController(store) { 1L }
        val runtime = RecordingRuntime()
        var retrievals = 0
    }

    private fun TestScope.harness(): Pair<Harness, ConversationAssistant> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CompanionDatabase.Schema.create(driver)
        val h = Harness(driver, SqlConversationStore(CompanionDatabase(driver)))
        val lane = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val assistant = ConversationAssistant(lane, { h.controller }, h.runtime, ChatContextProfile.EXTENDED_4096) { _, _, _ ->
            h.retrievals++
            PassageRetrieval(0, PassageCoverage(1, 1), listOf(retrieved(1, passage)), null, 1, 1,
                passage.encodeToByteArray().size)
        }
        return h to assistant
    }

    @Test fun refreshFailureAfterCommitFinishesTheSavedAttemptAsInterruptedAndRetryWorks() = runTest {
        for (scope in RequestScope.entries) {
            val (h, assistant) = harness()
            try {
                h.controller.restore(); h.controller.editDraft(question)
                h.failReadsAfterCommit = 1
                assistant.submit(requestScope = scope); runCurrent()

                // One user message and one attempt, finished INTERRUPTED; no retrieval or runtime call for it.
                val user = h.sql.messages().single()
                assertEquals(MessageRole.USER to scope, user.role to user.scope)
                val lost = h.sql.attempts().single()
                assertEquals(user.id, lost.userId)
                assertEquals(TurnStatus.INTERRUPTED, lost.status)
                assertEquals(0, h.retrievals)
                assertTrue(h.runtime.requests.isEmpty())
                assertFalse(assistant.state.value.active)
                // The second refresh succeeded: the UI shows the stored state, with Retry and no stale draft.
                val state = h.controller.state.value
                assertEquals(listOf(user), state.messages)
                assertEquals(listOf(lost), state.attempts)
                assertEquals("", state.draft)
                assertNull(state.error)

                // A later restore leaves the attempt unchanged.
                h.controller.restore()
                assertEquals(listOf(lost), h.sql.attempts())

                // Retry without restart: same user, same scope, a new attempt, no duplicate message.
                h.controller.editDraft("Synthetic unsent next draft")
                assistant.submit(retryUserId = user.id); runCurrent()
                assertEquals(1, h.retrievals)
                val request = h.runtime.requests.single()
                assertTrue(request.messages.single().text.startsWith(GroundedPrompt.instruction(scope) + "\n"))
                assertEquals(listOf(user), h.sql.messages())
                assertEquals("Synthetic unsent next draft", h.sql.draft())
                val retry = h.sql.attempts().last()
                assertNotEquals(lost.id, retry.id)
                assertEquals(request.id to TurnStatus.PENDING, retry.id to retry.status)
                h.runtime.finish("Synthetic reply"); runCurrent()
                assertEquals(listOf(TurnStatus.INTERRUPTED, TurnStatus.COMPLETED), h.sql.attempts().map { it.status })
                assertEquals(listOf(MessageRole.USER to scope, MessageRole.ASSISTANT to RequestScope.ORDINARY),
                    h.sql.messages().map { it.role to it.scope })
            } finally {
                assistant.invalidate(); h.driver.close()
            }
        }
    }

    @Test fun refreshFailingTwiceKeepsTheErrorButTheSavedAttemptIsTerminal() = runTest {
        for (scope in RequestScope.entries) {
            val (h, assistant) = harness()
            try {
                h.controller.restore(); h.controller.editDraft(question)
                h.failReadsAfterCommit = 2
                assistant.submit(requestScope = scope); runCurrent()

                assertEquals(ConversationError.MESSAGE_SAVE, h.controller.state.value.error)
                assertEquals(1, h.sql.messages().size)
                assertEquals(TurnStatus.INTERRUPTED, h.sql.attempts().single().status)
                assertEquals(0, h.retrievals)
                assertTrue(h.runtime.requests.isEmpty())
                // The next send refreshes through the epoch guard: no duplicate, and Retry is shown.
                assistant.submit(requestScope = scope); runCurrent()
                assertEquals(1, h.controller.state.value.messages.size)
                assertEquals(TurnStatus.INTERRUPTED, h.controller.state.value.attempts.single().status)
                assertEquals(1, h.sql.messages().size)
                assertEquals(0, h.retrievals)
                assertTrue(h.runtime.requests.isEmpty())
            } finally {
                assistant.invalidate(); h.driver.close()
            }
        }
    }

    /** S4-F9: the attempt could not be finished, so it stays PENDING with no Retry; the error must stay shown. */
    @Test fun finishFailureAfterARecoveredRefreshKeepsTheError() = runTest {
        for (scope in RequestScope.entries) {
            val (h, assistant) = harness()
            try {
                h.controller.restore(); h.controller.editDraft(question)
                h.failReadsAfterCommit = 1
                h.failFinish = true
                assistant.submit(requestScope = scope); runCurrent()

                val state = h.controller.state.value
                assertEquals(ConversationError.MESSAGE_SAVE, state.error)
                // The refresh worked: the UI shows the stored message and its still-PENDING attempt.
                assertEquals(h.sql.messages(), state.messages)
                assertEquals(1, state.messages.size)
                assertEquals("", state.draft)
                assertEquals(listOf(TurnStatus.PENDING), state.attempts.map { it.status })
                assertEquals(0, h.retrievals)
                assertTrue(h.runtime.requests.isEmpty())
                // Restore reconciles it, as for any abandoned attempt.
                h.failFinish = false
                h.controller.restore()
                assertEquals(listOf(TurnStatus.INTERRUPTED), h.controller.state.value.attempts.map { it.status })
            } finally {
                assistant.invalidate(); h.driver.close()
            }
        }
    }

    /** S4-F10: only this turn's attempt is finished; another PENDING attempt is not reconciled. */
    @Test fun finishingTheSavedAttemptLeavesAnUnrelatedPendingAttemptUntouched() = runTest {
        for (scope in RequestScope.entries) {
            val (h, assistant) = harness()
            try {
                h.controller.restore()
                val other = h.sql.submitUserMessage("Synthetic earlier question", 1, RequestScope.ORDINARY, "synthetic-other")
                h.controller.refresh()
                h.controller.editDraft(question)
                h.failReadsAfterCommit = 1
                assistant.submit(requestScope = scope); runCurrent()

                val attempts = h.sql.attempts().associate { it.userId to (it.id to it.status) }
                assertEquals("synthetic-other" to TurnStatus.PENDING, attempts[other.id])
                val user = h.sql.messages().last()
                assertNotEquals(other.id, user.id)
                assertEquals(TurnStatus.INTERRUPTED, attempts[user.id]?.second)
                assertEquals(2, attempts.size)
                assertEquals(0, h.retrievals)
                assertTrue(h.runtime.requests.isEmpty())
            } finally {
                assistant.invalidate(); h.driver.close()
            }
        }
    }

    @Test fun failedSubmitTransactionLeavesNothingDurableAndKeepsTheDraft() = runTest {
        for (scope in RequestScope.entries) {
            val (h, assistant) = harness()
            try {
                h.controller.restore(); h.controller.editDraft(question)
                h.driver.execute(null, "CREATE TRIGGER synthetic_attempt_failure BEFORE INSERT ON assistant_attempt " +
                    "BEGIN SELECT RAISE(ABORT, 'synthetic attempt failure'); END", 0)
                assistant.submit(requestScope = scope); runCurrent()

                assertTrue(h.sql.messages().isEmpty())
                assertTrue(h.sql.attempts().isEmpty())
                assertEquals(0L, h.driver.count("message_scope"))
                assertEquals(question, h.sql.draft())
                val state = h.controller.state.value
                assertEquals(question, state.draft)
                assertTrue(state.messages.isEmpty())
                assertEquals(ConversationError.MESSAGE_SAVE, state.error)
                assertEquals(0, h.retrievals)
                assertTrue(h.runtime.requests.isEmpty())
            } finally {
                assistant.invalidate(); h.driver.close()
            }
        }
    }
}
