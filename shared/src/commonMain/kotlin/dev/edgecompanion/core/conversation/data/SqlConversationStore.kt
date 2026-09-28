package dev.edgecompanion.core.conversation.data

import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.attachments.model.AttachmentStatus

import dev.edgecompanion.core.conversation.model.Message

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
        Message(it.id, it.text, it.created_at)
    }
    override fun draft() = db.conversationQueries.readDraft().executeAsOneOrNull().orEmpty()
    override fun saveDraft(text: String) { db.conversationQueries.writeDraft(text) }
    override fun append(text: String, timestamp: Long) = db.transaction {
        db.conversationQueries.insertMessage(text, timestamp)
        val messageId = db.attachmentQueries.lastMessageId().executeAsOne()
        db.conversationQueries.indexMessage(text)
        db.attachmentQueries.attachToMessage(messageId)
        saveDraft("")
        db.attachmentQueries.writeEpoch(epoch() + 1)
    }
    override fun clear() = db.transaction {
        db.attachmentQueries.clearAttachments()
        db.attachmentQueries.writeEpoch(epoch() + 1)
        db.conversationQueries.clearSearch()
        db.conversationQueries.clearMessages()
        saveDraft("")
    }
}
