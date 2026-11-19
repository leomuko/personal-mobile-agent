package dev.edgecompanion.core.conversation.data

import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.attachments.model.AttachmentStatus

import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.ModelError
import dev.edgecompanion.core.sources.model.PassageStopReason

import dev.edgecompanion.db.CompanionDatabase

class SqlConversationStore(private val db: CompanionDatabase) : ConversationStore {
    override fun attachments() = db.attachmentQueries.allAttachments().executeAsList().map {
        Attachment(it.id, it.name, it.mime_type, it.size, AttachmentStatus.valueOf(it.status), it.message_id)
    }
    override fun epoch() = db.attachmentQueries.readEpoch().executeAsOneOrNull() ?: 0L
    override fun contextEpoch() = db.libraryQueries.readContext().executeAsOneOrNull()?.epoch ?: 0L
    override fun messageFence() = db.libraryQueries.readContext().executeAsOneOrNull()?.message_fence ?: 0L
    override fun reserve(item: Attachment) { db.attachmentQueries.insertAttachment(item.id, item.name, item.mimeType) }
    override fun complete(id: String, size: Long) { db.attachmentQueries.completeAttachment(size, id) }
    override fun fail(id: String) { db.attachmentQueries.failAttachment(id) }
    override fun remove(id: String) { db.attachmentQueries.removeDraftAttachment(id) }
    override fun interruptImports() { db.attachmentQueries.interruptImports() }
    override fun messages(): List<Message> = db.transactionWithResult {
        val scopes = db.conversationQueries.messageScopes().executeAsList().associate { it.message_id to it.scope }
        db.conversationQueries.allMessages().executeAsList().map {
            Message(it.id, it.text, it.created_at, MessageRole.valueOf(it.role), it.turn_id ?: it.id,
                scopes[it.id]?.let(RequestScope::valueOf) ?: RequestScope.ORDINARY)
        }
    }
    override fun draft() = db.conversationQueries.readDraft().executeAsOneOrNull().orEmpty()
    override fun saveDraft(text: String) { db.conversationQueries.writeDraft(text) }
    override fun append(text: String, timestamp: Long) = append(text, timestamp, RequestScope.ORDINARY)
    private fun append(text: String, timestamp: Long, scope: RequestScope) = db.transaction {
        db.conversationQueries.insertMessage(text, timestamp)
        val messageId = db.attachmentQueries.lastMessageId().executeAsOne()
        db.conversationQueries.linkUserTurn(messageId)
        db.conversationQueries.writeMessageScope(messageId, scope.name)
        db.conversationQueries.indexMessage(text)
        db.attachmentQueries.attachToMessage(messageId)
        saveDraft("")
        db.attachmentQueries.writeEpoch(epoch() + 1)
    }
    override fun submitUserMessage(text: String, timestamp: Long): Message =
        submitUserMessage(text, timestamp, RequestScope.ORDINARY)
    override fun submitUserMessage(text: String, timestamp: Long, scope: RequestScope): Message =
        submitUserMessage(text, timestamp, scope, null)
    override fun submitUserMessage(text: String, timestamp: Long, scope: RequestScope, attemptId: String?): Message =
        db.transactionWithResult {
            append(text, timestamp, scope)
            val message = messages().last()
            // Same transaction: the turn's recovery intent is durable before retrieval or generation begins.
            if (attemptId != null) db.conversationQueries.startAttempt(attemptId, message.id, epoch())
            message
        }
    override fun attempts(): List<AssistantAttempt> = db.transactionWithResult {
        val groundings = db.conversationQueries.groundings().executeAsList().associateBy { it.attempt_id }
        val consulted = db.conversationQueries.consultedPassages().executeAsList().groupBy({ it.attempt_id }) {
            ConsultedPassage(it.position.toInt(), it.source_id, it.revision_id, it.passage_id, it.start_utf16, it.end_utf16)
        }
        db.conversationQueries.attempts().executeAsList().map {
            val grounding = groundings[it.id]
            val retrieval = grounding?.retrieval_status?.let { status ->
                RetrievalSummary(RetrievalStatus.valueOf(status), grounding.retrieval_reason?.let(PassageStopReason::valueOf),
                    grounding.indexed_revisions ?: 0, grounding.total_revisions ?: 0)
            }
            AssistantAttempt(it.id, it.user_id, it.epoch, TurnStatus.valueOf(it.status), it.error?.let(ModelError::valueOf),
                retrieval, consulted[it.id].orEmpty(), grounding?.sources_result?.let(SourcesNoAnswer::valueOf))
        }
    }
    override fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long): Boolean =
        startAssistantTurn(id, userId, expectedEpoch, TurnGrounding.NONE)
    override fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long, grounding: TurnGrounding): Boolean =
        db.transactionWithResult {
            if (!admits(userId, expectedEpoch, grounding)) return@transactionWithResult false
            val existing = attempts().firstOrNull { it.id == id }
            if (existing != null) return@transactionWithResult existing.status == TurnStatus.PENDING
            if (attempts().any { it.userId == userId && it.status == TurnStatus.COMPLETED }) return@transactionWithResult false
            db.conversationQueries.startAttempt(id, userId, expectedEpoch)
            writeGrounding(id, grounding)
            true
        }
    override fun beginAssistantTurn(id: String, grounding: TurnGrounding): Boolean = db.transactionWithResult {
        val attempt = begins(id, grounding) ?: return@transactionWithResult false
        // Idempotent: grounding is written once per attempt.
        if (!attempt.grounded) writeGrounding(id, grounding)
        true
    }
    override fun recordSourcesResult(id: String, grounding: TurnGrounding): Boolean = db.transactionWithResult {
        require(grounding.sourcesResult != null && grounding.consulted.isEmpty())
        // Only an attempt that has not begun generating; submit and the retry start already refuse a completed turn.
        val attempt = begins(id, grounding) ?: return@transactionWithResult false
        if (attempt.grounded) return@transactionWithResult false
        db.conversationQueries.finishAttempt(TurnStatus.COMPLETED.name, null, id)
        writeGrounding(id, grounding)
        true
    }
    override fun discardPendingAssistantTurn(id: String) = db.transaction {
        val attempt = attempts().firstOrNull { it.id == id } ?: return@transaction
        if (attempt.status != TurnStatus.PENDING || attempt.grounded) return@transaction
        db.conversationQueries.deletePendingAttempt(id)
    }
    private val AssistantAttempt.grounded get() = retrieval != null || consulted.isNotEmpty() || sourcesResult != null
    /** The PENDING attempt, when its draft epoch, its context epoch and the fence still admit it. */
    private fun begins(id: String, grounding: TurnGrounding): AssistantAttempt? {
        val attempt = attempts().firstOrNull { it.id == id && it.status == TurnStatus.PENDING } ?: return null
        if (!admits(attempt.userId, attempt.epoch, grounding)) return null
        val attemptContext = db.conversationQueries.attemptContext(id).executeAsOne()
        if (grounding.contextEpoch != null && grounding.contextEpoch != attemptContext) return null
        return attempt
    }
    private fun admits(userId: Long, expectedEpoch: Long, grounding: TurnGrounding): Boolean =
        epoch() == expectedEpoch && (grounding.contextEpoch == null || grounding.contextEpoch == contextEpoch()) &&
            userId > messageFence() && messages().any { it.id == userId && it.role == MessageRole.USER }
    private fun writeGrounding(id: String, grounding: TurnGrounding) {
        val retrieval = grounding.retrieval
        if (retrieval != null || grounding.sourcesResult != null)
            db.conversationQueries.insertGrounding(id, retrieval?.status?.name, retrieval?.reason?.name,
                retrieval?.indexedRevisions, retrieval?.totalRevisions, grounding.sourcesResult?.name)
        grounding.consulted.forEach {
            db.conversationQueries.insertConsulted(id, it.position.toLong(), it.sourceId, it.revisionId, it.passageId,
                it.startUtf16, it.endUtf16)
        }
    }
    override fun completeAssistantTurn(id: String, text: String, timestamp: Long): Boolean = db.transactionWithResult {
        val attempt = attempts().firstOrNull { it.id == id } ?: return@transactionWithResult false
        if (attempt.status == TurnStatus.COMPLETED) return@transactionWithResult true
        if (attempt.status != TurnStatus.PENDING || attempt.epoch != epoch() ||
            db.conversationQueries.attemptContext(id).executeAsOne() != contextEpoch() || attempt.userId <= messageFence()) return@transactionWithResult false
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
        db.conversationQueries.clearConsulted()
        db.conversationQueries.clearGroundings()
        db.conversationQueries.clearAttempts()
        db.conversationQueries.clearMessageScopes()
        db.attachmentQueries.clearAttachments()
        db.attachmentQueries.writeEpoch(epoch() + 1)
        db.conversationQueries.clearSearch()
        db.conversationQueries.clearMessages()
        saveDraft("")
    }
}
