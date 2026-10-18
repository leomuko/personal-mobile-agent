package dev.edgecompanion.ui.app

import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.ui.conversation.screen.ConversationScreen
import dev.edgecompanion.ui.memory.screen.MemoryScreen
import dev.edgecompanion.ui.sources.screen.SourcesScreen
import dev.edgecompanion.ui.conversation.components.*

import dev.edgecompanion.ui.settings.screen.SettingsScreen
import dev.edgecompanion.ui.designsystem.components.AssistantTopBar
import dev.edgecompanion.ui.designsystem.components.ToolButton
import dev.edgecompanion.ui.navigation.AssistantNavigation
import dev.edgecompanion.ui.navigation.AssistantDestination

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.appearance.AppearanceState
import dev.edgecompanion.core.appearance.ThemeMode
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable fun CompanionScreen(
    state: ConversationState,
    drawer: Boolean,
    overlayEnabled: Boolean,
    deviceSummary: String,
    onDraft: (String) -> Unit,
    onSend: () -> Unit,
    onMicrophone: () -> Unit,
    onCamera: () -> Unit,
    onOverlay: (Boolean) -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onClear: () -> Unit,
    notificationsAvailable: Boolean = true,
    onNotificationSettings: () -> Unit = {},
    conversationRequest: Int = 0,
    onAttachmentSource: (AttachmentSource) -> Unit = { onCamera() },
    onRemoveAttachment: (String) -> Unit = {},
    onPreviewAttachment: (Attachment) -> Unit = {},
    thumbnail: @Composable (Attachment, Modifier) -> Unit = { item, modifier -> AttachmentThumbnail(item, modifier) },
    recording: RecordingState = RecordingState(),
    onStopRecording: () -> Unit = {},
    onCancelRecording: () -> Unit = {},
    modelsContent: (@Composable () -> Unit)? = null,
    onCancelAssistant: () -> Unit = {},
    onRetryAssistant: (Long) -> Unit = {},
    appearance: AppearanceState = AppearanceState(),
    onThemeMode: (ThemeMode) -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    AssistantNavigation(drawer, conversationRequest) { destination, openMenu, chatList ->
    val settings = destination == AssistantDestination.SETTINGS
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        if (drawer) Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(36.dp, 4.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)))
        }
        AssistantTopBar(
            title = stringResource(if (destination == AssistantDestination.CHAT) Res.string.assistant else destination.label),
            leading = if (drawer) null else ({ ToolButton(Icons.Outlined.Menu, stringResource(Res.string.open_navigation), openMenu, !recording.active) }),
        ) {
            if (drawer) {
                ToolButton(Icons.Outlined.OpenInFull, stringResource(Res.string.open_full_app), onExpand, !recording.active)
                ToolButton(Icons.Outlined.Close, stringResource(Res.string.close_conversation), onClose, !recording.active)
            }
        }
        when {
            settings -> {
                SettingsScreen(overlayEnabled, notificationsAvailable, deviceSummary, state.ready, onOverlay, onNotificationSettings,
                    { confirmClear = true }, Modifier.weight(1f), modelsContent, appearance, onThemeMode)
            }
            destination == AssistantDestination.CHAT -> {
                if (drawer && overlayEnabled) {
                    TextButton(onClick = { onOverlay(false) }) {
                        Icon(Icons.Outlined.StopCircle, null)
                        Text(stringResource(Res.string.stop_mascot), Modifier.padding(start = 8.dp))
                    }
                }
                ConversationScreen(state, chatList, Modifier.weight(1f), onDraft, onSend, onMicrophone, onAttachmentSource, onRemoveAttachment,
                    onPreviewAttachment, thumbnail, recording, onStopRecording, onCancelRecording, onCancelAssistant, onRetryAssistant)
            }
            destination == AssistantDestination.MEMORY -> MemoryScreen(Modifier.weight(1f))
            else -> SourcesScreen(Modifier.weight(1f))
        }
    }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(Res.string.clear_title)) }, text = { Text(stringResource(Res.string.clear_detail)) },
        confirmButton = { TextButton(onClick = { onClear(); confirmClear = false }) { Text(stringResource(Res.string.clear)) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(Res.string.cancel)) } })
}
