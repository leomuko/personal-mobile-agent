package dev.edgecompanion.core.conversation.session

import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.data.ConversationStore
import dev.edgecompanion.core.inference.BenchmarkSample

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class ConversationControllerTest {
    private class MemoryStore : ConversationStore {
        val rows = mutableListOf<Message>()
        var text = ""
        var fail = false
        override fun messages(): List<Message> { if (fail) error("disk full"); return rows.toList() }
        override fun draft() = text
        override fun saveDraft(text: String) { if (fail) error("disk full"); this.text = text }
        override fun append(text: String, timestamp: Long) {
            if (fail) error("disk full")
            rows.add(Message(rows.size.toLong() + 1, text, timestamp)); this.text = ""
        }
        override fun clear() { if (fail) error("disk full"); rows.clear(); text = "" }
    }
    @Test fun duplicateSubmissionDoesNotCreateDuplicateMessages() = runTest {
        val store = MemoryStore(); val session = ConversationController(store) { 42 }
        session.restore(); session.editDraft("  Remember the contract  "); session.submit(); session.submit()
        assertEquals(listOf(Message(1, "Remember the contract", 42)), session.state.value.messages)
        assertEquals("", store.draft())
    }
    @Test fun anotherHostRestoresTheSamePersistedDraftAndMessages() = runTest {
        val store = MemoryStore(); val first = ConversationController(store) { 42 }
        first.restore(); first.editDraft("Saved"); first.submit(); first.editDraft("Unfinished")
        val second = ConversationController(store) { 50 }; second.restore()
        assertEquals(first.state.value, second.state.value)
    }
    @Test fun storageFailureRetainsDraftWithoutClaimingSuccess() = runTest {
        val store = MemoryStore(); val session = ConversationController(store) { 42 }
        session.restore(); session.editDraft("Keep this"); store.fail = true; session.submit()
        assertEquals("Keep this", session.state.value.draft)
        assertTrue(session.state.value.messages.isEmpty())
        assertEquals(ConversationError.MESSAGE_SAVE, session.state.value.error)
    }
    @Test fun errorsAreTypedAndSuccessfulRetryClearsThem() = runTest {
        val store = MemoryStore().apply { fail = true }
        val session = ConversationController(store) { 42 }
        session.restore(); assertEquals(ConversationError.STORAGE_OPEN, session.state.value.error)
        assertFalse(session.state.value.ready)
        store.fail = false; session.restore(); session.editDraft("preserve")
        store.fail = true; session.editDraft("unsaved")
        assertEquals(ConversationError.DRAFT_SAVE, session.state.value.error)
        assertEquals("preserve", session.state.value.draft)
        session.clear(); assertEquals(ConversationError.CLEAR, session.state.value.error)
        assertEquals("preserve", store.text)
        store.fail = false; session.clear(); assertNull(session.state.value.error)
    }
    @Test fun cancellationIsNotReportedAsAStorageError() = runTest {
        val base = MemoryStore()
        val store = object : ConversationStore by base {
            override fun messages(): List<Message> = throw CancellationException("cancelled")
        }
        val session = ConversationController(store) { 42 }
        assertFailsWith<CancellationException> { session.restore() }
        assertNull(session.state.value.error)
    }
    @Test fun blankOrUninitializedRequestsDoNotWrite() = runTest {
        val store = MemoryStore(); val session = ConversationController(store) { 42 }
        session.editDraft("Before initialization"); session.submit(); assertEquals("", store.text)
        session.restore(); session.editDraft("  "); session.submit(); assertTrue(store.rows.isEmpty())
    }
    @Test fun clearRemovesDraftAndConversation() = runTest {
        val store = MemoryStore(); val session = ConversationController(store) { 42 }
        session.restore(); session.editDraft("Message"); session.submit(); session.editDraft("Draft"); session.clear()
        assertTrue(store.rows.isEmpty()); assertEquals("", store.text)
        assertEquals(ConversationState(ready = true), session.state.value)
    }
    @Test fun boundedInputDoesNotExceedContextInputLimit() = runTest {
        val store = MemoryStore(); val session = ConversationController(store) { 42 }
        session.restore(); session.editDraft("x".repeat(16_001)); assertEquals(16_000, store.text.length)
    }
    @Test fun benchmarkSeparatesPrefillFromDecodeAndRejectsInvalidSamples() {
        assertEquals(10.0, BenchmarkSample(false, 200, 1200, 11).tokensPerSecond)
        assertNull(BenchmarkSample(true, 100, 100, 1).tokensPerSecond)
        assertFailsWith<IllegalArgumentException> { BenchmarkSample(false, 200, 100, 10) }
    }
}
