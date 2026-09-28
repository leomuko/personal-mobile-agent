package dev.edgecompanion.core.conversation.data

import dev.edgecompanion.core.attachments.model.Attachment

import dev.edgecompanion.core.conversation.model.Message

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
    fun clear()
}
