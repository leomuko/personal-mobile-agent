package dev.edgecompanion.core.conversation.data

import dev.edgecompanion.core.attachments.model.Attachment

import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.AssistantAttempt
import dev.edgecompanion.core.conversation.model.TurnStatus
import dev.edgecompanion.core.inference.ModelError

interface ConversationStore {
    fun attachments(): List<Attachment> = emptyList()
    fun epoch(): Long = 0
    fun reserve(item: Attachment) { error("Attachments unavailable") }
    fun complete(id: String, size: Long) { error("Attachments unavailable") }
    fun fail(id: String) { error("Attachments unavailable") }
    fun remove(id: String) { error("Attachments unavailable") }
    fun interruptImports() = Unit
    fun messages(): List<Message>
    fun draft(): String
    fun saveDraft(text: String)
    fun append(text: String, timestamp: Long)
    fun submitUserMessage(text: String, timestamp: Long): Message { append(text, timestamp); return messages().last() }
    fun attempts(): List<AssistantAttempt> = emptyList()
    fun startAssistantTurn(id: String, userId: Long, expectedEpoch: Long): Boolean = false
    fun completeAssistantTurn(id: String, text: String, timestamp: Long): Boolean = false
    fun finishAssistantTurn(id: String, status: TurnStatus, error: ModelError? = null) = Unit
    fun reconcileAssistantTurns() = Unit
    fun clear()
}
