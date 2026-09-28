package dev.edgecompanion.ui.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.ui.designsystem.components.ToolButton
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

enum class AttachmentSource { FILES, CAMERA, GALLERY }

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable internal fun Composer(state: ConversationState, onDraft: (String) -> Unit, onSend: () -> Unit,
    onMic: () -> Unit, onSource: (AttachmentSource) -> Unit, onRemove: (String) -> Unit, onPreview: (Attachment) -> Unit,
    thumbnail: @Composable (Attachment, Modifier) -> Unit, recording: RecordingState, onStop: () -> Unit, onCancel: () -> Unit) {
    var sources by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var draft by remember(state.draftEpoch) { mutableStateOf(state.draft) }
    var awaitingDraft by remember(state.draftEpoch) { mutableStateOf<String?>(null) }
    // Text input requires a synchronous echo; storage acknowledgements may arrive several frames later.
    LaunchedEffect(state.draft, state.draftEpoch, state.error) {
        if (awaitingDraft == null || awaitingDraft == state.draft || state.error == ConversationError.DRAFT_SAVE) {
            draft = state.draft
            awaitingDraft = null
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var imeWasVisible by remember { mutableStateOf(false) }
    val endEditing = {
        editing = false
        focus.clearFocus()
        keyboard?.hide()
    }
    // Initial zero insets are not a dismissal: hardware keyboards may never report an IME.
    LaunchedEffect(imeVisible) {
        if (imeWasVisible && !imeVisible) endEditing()
        imeWasVisible = imeVisible
    }
    val attachments = state.attachments.filter { it.messageId == null }
    val canSend = state.ready && draft == state.draft && (draft.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.all { it.status == AttachmentStatus.READY }
    val openSources = {
        endEditing()
        sources = true
    }
    if (recording.active) Column {
        AttachmentStrip(attachments, onRemove, onPreview, thumbnail)
        RecordingBubble(recording, onStop, onCancel)
    } else BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val style = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
        val measurer = rememberTextMeasurer()
        val textWidth = with(density) { (maxWidth - 176.dp).roundToPx().coerceAtLeast(1) }
        val multiline = remember(draft, style, textWidth, measurer) {
            measurer.measure(draft, style, constraints = Constraints(maxWidth = textWidth), maxLines = 2).lineCount > 1
        }
        val expanded = editing || imeVisible || attachments.isNotEmpty() || multiline
        Surface(shape = RoundedCornerShape(if (expanded) 24.dp else 36.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().testTag(if (expanded) "composer-expanded" else "composer-compact")) {
            ComposerInputLayout(expanded,
                attachments = { AttachmentStrip(attachments, onRemove, onPreview, thumbnail) },
                field = {
                    BasicTextField(draft, { text ->
                        draft = text.take(16_000)
                        awaitingDraft = draft
                        onDraft(draft)
                    }, enabled = state.ready, textStyle = style,
                        maxLines = 4, cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        modifier = Modifier.onFocusChanged { editing = it.isFocused }
                            .onPreInterceptKeyBeforeSoftKeyboard { event ->
                                if (editing && event.key == Key.Back) {
                                    if (event.type == KeyEventType.KeyUp) endEditing()
                                    true
                                } else false
                            }.testTag("composer-input"),
                        decorationBox = { input ->
                            Box(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                                if (draft.isEmpty()) Text(stringResource(Res.string.ask_anything), style = style,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                input()
                            }
                        })
                },
                add = { ToolButton(Icons.Outlined.Add, stringResource(Res.string.add_attachment), openSources, state.ready) },
                mic = {
                    ToolButton(Icons.Outlined.Mic, stringResource(Res.string.start_voice), {
                        endEditing(); onMic()
                    }, state.ready)
                },
                send = {
                    FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Outlined.ArrowUpward, stringResource(Res.string.save_message))
                    }
                })
        }
    }
    if (sources) AttachmentSourceSheet(onDismiss = { sources = false }, onSource = {
        sources = false
        onSource(it)
    })
    // Register after the field so editing dismissal takes precedence over its own Back handler.
    BackHandler(enabled = editing && !sources && !recording.active) { endEditing() }
}

/** Reposition one stable text field instead of recreating its focus/selection when the IME opens. */
@Composable private fun ComposerInputLayout(expanded: Boolean, attachments: @Composable () -> Unit,
    field: @Composable () -> Unit, add: @Composable () -> Unit, mic: @Composable () -> Unit, send: @Composable () -> Unit) {
    Layout(modifier = Modifier.fillMaxWidth().padding(8.dp), content = {
        Box { attachments() }
        Box { field() }
        Box { add() }
        Box { mic() }
        Box { send() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val button = 48.dp.roundToPx()
        val addButton = children[2].measure(Constraints.fixed(button, button))
        val micButton = children[3].measure(Constraints.fixed(button, button))
        val sendButton = children[4].measure(Constraints.fixed(button, button))
        val strip = children[0].measure(Constraints(maxWidth = width))
        val fieldWidth = if (expanded) width else (width - 3 * button).coerceAtLeast(1)
        val input = children[1].measure(Constraints(minWidth = fieldWidth, maxWidth = fieldWidth))
        val rowHeight = maxOf(button, if (expanded) 0 else input.height)
        val actionY = if (expanded) strip.height + input.height else 0
        layout(width, actionY + rowHeight) {
            strip.placeRelative(0, 0)
            input.placeRelative(if (expanded) 0 else button, if (expanded) strip.height else (rowHeight - input.height) / 2)
            addButton.placeRelative(0, actionY + (rowHeight - button) / 2)
            micButton.placeRelative(width - 2 * button, actionY + (rowHeight - button) / 2)
            sendButton.placeRelative(width - button, actionY + (rowHeight - button) / 2)
        }
    }
}
