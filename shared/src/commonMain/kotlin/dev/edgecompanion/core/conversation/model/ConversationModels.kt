package dev.edgecompanion.core.conversation.model

import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.inference.ModelError

enum class MessageRole { USER, ASSISTANT }
enum class TurnStatus { PENDING, COMPLETED, CANCELLED, FAILED, INTERRUPTED }
data class Message(val id: Long, val text: String, val createdAt: Long,
    val role: MessageRole = MessageRole.USER, val turnId: Long = id)
data class AssistantAttempt(val id: String, val userId: Long, val epoch: Long, val status: TurnStatus,
    val error: ModelError? = null)
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
)
