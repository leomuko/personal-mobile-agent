package dev.edgecompanion.ui.conversation.viewmodel

import androidx.lifecycle.ViewModel
import dev.edgecompanion.core.conversation.session.ConversationSession

/** Each host has its own lifecycle, while durable work belongs to the shared session. */
class ConversationViewModel(private val session: ConversationSession) : ViewModel() {
    val state = session.state
    fun editDraft(text: String) = session.editDraft(text)
    fun submit() = session.submit()
    fun clearConversation() = session.clear()
    fun removeAttachment(id: String) = session.removeAttachment(id)
}
