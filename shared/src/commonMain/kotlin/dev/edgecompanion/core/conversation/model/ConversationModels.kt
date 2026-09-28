package dev.edgecompanion.core.conversation.model

import dev.edgecompanion.core.attachments.model.Attachment

data class Message(val id: Long, val text: String, val createdAt: Long)
enum class ConversationError { STORAGE_OPEN, ENCRYPTED_STORAGE, DRAFT_SAVE, MESSAGE_SAVE, CLEAR, ATTACHMENT_SAVE, ATTACHMENT_LIMIT }
data class ConversationState(
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val ready: Boolean = false,
    val error: ConversationError? = null,
    val attachments: List<Attachment> = emptyList(),
    val draftEpoch: Long = 0,
)
