package dev.edgecompanion.app

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.attachments.model.AttachmentStatus
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AssistantPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun attachmentGuidanceTracksDraftAndActiveTurnNotHistory() {
        val historical = Attachment("past", "Past.txt", "text/plain", status = AttachmentStatus.READY, messageId = 1)
        val state = mutableStateOf(ConversationState(ready = true,
            messages = listOf(Message(1, "Earlier message", 1)), attachments = listOf(historical),
            assistant = AssistantState(enabled = true)))
        compose.setContent { CompanionTheme {
            CompanionScreen(state.value, true, false, "Synthetic", {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("attachment-guidance").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(attachments = listOf(historical, historical.copy(id = "draft", messageId = null))) }
        compose.onNodeWithTag("attachment-guidance").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(attachments = listOf(historical),
            assistant = AssistantState(true, AssistantPhase.STREAMING, "active", 1, "Reply")) }
        compose.onNodeWithTag("attachment-guidance").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(assistant = state.value.assistant.copy(userId = 2)) }
        compose.onNodeWithTag("attachment-guidance").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(assistant = AssistantState(enabled = true)) }
        compose.onNodeWithTag("attachment-guidance").assertDoesNotExist()
    }
    @Test fun roleAwareStreamingStopAndRetryAreReachableInDrawer() {
        val state = mutableStateOf(ConversationState(ready = true, draft = "Next draft",
            messages = listOf(Message(1, "Hello", 1), Message(2, "Earlier answer", 2, MessageRole.ASSISTANT, 1)),
            assistant = AssistantState(true, AssistantPhase.STREAMING, "a", 1, "Streaming reply")))
        var stopped = 0; var retryId: Long? = null
        compose.setContent { CompanionTheme {
            CompanionScreen(state.value, true, false, "Synthetic", {}, {}, {}, {}, {}, {}, {}, {},
                onCancelAssistant = { stopped++ }, onRetryAssistant = { retryId = it })
        } }
        compose.onNodeWithTag("message-USER-1").assertExists()
        compose.onNodeWithTag("message-ASSISTANT-2").assertExists()
        compose.onNodeWithTag("assistant-stream").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Start voice capture").assertIsNotEnabled()
        compose.onNodeWithText("Next draft").assertIsDisplayed()
        compose.onNodeWithContentDescription("Stop response").performClick()
        compose.runOnIdle {
            assertEquals(1, stopped)
            state.value = state.value.copy(assistant = AssistantState(enabled = true),
                attempts = listOf(AssistantAttempt("cancelled", 1, 1, TurnStatus.CANCELLED)))
        }
        compose.onNodeWithTag("assistant-stream").assertDoesNotExist()
        compose.onNodeWithText("Retry response").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1L, retryId) }
    }
}
