package dev.edgecompanion.ui.conversation.components

import dev.edgecompanion.core.attachments.model.Attachment

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
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.inference.ModelError
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.testTag
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun Conversation(state: ConversationState, list: LazyListState, modifier: Modifier,
    onPreview: (Attachment) -> Unit, thumbnail: @Composable (Attachment, Modifier) -> Unit,
    onRetryAssistant: (Long) -> Unit = {}) {
    var lastMessageCount by rememberSaveable { mutableStateOf(0) }
    val latestAttempts = remember(state.attempts) { state.attempts.associateBy { it.userId } }
    LaunchedEffect(state.messages.size) {
        if (state.messages.size > lastMessageCount) list.animateScrollToItem(state.messages.lastIndex)
        lastMessageCount = state.messages.size
    }
    LaunchedEffect(state.assistant.id) {
        if (state.assistant.active && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= state.messages.lastIndex)
            list.animateScrollToItem(state.messages.size)
    }
    Column(modifier.fillMaxWidth()) {
        if (!state.assistant.enabled) Text(stringResource(Res.string.model_missing), Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp),
            color = MaterialTheme.colorScheme.onTertiaryContainer, style = MaterialTheme.typography.labelMedium)
        val relevantAttachments = state.attachments.any {
            it.messageId == null || (state.assistant.active && it.messageId == state.assistant.userId)
        }
        if (state.assistant.enabled && relevantAttachments)
            Text(stringResource(Res.string.assistant_attachments),
                Modifier.testTag("attachment-guidance").padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall)
        state.assistant.error?.let { Text(stringResource(it.chatLabel()), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
        if (state.assistant.saveFailed) Text(stringResource(Res.string.assistant_save_failed), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
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
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (message.role == MessageRole.USER) Alignment.End else Alignment.Start) {
                    AttachmentStrip(state.attachments.filter { it.messageId == message.id }, onPreview = onPreview, thumbnail = thumbnail)
                    if (message.text.isNotEmpty()) Surface(color = if (message.role == MessageRole.USER) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (message.role == MessageRole.USER) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth(0.9f).testTag("message-${message.role.name}-${message.id}")) {
                        Text(message.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                    Text(stringResource(Res.string.saved_locally), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val attempt = latestAttempts[message.id]
                    if (message.role == MessageRole.USER && attempt != null && attempt.status in setOf(TurnStatus.CANCELLED, TurnStatus.FAILED, TurnStatus.INTERRUPTED)) {
                        Text(stringResource(if (attempt.status == TurnStatus.CANCELLED) Res.string.assistant_cancelled else Res.string.assistant_interrupted),
                            style = MaterialTheme.typography.labelSmall)
                        if (state.assistant.enabled && message.id > state.messageFence) TextButton(onClick = { onRetryAssistant(message.id) }, enabled = !state.assistant.active) {
                            Icon(Icons.Outlined.Refresh, null); Text(stringResource(Res.string.assistant_retry))
                        }
                    }
                }
            }
            if (state.assistant.active) item(key = "active-assistant") {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(0.9f).testTag("assistant-stream")) {
                    Column(Modifier.padding(14.dp)) {
                        if (state.assistant.text.isEmpty()) Text(stringResource(Res.string.assistant_preparing))
                        else Text(state.assistant.text)
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                }
            }
        }
    }
}

private fun ModelError.chatLabel() = when (this) {
    ModelError.NOT_INSTALLED -> Res.string.model_asset_missing
    ModelError.BUSY -> Res.string.model_busy
    ModelError.LOW_MEMORY -> Res.string.model_memory_error
    ModelError.THERMAL -> Res.string.model_thermal_error
    ModelError.CONTEXT_LIMIT -> Res.string.model_context_error
    ModelError.RESTART_REQUIRED, ModelError.CANCEL_TIMEOUT, ModelError.RELEASE_FAILED -> Res.string.model_restart_error
    ModelError.RESOURCE_UNKNOWN -> Res.string.model_resource_error
    ModelError.UNSUPPORTED_DEVICE -> Res.string.model_device_error
    else -> Res.string.model_generation_error
}
