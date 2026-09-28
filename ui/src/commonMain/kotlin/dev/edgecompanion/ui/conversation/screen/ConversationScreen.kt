package dev.edgecompanion.ui.conversation.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.ui.conversation.components.*

@Composable internal fun ConversationScreen(state: ConversationState, list: LazyListState, modifier: Modifier,
    onDraft: (String) -> Unit, onSend: () -> Unit, onMic: () -> Unit, onSource: (AttachmentSource) -> Unit,
    onRemove: (String) -> Unit, onPreview: (Attachment) -> Unit, thumbnail: @Composable (Attachment, Modifier) -> Unit,
    recording: RecordingState, onStop: () -> Unit, onCancel: () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Conversation(state, list, Modifier.weight(1f), onPreview, thumbnail)
        Composer(state, onDraft, onSend, onMic, onSource, onRemove, onPreview, thumbnail, recording, onStop, onCancel)
    }
}
