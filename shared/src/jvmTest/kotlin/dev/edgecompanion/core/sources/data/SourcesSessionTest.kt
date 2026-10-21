package dev.edgecompanion.core.sources.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.model.TurnStatus
import dev.edgecompanion.core.conversation.session.*
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesSessionTest {
    private class Runtime : ChatRuntime {
        override val enabled = true
        val requests = mutableListOf<ChatRequest>()
        var events = Channel<ChatEvent>(Channel.UNLIMITED)
        var gate: CompletableDeferred<Unit>? = null
        var failCleanup = false
        override fun generateChat(request: ChatRequest): Flow<ChatEvent> {
            requests += request; events = Channel(Channel.UNLIMITED); return events.receiveAsFlow()
        }
        fun finish(text: String) {
            val id = requests.last().id
            events.trySend(ChatEvent.Text(id, text)); events.trySend(ChatEvent.Terminal(id, ChatOutcome.COMPLETED)); events.close()
        }
        override suspend fun cancelChat(id: String, interrupted: Boolean) {
            gate?.await()
            if (failCleanup) throw ModelException(ModelError.RELEASE_FAILED)
            events.close()
        }
    }
    @Test fun deleteRevokesBeforeCleanupBlocksNewWorkAndFencesRequestSnapshotsAndRetry() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CompanionDatabase.Schema.create(driver)
        val db = CompanionDatabase(driver)
        val store = SqlConversationStore(db)
        val repo = SourcesRepository(db, LibraryCapacity { LibraryFootprint(0, Long.MAX_VALUE) }) { 1 }
        val runtime = Runtime()
        val session = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(store) { 1 } },
            {}, chatRuntime = runtime, sourcesProvider = { repo })
        try {
            session.start(); runCurrent()
            val source = session.addTextSource("Old", "private old fact")
            val keep = session.addTextSource("Keep", "independent source")
            session.editDraft("private old fact"); runCurrent(); session.submit(); runCurrent()
            runtime.finish("old paraphrase"); runCurrent()
            session.editDraft("remember more"); runCurrent(); session.submit(); runCurrent()
            runtime.gate = CompletableDeferred()
            val deletion = async { session.deleteSource(source.id) }; runCurrent()
            assertEquals(1, store.contextEpoch())
            assertEquals(3, store.messageFence())
            session.editDraft("new question"); runCurrent(); session.submit(); runCurrent()
            assertEquals(2, runtime.requests.size)
            runtime.finish("late forgotten text"); runCurrent()
            assertTrue(store.messages().none { it.text == "late forgotten text" })
            runtime.gate!!.complete(Unit); deletion.await(); runCurrent()
            assertEquals("new question", session.state.value.draft)
            assertEquals(TurnStatus.INTERRUPTED, store.attempts().last().status)
            session.retryAssistant(1); session.retryAssistant(3); runCurrent()
            assertEquals(2, runtime.requests.size)
            session.submit(); runCurrent()
            assertEquals(3, runtime.requests.size)
            assertEquals(1, runtime.requests.last().messages.size)
            assertEquals("new question", runtime.requests.last().messages.single().text)
            assertEquals(keep, session.listSources().items.single())
            assertTrue(session.state.value.messages.any { it.text == "old paraphrase" })
            runtime.finish("fresh reply"); runCurrent()
        } finally { runtime.gate?.complete(Unit); session.shutdown(); driver.close() }
    }
    @Test fun callerCancellationCannotLeaveUtf8WriteReadingAnAlreadyClearedBuffer() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CompanionDatabase.Schema.create(driver)
        val db = CompanionDatabase(driver)
        val repo = SourcesRepository(db, LibraryCapacity { LibraryFootprint(0, Long.MAX_VALUE) }) { 1 }
        val session = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(SqlConversationStore(db)) { 1 } },
            {}, sourcesProvider = { repo })
        try {
            session.start(); runCurrent()
            val bytes = "text".encodeToByteArray()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try { session.addUtf8Source("Cancel", bytes) } finally { bytes.fill(0) }
            }
            job.cancelAndJoin(); runCurrent()
            assertTrue(session.listSources().items.isEmpty())
        } finally { session.shutdown(); driver.close() }
    }
    @Test fun uncertainContextCleanupQuarantinesFurtherRequestsButKeepsDurableDeletion() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        CompanionDatabase.Schema.create(driver)
        val db = CompanionDatabase(driver)
        val store = SqlConversationStore(db)
        val repo = SourcesRepository(db, LibraryCapacity { LibraryFootprint(0, Long.MAX_VALUE) }) { 1 }
        val runtime = Runtime()
        val session = ConversationSession(StandardTestDispatcher(testScheduler), { ConversationController(store) { 1 } },
            {}, chatRuntime = runtime, sourcesProvider = { repo })
        try {
            session.start(); runCurrent()
            val source = session.addTextSource("Delete", "text")
            session.editDraft("question"); runCurrent(); session.submit(); runCurrent()
            runtime.failCleanup = true
            val failure = assertFailsWith<ModelException> { session.deleteSource(source.id) }
            assertEquals(ModelError.RESTART_REQUIRED, failure.error)
            session.editDraft("unsent"); runCurrent(); session.submit(); runCurrent()
            assertEquals(1, runtime.requests.size)
            assertEquals(ModelError.RESTART_REQUIRED, session.state.value.assistant.error)
            assertEquals("unsent", session.state.value.draft)
            assertTrue(session.listSources().items.isEmpty())
            assertEquals(1, store.contextEpoch())
        } finally { session.shutdown(); driver.close() }
    }
}
