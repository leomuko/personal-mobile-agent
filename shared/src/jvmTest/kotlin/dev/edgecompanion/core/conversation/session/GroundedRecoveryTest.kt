package dev.edgecompanion.core.conversation.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.ChatContextProfile
import dev.edgecompanion.core.sources.data.SourcesRepository
import dev.edgecompanion.core.sources.model.SourceKind
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/**
 * S4-F5 / S4-F4: process loss on a file-backed JDBC database (not SQLCipher). "Process A" is abandoned with its
 * coroutines still suspended and its connection closed, so no cancellation cleanup writes anything; "process B"
 * opens a new connection and restores. Synthetic text only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroundedRecoveryTest {
    private val profile = ChatContextProfile.EXTENDED_4096
    private val garden = "The synthetic greenhouse vent opens at twenty degrees on weekdays."
    private val question = "When does the greenhouse vent open?"

    private inline fun withDatabaseFile(block: (String) -> Unit) {
        val directory = Files.createTempDirectory("grounded-recovery-").toFile()
        try { block("jdbc:sqlite:${File(directory, "synthetic.db").absolutePath}") }
        finally { directory.deleteRecursively() }
    }

    /** Creates the schema, a matching source and a READY draft attachment through process A's connection. */
    private fun seed(driver: JdbcSqliteDriver): SqlConversationStore {
        CompanionDatabase.Schema.create(driver)
        val db = CompanionDatabase(driver)
        SourcesRepository(db, unlimited) { 1 }.importText("Garden", garden, SourceKind.PASTED_TEXT)
        return SqlConversationStore(db).apply {
            reserve(Attachment("synthetic-attachment", "synthetic.txt", "text/plain"))
            complete("synthetic-attachment", 12)
        }
    }

    /** Process B: a new connection and session; restore reconciles. The caller shuts the session down. */
    private fun TestScope.reopen(driver: JdbcSqliteDriver, runtime: RecordingRuntime): Pair<ConversationSession, SqlConversationStore> {
        val db = CompanionDatabase(driver)
        val store = SqlConversationStore(db)
        val repo = SourcesRepository(db, unlimited) { 1 }
        val session = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(store) { 1 } }, {},
            chatRuntime = runtime, sourcesProvider = { repo }, chatProfile = profile)
        session.start(); runCurrent()
        return session to store
    }

    private fun TestScope.assertRestoredTurnRetries(session: ConversationSession, store: SqlConversationStore,
        runtime: RecordingRuntime, scope: RequestScope, lost: AssistantAttempt) {
        val state = session.state.value
        val user = state.messages.single()
        assertEquals(question to scope, user.text to user.scope)
        assertEquals("", state.draft)
        assertEquals(listOf("synthetic-attachment" to user.id), state.attachments.map { it.id to it.messageId })
        // Exactly one attempt for the turn, INTERRUPTED, with whatever grounding it had kept.
        assertEquals(listOf(lost.copy(status = TurnStatus.INTERRUPTED)), state.attempts)
        session.retryAssistant(user.id); runCurrent()
        val request = runtime.requests.single()
        val text = request.messages.single().text
        assertTrue(text.startsWith(GroundedPrompt.instruction(scope) + "\n"))
        assertTrue(text.contains(garden))
        val retry = store.attempts().last()
        assertNotEquals(lost.id, retry.id)
        assertEquals(request.id, retry.id)
        assertEquals(TurnStatus.PENDING, retry.status)
        assertEquals(1, retry.consulted.size)
        runtime.finish("Synthetic reply"); runCurrent()
        assertEquals(listOf(TurnStatus.INTERRUPTED, TurnStatus.COMPLETED), store.attempts().map { it.status })
        assertEquals(listOf(MessageRole.USER to scope, MessageRole.ASSISTANT to RequestScope.ORDINARY),
            store.messages().map { it.role to it.scope })
    }

    @Test fun processLossDuringRetrievalRestoresOneInterruptedAttemptThatRetriesWithItsScope() = runTest {
        for (scope in RequestScope.entries) withDatabaseFile { url ->
            // Process A: retrieval never returns, as if the process died inside it.
            val driverA = JdbcSqliteDriver(url)
            val storeA = seed(driverA)
            val controllerA = ConversationController(storeA) { 1 }
            var retrieving = false
            val laneA = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val assistantA = ConversationAssistant(laneA, { controllerA }, RecordingRuntime(), profile) { _, _, _ ->
                retrieving = true; awaitCancellation()
            }
            controllerA.restore(); controllerA.editDraft(question)
            assistantA.submit(requestScope = scope); runCurrent()
            assertTrue(retrieving)
            val lost = storeA.attempts().single()
            assertEquals(TurnStatus.PENDING, lost.status)
            assertNull(lost.retrieval)
            assertTrue(lost.consulted.isEmpty())
            // Process loss: the connection goes away and no coroutine cleanup ever runs for process A.
            driverA.close()

            val driverB = JdbcSqliteDriver(url)
            try {
                val runtime = RecordingRuntime()
                val (session, store) = reopen(driverB, runtime)
                try { assertRestoredTurnRetries(session, store, runtime, scope, lost) }
                finally { session.shutdown() }
            } finally { driverB.close() }
        }
    }

    /** Unchanged behaviour: loss after the attempt began restores INTERRUPTED with its grounding and references (S4-F4). */
    @Test fun processLossDuringGenerationRestoresInterruptedWithReferencesKept() = runTest {
        for (scope in RequestScope.entries) withDatabaseFile { url ->
            val driverA = JdbcSqliteDriver(url)
            val storeA = seed(driverA)
            val runtimeA = RecordingRuntime()
            val sessionA = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(storeA) { 1 } }, {},
                chatRuntime = runtimeA, sourcesProvider = { SourcesRepository(CompanionDatabase(driverA), unlimited) { 1 } },
                chatProfile = profile)
            sessionA.start(); runCurrent()
            sessionA.editDraft(question); runCurrent(); sessionA.submit(scope); runCurrent()
            val original = runtimeA.requests.single()
            val lost = storeA.attempts().single()
            assertEquals(TurnStatus.PENDING, lost.status)
            assertEquals(1, lost.consulted.size)
            assertEquals(RetrievalSummary(RetrievalStatus.COMPLETE, null, 1, 1), lost.retrieval)
            // Process loss mid-generation: no terminal event, no cleanup.
            driverA.close()

            val driverB = JdbcSqliteDriver(url)
            try {
                val runtime = RecordingRuntime()
                val (session, store) = reopen(driverB, runtime)
                try {
                    assertRestoredTurnRetries(session, store, runtime, scope, lost)
                    // Deterministic retry: same library and history give the same request bytes.
                    assertEquals(original.messages, runtime.requests.single().messages)
                    assertEquals(lost.consulted, store.attempts().last().consulted)
                } finally { session.shutdown() }
            } finally { driverB.close() }
        }
    }
}
