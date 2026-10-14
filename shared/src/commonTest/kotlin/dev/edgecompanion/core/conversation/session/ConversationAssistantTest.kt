package dev.edgecompanion.core.conversation.session

import dev.edgecompanion.core.conversation.data.ConversationStore
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationAssistantTest {
    private class Store : ConversationStore {
        val rows = mutableListOf<Message>()
        val turns = mutableListOf<AssistantAttempt>()
        var draftText = ""
        var generationEpoch = 0L
        var failUser = false
        var failAssistant = false
        override fun messages() = rows.toList()
        override fun draft() = draftText
        override fun epoch() = generationEpoch
        override fun saveDraft(text: String) { draftText = text }
        override fun append(text: String, timestamp: Long) {
            if (failUser) error("storage")
            rows += Message(rows.size.toLong() + 1, text, timestamp)
            draftText = ""; generationEpoch++
        }
        override fun clear() { rows.clear(); turns.clear(); draftText = ""; generationEpoch++ }
        override fun attempts() = turns.toList()
        override fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long): Boolean {
            if (expectedEpoch != generationEpoch) return false
            turns += AssistantAttempt(id, userId, expectedEpoch, TurnStatus.PENDING)
            return true
        }
        override fun completeAssistantTurn(id: String, text: String, timestamp: Long): Boolean {
            if (failAssistant) error("storage")
            val turn = turns.firstOrNull { it.id == id && it.status == TurnStatus.PENDING } ?: return false
            rows += Message(rows.size.toLong() + 1, text, timestamp, MessageRole.ASSISTANT, turn.userId)
            turns[turns.indexOf(turn)] = turn.copy(status = TurnStatus.COMPLETED)
            return true
        }
        override fun finishAssistantTurn(id: String, status: TurnStatus, error: ModelError?) {
            val i = turns.indexOfFirst { it.id == id && it.status == TurnStatus.PENDING }
            if (i >= 0) turns[i] = turns[i].copy(status = status, error = error)
        }
        override fun reconcileAssistantTurns() {
            for (i in turns.indices) if (turns[i].status == TurnStatus.PENDING)
                turns[i] = turns[i].copy(status = TurnStatus.INTERRUPTED)
        }
    }
    private class Runtime : ChatRuntime {
        override val enabled = true
        val requests = mutableListOf<ChatRequest>()
        var events = Channel<ChatEvent>(Channel.UNLIMITED)
        var cancelGate: CompletableDeferred<Unit>? = null
        override fun generateChat(request: ChatRequest): Flow<ChatEvent> {
            requests += request
            events = Channel(Channel.UNLIMITED)
            return events.receiveAsFlow()
        }
        fun delta(text: String, id: String = requests.last().id) { events.trySend(ChatEvent.Text(id, text)) }
        fun finish(outcome: ChatOutcome = ChatOutcome.COMPLETED, error: ModelError? = null) {
            events.trySend(ChatEvent.Terminal(requests.last().id, outcome, error)); events.close()
        }
        override suspend fun cancelChat(id: String, interrupted: Boolean) {
            cancelGate?.await()
            if (requests.lastOrNull()?.id == id) finish(if (interrupted) ChatOutcome.INTERRUPTED else ChatOutcome.CANCELLED)
        }
    }
    private fun TestScope.session(store: Store, runtime: Runtime) = ConversationSession(StandardTestDispatcher(testScheduler),
        { ConversationController(store) { 42 } }, {}, chatRuntime = runtime).also { it.start(); runCurrent() }

    @Test fun streamingCompositionPreservesDraftAndDoubleSubmitIsRejected() = runTest {
        val store = Store(); val runtime = Runtime(); val session = session(store, runtime)
        try {
            session.editDraft("Hello"); runCurrent(); session.submit(); runCurrent()
            assertEquals(1, store.rows.size)
            runtime.delta("A local "); runCurrent()
            session.editDraft("Next draft"); runCurrent(); session.submit(); runCurrent()
            assertEquals(1, runtime.requests.size)
            assertEquals("Next draft", session.state.value.draft)
            assertEquals("A local ", session.state.value.assistant.text)
            runtime.delta("response"); runtime.finish(); runCurrent()
            assertEquals(MessageRole.ASSISTANT, store.rows.last().role)
            assertEquals("Next draft", session.state.value.draft)
            assertEquals("A local response", store.rows.last().text)
        } finally { session.shutdown() }
    }
    @Test fun cancelAndRetryReuseUserTurnAndIgnoreStaleCallbacks() = runTest {
        val store = Store(); val runtime = Runtime(); val session = session(store, runtime)
        try {
            session.editDraft("Hello"); runCurrent(); session.submit(); runCurrent()
            val old = runtime.requests.last().id
            runtime.delta("partial"); runCurrent(); session.cancelAssistant(); runCurrent()
            assertEquals(1, store.rows.size)
            assertEquals(TurnStatus.CANCELLED, store.turns.last().status)
            session.retryAssistant(1); runCurrent()
            runtime.delta("stale", old); runtime.delta("New answer"); runtime.finish(); runCurrent()
            assertEquals(2, store.rows.size)
            assertEquals(1, store.rows.count { it.role == MessageRole.USER })
            assertEquals("New answer", store.rows.last().text)
        } finally { session.shutdown() }
    }
    @Test fun clearInvalidatesQueuedCompletion() = runTest {
        val store = Store(); val runtime = Runtime(); val session = session(store, runtime)
        try {
            session.editDraft("Hello"); runCurrent(); session.submit(); runCurrent()
            runtime.delta("Late"); session.clear(); runtime.finish(); runCurrent()
            assertTrue(store.rows.isEmpty())
            assertFalse(session.state.value.assistant.active)
        } finally { session.shutdown() }
    }
    @Test fun clearBlocksSubmissionUntilCancellationAndDeletionFinish() = runTest {
        val store = Store(); val runtime = Runtime(); val session = session(store, runtime)
        try {
            session.editDraft("Hello"); runCurrent(); session.submit(); runCurrent()
            runtime.cancelGate = CompletableDeferred()
            session.clear(); runCurrent()
            session.editDraft("During clear"); runCurrent(); session.submit(); runCurrent()
            assertEquals(1, runtime.requests.size)
            runtime.cancelGate!!.complete(Unit); runCurrent()
            assertTrue(store.rows.isEmpty())
            assertEquals("", store.draftText)
        } finally { runtime.cancelGate?.complete(Unit); session.shutdown() }
    }
    @Test fun storageAndBudgetFailuresDoNotProduceFakeCompletedMessages() = runTest {
        val store = Store(); val runtime = Runtime(); val session = session(store, runtime)
        try {
            session.editDraft("x".repeat(500)); runCurrent(); session.submit(); runCurrent()
            assertTrue(runtime.requests.isEmpty()); assertTrue(store.rows.isEmpty())
            assertEquals(ModelError.CONTEXT_LIMIT, session.state.value.assistant.error)
            session.editDraft("Hello"); runCurrent(); store.failUser = true; session.submit(); runCurrent()
            assertTrue(runtime.requests.isEmpty())
            store.failUser = false; session.submit(); runCurrent(); store.failAssistant = true
            runtime.delta("Unsaved"); runtime.finish(); runCurrent()
            assertEquals(1, store.rows.size)
            assertTrue(session.state.value.assistant.saveFailed)
            assertEquals(TurnStatus.FAILED, store.turns.last().status)
        } finally { session.shutdown() }
    }
    @Test fun runtimeErrorIsVisibleAndReopenReconcilesPendingStatus() = runTest {
        val store = Store(); val runtime = Runtime(); var session = session(store, runtime)
        try {
            session.editDraft("Hello"); runCurrent(); session.submit(); runCurrent()
            runtime.finish(ChatOutcome.REJECTED, ModelError.LOW_MEMORY); runCurrent()
            assertEquals(ModelError.LOW_MEMORY, session.state.value.assistant.error)
            assertEquals(1, store.rows.size)
            store.turns += AssistantAttempt("dead", 1, store.epoch(), TurnStatus.PENDING)
            session.shutdown(); session = session(store, runtime)
            assertEquals(TurnStatus.INTERRUPTED, store.turns.last().status)
            assertEquals("", session.state.value.assistant.text)
        } finally { session.shutdown() }
    }
}
