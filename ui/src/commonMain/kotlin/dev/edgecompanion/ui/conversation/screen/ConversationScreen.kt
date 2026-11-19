package dev.edgecompanion.ui.conversation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.ConsultedPassage
import dev.edgecompanion.core.sources.model.SourceSpan
import dev.edgecompanion.ui.conversation.components.*
import dev.edgecompanion.ui.conversation.presentation.ConsultedRead
import kotlinx.coroutines.flow.Flow

@Composable internal fun ConversationScreen(state: ConversationState, list: LazyListState, modifier: Modifier,
    onDraft: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onSource: (AttachmentSource) -> Unit,
    onRemove: (String) -> Unit, onPreview: (Attachment) -> Unit, thumbnail: @Composable (Attachment, Modifier) -> Unit,
    recording: RecordingState, onStop: () -> Unit, onCancel: () -> Unit,
    onCancelAssistant: () -> Unit, onRetryAssistant: (Long) -> Unit,
    hasSources: Boolean?, onSourcesOnly: (Boolean) -> Unit,
    observeConsulted: (List<ConsultedPassage>) -> Flow<ConsultedRead>, onOpenPassage: (SourceSpan) -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        Conversation(state, list, Modifier.weight(1f), onPreview, thumbnail, onRetryAssistant, observeConsulted, onOpenPassage)
        Composer(state, onDraft, onSend, onMic, onSource, onRemove, onPreview, thumbnail, recording, onStop, onCancel,
            onCancelAssistant, hasSources, onSourcesOnly)
    }
}
