package dev.edgecompanion.ui.conversation.viewmodel

import androidx.lifecycle.viewModelScope
import dev.edgecompanion.core.conversation.data.ConversationStore
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.conversation.session.ConversationController
import dev.edgecompanion.core.conversation.session.ConversationSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    @Test fun submitUsesTheComposerScopeForTheStoredUserTurn() = withSession { model, store ->
        model.setSourcesOnly(true)
        runCurrent()
        assertEquals(RequestScope.SOURCES, model.state.value.draftScope)
        model.editDraft("Synthetic scoped question")
        model.submit()
        runCurrent()
        assertEquals(RequestScope.SOURCES, store.messages().single().scope)
        assertEquals(SourcesNoAnswer.NO_MATCH, store.attempts().single().sourcesResult)

        model.setSourcesOnly(false)
        model.editDraft("Synthetic ordinary question")
        model.submit()
        runCurrent()
        assertEquals(listOf(RequestScope.SOURCES, RequestScope.ORDINARY), store.messages().map { it.scope })
    }

    @Test fun togglePersistsBothChoicesAndNewSessionsRestoreThem() = runTest {
        val store = RecordingStore()
        withSession(store) { model, _ ->
            model.setSourcesOnly(true)
            runCurrent()
            assertEquals(RequestScope.SOURCES, store.draftScope())
        }
        withSession(store) { model, _ ->
            assertEquals(RequestScope.SOURCES, model.state.value.draftScope)
            model.setSourcesOnly(false)
            runCurrent()
            assertEquals(RequestScope.ORDINARY, store.draftScope())
        }
        withSession(store) { model, _ ->
            assertEquals(RequestScope.ORDINARY, model.state.value.draftScope)
        }
    }

    private fun withSession(block: suspend TestScope.(ConversationViewModel, RecordingStore) -> Unit) =
        runTest { withSession(RecordingStore(), block) }

    private suspend fun TestScope.withSession(store: RecordingStore,
        block: suspend TestScope.(ConversationViewModel, RecordingStore) -> Unit) {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val session = ConversationSession(dispatcher, { ConversationController(store) { 1 } }, {})
        val model = ConversationViewModel(session)
        try {
            session.start()
            runCurrent()
            assertTrue(model.state.value.ready)
            block(model, store)
        } finally {
            model.viewModelScope.cancel()
            try { session.shutdown() } finally { Dispatchers.resetMain() }
        }
    }

    /** Records store calls while the ViewModel, session, controller and assistant use their production paths. */
    private class RecordingStore : ConversationStore {
        private var text = ""
        private var scope = RequestScope.ORDINARY
        private val messages = mutableListOf<Message>()
        private var attempt: AssistantAttempt? = null

        override fun draft() = text
        override fun saveDraft(text: String) { this.text = text }
        override fun draftScope() = scope
        override fun saveDraftScope(scope: RequestScope) { this.scope = scope }
        override fun messages() = messages.toList()
        override fun append(text: String, timestamp: Long) { submitUserMessage(text, timestamp, RequestScope.ORDINARY) }
        override fun submitUserMessage(text: String, timestamp: Long, scope: RequestScope) =
            Message(messages.size.toLong() + 1, text, timestamp, scope = scope).also {
                messages += it
                this.text = ""
            }
        override fun attempts() = listOfNotNull(attempt)
        override fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long): Boolean {
            attempt = AssistantAttempt(id, userId, expectedEpoch, TurnStatus.PENDING)
            return true
        }
        override fun recordSourcesResult(id: String, grounding: TurnGrounding): Boolean {
            val pending = attempt?.takeIf { it.id == id && it.status == TurnStatus.PENDING } ?: return false
            attempt = pending.copy(status = TurnStatus.COMPLETED, sourcesResult = grounding.sourcesResult)
            return true
        }
        override fun clear() { messages.clear(); text = ""; attempt = null }
    }
}
