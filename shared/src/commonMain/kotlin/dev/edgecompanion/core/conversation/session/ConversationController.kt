package dev.edgecompanion.core.conversation.session

import dev.edgecompanion.core.attachments.model.*

import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.TurnStatus
import dev.edgecompanion.core.inference.ModelError
import dev.edgecompanion.core.conversation.data.ConversationStore

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Both Android hosts use this single session; Phase 0 does not fabricate model replies. */
class ConversationController(private val store: ConversationStore, private val now: () -> Long) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(ConversationState())
    val state = mutable.asStateFlow()
    fun attachmentError(limit: Boolean = false) {
        mutable.value = mutable.value.copy(error = if (limit) ConversationError.ATTACHMENT_LIMIT else ConversationError.ATTACHMENT_SAVE)
    }

    private fun snapshot() = ConversationState(store.messages(), store.draft(), ready = true,
        attachments = store.attachments(), draftEpoch = store.epoch(), attempts = store.attempts(),
        contextEpoch = store.contextEpoch(), messageFence = store.messageFence())

    suspend fun refresh() = mutex.withLock { mutable.value = snapshot() }

    suspend fun reserve(item: Attachment, epoch: Long): Boolean = mutex.withLock {
        try {
            if (!mutable.value.ready || epoch != store.epoch()) return@withLock false
            if (store.attachments().count { it.messageId == null } >= AttachmentLimits.COUNT) {
                mutable.value = mutable.value.copy(error = ConversationError.ATTACHMENT_LIMIT)
                return@withLock false
            }
            store.reserve(item.copy(name = item.name.take(180), mimeType = item.mimeType.take(120)))
            mutable.value = snapshot()
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = ConversationError.ATTACHMENT_SAVE)
            false
        }
    }

    suspend fun complete(id: String, size: Long): Boolean = mutex.withLock {
        val items = store.attachments()
        if (items.none { it.id == id && it.messageId == null }) return@withLock false
        if (size !in 1..AttachmentLimits.ITEM_BYTES ||
            items.filter { it.messageId == null }.sumOf { it.size } + size > AttachmentLimits.DRAFT_BYTES) {
            store.fail(id)
            mutable.value = snapshot().copy(error = ConversationError.ATTACHMENT_LIMIT)
            return@withLock false
        }
        store.complete(id, size)
        mutable.value = snapshot()
        true
    }

    suspend fun fail(id: String) = mutex.withLock {
        store.fail(id)
        mutable.value = snapshot().copy(error = ConversationError.ATTACHMENT_SAVE)
    }

    suspend fun remove(id: String) = mutex.withLock {
        store.remove(id)
        mutable.value = snapshot()
    }

    suspend fun restore() = mutex.withLock {
        try {
            store.interruptImports()
            store.reconcileAssistantTurns()
            mutable.value = snapshot()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = ConversationError.STORAGE_OPEN)
        }
    }

    suspend fun editDraft(text: String) = mutex.withLock {
        if (!mutable.value.ready) return@withLock
        try {
            store.saveDraft(text.take(16_000))
            mutable.value = mutable.value.copy(draft = text.take(16_000), error = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = ConversationError.DRAFT_SAVE)
        }
    }

    suspend fun submit(): Message? = mutex.withLock {
        val text = mutable.value.draft.trim()
        val attachments = mutable.value.attachments.filter { it.messageId == null }
        if (!mutable.value.ready || (text.isEmpty() && attachments.isEmpty()) ||
            attachments.any { it.status != AttachmentStatus.READY }) return@withLock null
        try {
            // A previous send may have committed before its UI refresh failed.
            if (store.epoch() != mutable.value.draftEpoch) {
                mutable.value = snapshot()
                return@withLock null
            }
            val submitted = store.submitUserMessage(text, now())
            mutable.value = snapshot()
            submitted
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = ConversationError.MESSAGE_SAVE)
            null
        }
    }

    suspend fun startAssistant(id: String, userId: Long, epoch: Long, contextEpoch: Long = state.value.contextEpoch): Boolean = mutex.withLock {
        if (store.contextEpoch() != contextEpoch) return@withLock false
        val started = store.startAssistantTurn(id, userId, epoch)
        mutable.value = snapshot()
        started
    }
    suspend fun completeAssistant(id: String, text: String): Boolean = mutex.withLock {
        val saved = store.completeAssistantTurn(id, text, now())
        mutable.value = snapshot()
        saved
    }
    suspend fun finishAssistant(id: String, status: TurnStatus, error: ModelError?) = mutex.withLock {
        store.finishAssistantTurn(id, status, error)
        mutable.value = snapshot()
    }

    suspend fun clear() = mutex.withLock {
        if (!mutable.value.ready) return@withLock
        try {
            store.clear()
            mutable.value = snapshot()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutable.value = mutable.value.copy(error = ConversationError.CLEAR)
        }
    }
}
