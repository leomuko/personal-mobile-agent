package dev.edgecompanion.ui.conversation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.edgecompanion.core.conversation.model.ConsultedPassage
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.core.sources.model.SourceException
import dev.edgecompanion.ui.conversation.presentation.ConsultedRead
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Each host has its own lifecycle, while durable work belongs to the shared session. */
class ConversationViewModel(private val session: ConversationSession) : ViewModel() {
    val state = session.state
    val hasSources = session.sourceChanges.map {
        try { session.hasSources() } catch (_: SourceException) { null }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), null)

    fun observeConsulted(references: List<ConsultedPassage>) = session.sourceChanges.map {
        try { ConsultedRead.Loaded(session.consultedPreviews(references)) }
        catch (_: SourceException) { ConsultedRead.Unavailable }
    }

    fun editDraft(text: String) = session.editDraft(text)
    fun setSourcesOnly(enabled: Boolean) = session.setDraftScope(if (enabled) RequestScope.SOURCES else RequestScope.ORDINARY)
    fun submit() = session.submitDraft()
    fun cancelAssistant() = session.cancelAssistant()
    fun retryAssistant(userId: Long) = session.retryAssistant(userId)
    fun clearConversation() = session.clear()
    fun removeAttachment(id: String) = session.removeAttachment(id)
}
