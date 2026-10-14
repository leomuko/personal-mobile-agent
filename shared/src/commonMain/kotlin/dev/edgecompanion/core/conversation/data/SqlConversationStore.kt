package dev.edgecompanion.core.conversation.data

import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.attachments.model.AttachmentStatus

import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.ModelError

import dev.edgecompanion.db.CompanionDatabase

class SqlConversationStore(private val db: CompanionDatabase) : ConversationStore {
    override fun attachments() = db.attachmentQueries.allAttachments().executeAsList().map {
        Attachment(it.id, it.name, it.mime_type, it.size, AttachmentStatus.valueOf(it.status), it.message_id)
    }
    override fun epoch() = db.attachmentQueries.readEpoch().executeAsOneOrNull() ?: 0L
    override fun reserve(item: Attachment) { db.attachmentQueries.insertAttachment(item.id, item.name, item.mimeType) }
    override fun complete(id: String, size: Long) { db.attachmentQueries.completeAttachment(size, id) }
    override fun fail(id: String) { db.attachmentQueries.failAttachment(id) }
    override fun remove(id: String) { db.attachmentQueries.removeDraftAttachment(id) }
    override fun interruptImports() { db.attachmentQueries.interruptImports() }
    override fun messages() = db.conversationQueries.allMessages().executeAsList().map {
        Message(it.id, it.text, it.created_at, MessageRole.valueOf(it.role), it.turn_id ?: it.id)
    }
    override fun draft() = db.conversationQueries.readDraft().executeAsOneOrNull().orEmpty()
    override fun saveDraft(text: String) { db.conversationQueries.writeDraft(text) }
    override fun append(text: String, timestamp: Long) = db.transaction {
        db.conversationQueries.insertMessage(text, timestamp)
        val messageId = db.attachmentQueries.lastMessageId().executeAsOne()
        db.conversationQueries.linkUserTurn(messageId)
        db.conversationQueries.indexMessage(text)
        db.attachmentQueries.attachToMessage(messageId)
        saveDraft("")
        db.attachmentQueries.writeEpoch(epoch() + 1)
    }
    override fun submitUserMessage(text: String, timestamp: Long): Message = db.transactionWithResult {
        append(text, timestamp)
        messages().last()
    }
    override fun attempts() = db.conversationQueries.attempts().executeAsList().map {
        AssistantAttempt(it.id, it.user_id, it.epoch, TurnStatus.valueOf(it.status), it.error?.let(ModelError::valueOf))
    }
    override fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long): Boolean = db.transactionWithResult {
        if (epoch() != expectedEpoch || messages().none { it.id == userId && it.role == MessageRole.USER }) return@transactionWithResult false
        val existing = attempts().firstOrNull { it.id == id }
        if (existing != null) return@transactionWithResult existing.status == TurnStatus.PENDING
        if (attempts().any { it.userId == userId && it.status == TurnStatus.COMPLETED }) return@transactionWithResult false
        db.conversationQueries.startAttempt(id, userId, expectedEpoch)
        true
    }
    override fun completeAssistantTurn(id: String, text: String, timestamp: Long): Boolean = db.transactionWithResult {
        val attempt = attempts().firstOrNull { it.id == id } ?: return@transactionWithResult false
        if (attempt.status == TurnStatus.COMPLETED) return@transactionWithResult true
        if (attempt.status != TurnStatus.PENDING || attempt.epoch != epoch()) return@transactionWithResult false
        db.conversationQueries.insertAssistant(text, timestamp, attempt.userId, id)
        db.conversationQueries.indexMessage(text)
        db.conversationQueries.finishAttempt(TurnStatus.COMPLETED.name, null, id)
        true
    }
    override fun finishAssistantTurn(id: String, status: TurnStatus, error: ModelError?) {
        require(status != TurnStatus.COMPLETED && status != TurnStatus.PENDING)
        db.conversationQueries.finishAttempt(status.name, error?.name, id)
    }
    override fun reconcileAssistantTurns() { db.conversationQueries.reconcileAttempts() }
    override fun clear() = db.transaction {
        db.conversationQueries.clearAttempts()
        db.attachmentQueries.clearAttachments()
        db.attachmentQueries.writeEpoch(epoch() + 1)
        db.conversationQueries.clearSearch()
        db.conversationQueries.clearMessages()
        saveDraft("")
    }
}
