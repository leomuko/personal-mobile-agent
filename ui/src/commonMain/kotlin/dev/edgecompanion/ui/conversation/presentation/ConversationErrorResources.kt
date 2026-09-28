package dev.edgecompanion.ui.conversation.presentation

import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.StringResource

fun ConversationError.stringResource(): StringResource = when (this) {
    ConversationError.ATTACHMENT_SAVE -> Res.string.error_attachment
    ConversationError.ATTACHMENT_LIMIT -> Res.string.error_attachment_limit
    ConversationError.STORAGE_OPEN -> Res.string.error_storage_open
    ConversationError.ENCRYPTED_STORAGE -> Res.string.error_encrypted_storage
    ConversationError.DRAFT_SAVE -> Res.string.error_draft_save
    ConversationError.MESSAGE_SAVE -> Res.string.error_message_save
    ConversationError.CLEAR -> Res.string.error_clear
}
