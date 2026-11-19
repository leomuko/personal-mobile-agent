package dev.edgecompanion.ui.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.ui.designsystem.components.ToolButton
import dev.edgecompanion.ui.conversation.presentation.SourcesChipState
import dev.edgecompanion.ui.conversation.presentation.sourcesChip
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

enum class AttachmentSource { FILES, CAMERA, GALLERY }

@OptIn(ExperimentalLayoutApi::class, ExperimentalComposeUiApi::class)
@Composable internal fun Composer(state: ConversationState, onDraft: (String) -> Unit, onSend: () -> Unit,
    onMic: () -> Unit, onSource: (AttachmentSource) -> Unit, onRemove: (String) -> Unit, onPreview: (Attachment) -> Unit,
    thumbnail: @Composable (Attachment, Modifier) -> Unit, recording: RecordingState, onStop: () -> Unit, onCancel: () -> Unit,
    onCancelAssistant: () -> Unit = {}, hasSources: Boolean? = null, onSourcesOnly: (Boolean) -> Unit = {}) {
    var attachmentSheetOpen by remember { mutableStateOf(false) }
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
    val canSend = state.ready && !state.assistant.active && draft == state.draft && (draft.isNotBlank() || attachments.isNotEmpty()) &&
        attachments.all { it.status == AttachmentStatus.READY }
    val openAttachments = {
        endEditing()
        attachmentSheetOpen = true
    }
    val chip = state.sourcesChip(hasSources)
    if (recording.active) Column {
        AttachmentStrip(attachments, onRemove, onPreview, thumbnail)
        RecordingBubble(recording, onStop, onCancel)
    } else Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
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
                    add = { ToolButton(Icons.Outlined.Add, stringResource(Res.string.add_attachment), openAttachments, state.ready) },
                    sourcesChip = { SourcesChip(chip, onSourcesOnly) },
                    mic = {
                        ToolButton(Icons.Outlined.Mic, stringResource(Res.string.start_voice), {
                            endEditing(); onMic()
                        }, state.ready && !state.assistant.active)
                    },
                    send = {
                        FilledIconButton(onClick = if (state.assistant.active) onCancelAssistant else onSend,
                            enabled = state.assistant.active || canSend, modifier = Modifier.size(48.dp)) {
                            Icon(if (state.assistant.active) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                                stringResource(if (state.assistant.active) Res.string.assistant_stop else Res.string.save_message))
                        }
                    })
            }
        }
        if (chip.showEmptyHint) {
            // The disabled switch already announces the hint.
            val hint = Modifier.testTag("sources-empty-hint")
            val semantics = if (chip.enabled) hint else hint.clearAndSetSemantics {}
            Text(stringResource(Res.string.sources_empty_hint), semantics.padding(start = 12.dp, top = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (attachmentSheetOpen) AttachmentSourceSheet(onDismiss = { attachmentSheetOpen = false }, onSource = {
        attachmentSheetOpen = false
        onSource(it)
    })
    // Register after the field so editing dismissal takes precedence over its own Back handler.
    BackHandler(enabled = editing && !attachmentSheetOpen && !recording.active) { endEditing() }
}

@Composable private fun SourcesChip(state: SourcesChipState, onChange: (Boolean) -> Unit) {
    val name = stringResource(Res.string.sources_only_toggle)
    val disabledState = if (!state.enabled && state.showEmptyHint)
        stringResource(Res.string.sources_only_disabled_state, stringResource(Res.string.sources_empty_hint))
    else null
    Surface(
        modifier = Modifier.heightIn(min = 48.dp).testTag("sources-scope-chip")
            .semantics {
                contentDescription = name
                if (disabledState != null) stateDescription = disabledState
            }
            .toggleable(value = state.checked, enabled = state.enabled, role = Role.Switch, onValueChange = onChange),
        shape = RoundedCornerShape(16.dp),
        color = if (state.checked && state.enabled) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surface,
        contentColor = if (state.checked && state.enabled) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (state.enabled) 1f else 0.60f),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp).clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, Modifier.size(16.dp))
            Text(stringResource(Res.string.my_sources), Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelMedium)
            if (state.checked) Icon(Icons.Outlined.Check, null, Modifier.size(16.dp))
        }
    }
}

/** Reposition one stable text field instead of recreating its focus/selection when the IME opens. */
@Composable private fun ComposerInputLayout(expanded: Boolean, attachments: @Composable () -> Unit,
    field: @Composable () -> Unit, add: @Composable () -> Unit, sourcesChip: @Composable () -> Unit,
    mic: @Composable () -> Unit, send: @Composable () -> Unit) {
    Layout(modifier = Modifier.fillMaxWidth().padding(8.dp), content = {
        Box { attachments() }
        Box { field() }
        Box { add() }
        Box { mic() }
        Box { send() }
        Box { sourcesChip() }
    }) { children, constraints ->
        val width = constraints.maxWidth
        val button = 48.dp.roundToPx()
        val addButton = children[2].measure(Constraints.fixed(button, button))
        val micButton = children[3].measure(Constraints.fixed(button, button))
        val sendButton = children[4].measure(Constraints.fixed(button, button))
        val strip = children[0].measure(Constraints(maxWidth = width))
        val chipWidthLimit = minOf(148.dp.roundToPx(), (width - button - 72.dp.roundToPx()).coerceAtLeast(0))
        val chip = children[5].measure(Constraints(maxWidth = chipWidthLimit))
        val compactFieldWidth = width - 3 * button - chip.width
        val wrapActions = expanded || compactFieldWidth < 72.dp.roundToPx()
        val fieldWidth = (if (wrapActions) width - button - chip.width else compactFieldWidth).coerceAtLeast(1)
        val input = children[1].measure(Constraints(minWidth = fieldWidth, maxWidth = fieldWidth))
        val rowHeight = maxOf(button, input.height, chip.height)
        val actionY = if (wrapActions) strip.height + rowHeight else (rowHeight - button) / 2
        layout(width, strip.height + rowHeight + if (wrapActions) button else 0) {
            strip.placeRelative(0, 0)
            input.placeRelative(button + chip.width, strip.height + (rowHeight - input.height) / 2)
            addButton.placeRelative(0, strip.height + (rowHeight - button) / 2)
            chip.placeRelative(button, strip.height + (rowHeight - chip.height) / 2)
            micButton.placeRelative(width - 2 * button, actionY)
            sendButton.placeRelative(width - button, actionY)
        }
    }
}
