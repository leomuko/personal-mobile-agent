package dev.edgecompanion.core.conversation.model

import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.inference.ModelError
import dev.edgecompanion.core.sources.model.PassageStopReason
import dev.edgecompanion.core.sources.model.SourceSpan

enum class MessageRole { USER, ASSISTANT }
enum class TurnStatus { PENDING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }
/** Persisted with each user turn (D3). Rows written before S4 read as ORDINARY. */
enum class RequestScope { ORDINARY, SOURCES }
data class Message(val id: Long, val text: String, val createdAt: Long,
    val role: MessageRole = MessageRole.USER, val turnId: Long = id,
    val scope: RequestScope = RequestScope.ORDINARY)

/** Typed SOURCES-scope outcome when no usable passage exists. */
enum class SourcesNoAnswer(val canRetry: Boolean) {
    NO_MATCH(false), NOT_INDEXED(true), RETRIEVAL_INCOMPLETE(true), PASSAGES_UNUSABLE(false)
}
/** SKIPPED: the library had no source revisions, so nothing was searched (D3). */
enum class RetrievalStatus { COMPLETE, INCOMPLETE, FAILED, SKIPPED }
data class RetrievalSummary(val status: RetrievalStatus, val reason: PassageStopReason?,
    val indexedRevisions: Long, val totalRevisions: Long)
/** A reference only; text and title are resolved live, and a missing source reads as deleted. */
data class ConsultedPassage(val position: Int, val sourceId: Long, val revisionId: Long, val passageId: Long,
    val startUtf16: Long, val endUtf16: Long) {
    val span get() = SourceSpan(sourceId, revisionId, passageId, startUtf16, endUtf16)
}
/** What an attempt was grounded on. [contextEpoch] is checked in the attempt-start transaction when set. */
data class TurnGrounding(val contextEpoch: Long? = null, val retrieval: RetrievalSummary? = null,
    val consulted: List<ConsultedPassage> = emptyList(), val sourcesResult: SourcesNoAnswer? = null) {
    companion object { val NONE = TurnGrounding() }
}
data class AssistantAttempt(val id: String, val userId: Long, val epoch: Long, val status: TurnStatus,
    val error: ModelError? = null, val retrieval: RetrievalSummary? = null,
    val consulted: List<ConsultedPassage> = emptyList(), val sourcesResult: SourcesNoAnswer? = null)
internal val AssistantAttempt.blocksRetry get() = status == TurnStatus.COMPLETED && sourcesResult?.canRetry != true
enum class AssistantPhase { IDLE, LOADING, STREAMING, ERROR }
data class AssistantState(val enabled: Boolean = false, val phase: AssistantPhase = AssistantPhase.IDLE,
    val id: String? = null, val userId: Long? = null, val text: String = "", val error: ModelError? = null,
    val saveFailed: Boolean = false) {
    val active: Boolean get() = phase == AssistantPhase.LOADING || phase == AssistantPhase.STREAMING
}
enum class ConversationError { STORAGE_OPEN, ENCRYPTED_STORAGE, DRAFT_SAVE, MESSAGE_SAVE, CLEAR, ATTACHMENT_SAVE, ATTACHMENT_LIMIT }
data class ConversationState(
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val ready: Boolean = false,
    val error: ConversationError? = null,
    val attachments: List<Attachment> = emptyList(),
    val draftEpoch: Long = 0,
    val attempts: List<AssistantAttempt> = emptyList(),
    val assistant: AssistantState = AssistantState(),
    val contextEpoch: Long = 0,
    val messageFence: Long = 0,
    val draftScope: RequestScope = RequestScope.ORDINARY,
)
