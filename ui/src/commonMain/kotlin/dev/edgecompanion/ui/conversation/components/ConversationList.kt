package dev.edgecompanion.ui.conversation.components

import dev.edgecompanion.core.attachments.model.Attachment

import dev.edgecompanion.ui.designsystem.theme.Mint
import dev.edgecompanion.ui.designsystem.components.Mascot
import dev.edgecompanion.ui.conversation.presentation.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun Conversation(state: ConversationState, list: LazyListState, modifier: Modifier,
    onPreview: (Attachment) -> Unit, thumbnail: @Composable (Attachment, Modifier) -> Unit) {
    var lastMessageCount by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastMessageCount) list.animateScrollToItem(state.messages.lastIndex)
        lastMessageCount = state.messages.size
    }
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(Res.string.model_missing), Modifier.fillMaxWidth().background(Color(0xFFF5F1EC)).padding(12.dp),
            color = Color(0xFF735438), style = MaterialTheme.typography.labelMedium)
        state.error?.let { Text(stringResource(it.stringResource()), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (state.messages.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Mascot(Modifier.size(112.dp))
                    Text(stringResource(if (state.ready) Res.string.empty_prompt else Res.string.storage_opening),
                        Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
        } else LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(state.messages, key = { it.id }) { message ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                    AttachmentStrip(state.attachments.filter { it.messageId == message.id }, onPreview = onPreview, thumbnail = thumbnail)
                    if (message.text.isNotEmpty()) Surface(color = Mint, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(0.9f)) {
                        Text(message.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(Res.string.saved_locally), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
