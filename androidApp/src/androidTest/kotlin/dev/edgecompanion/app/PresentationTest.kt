package dev.edgecompanion.app

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import dev.edgecompanion.ui.conversation.presentation.stringResource
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PresentationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun attachmentButtonShowsAllThreeSourcesWithoutLeavingChat() {
        var selected: dev.edgecompanion.ui.conversation.components.AttachmentSource? = null
        compose.setContent { CompanionTheme {
            CompanionScreen(ConversationState(ready = true, draft = "Keep draft"), false, false,
                "Synthetic", {}, {}, {}, {}, {}, {}, {}, {}, onAttachmentSource = { selected = it })
        } }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Files").assertIsDisplayed()
        compose.onNodeWithText("Camera").assertIsDisplayed()
        compose.onNodeWithText("Gallery").assertDoesNotExist()
        compose.onNodeWithText("Avatar").assertDoesNotExist()
        val photos = compose.onNodeWithText("Photos").fetchSemanticsNode().boundsInRoot
        val camera = compose.onNodeWithText("Camera").fetchSemanticsNode().boundsInRoot
        val files = compose.onNodeWithText("Files").fetchSemanticsNode().boundsInRoot
        assertTrue(photos.center.x < camera.center.x && camera.center.x < files.center.x)
        assertEquals(photos.center.y, camera.center.y, 1f)
        captureSyntheticUi("composer-source-sheet.png")
        compose.onNodeWithText("Photos").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(dev.edgecompanion.ui.conversation.components.AttachmentSource.GALLERY, selected) }
        compose.onNodeWithText("Keep draft").assertIsDisplayed()
    }

    @Test fun recordingBubblePreservesConversationAndStopDoesNotSend() {
        var stopped = false; var sent = false
        compose.setContent { CompanionTheme {
            CompanionScreen(ConversationState(ready = true, messages = listOf(dev.edgecompanion.core.conversation.model.Message(1, "Existing conversation", 0))),
                true, false, "Synthetic", {}, { sent = true }, {}, {}, {}, {}, {}, {},
                recording = dev.edgecompanion.ui.conversation.components.RecordingState(true, 8, List(32) { (it % 5) / 5f }),
                onStopRecording = { stopped = true })
        } }
        compose.onNodeWithText("Existing conversation").assertIsDisplayed()
        compose.onNodeWithText("Recording").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open full app").assertIsNotEnabled()
        captureSyntheticUi("inline-recording.png")
        compose.onNodeWithContentDescription("Stop recording").performClick()
        compose.runOnIdle { assertTrue(stopped); assertFalse(sent) }
    }

    @Test fun restoredAttachmentTileStaysWithUnsentPrompt() {
        val restore = StateRestorationTester(compose)
        restore.setContent { Screen(ConversationState(ready = true, draft = "Review this",
            attachments = listOf(dev.edgecompanion.core.attachments.model.Attachment("doc", "Synthetic.pdf", "application/pdf", 1024,
                dev.edgecompanion.core.attachments.model.AttachmentStatus.READY)))) }
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Synthetic.pdf").assertIsDisplayed()
        compose.onNodeWithText("Review this").assertIsDisplayed()
        captureSyntheticUi("attachment-draft.png")
    }

    @Test fun everyTypedFailureHasResourceBackedPresentation() {
        val state = mutableStateOf(ConversationState(ready = true))
        compose.setContent { Screen(state.value) }
        for (error in ConversationError.entries) {
            compose.runOnIdle { state.value = ConversationState(ready = true, error = error) }
            val text = runBlocking { getString(error.stringResource()) }
            compose.onNodeWithText(text).assertIsDisplayed()
        }
    }

    @Test fun tabSelectionSurvivesLayoutDirectionChange() {
        val rtl = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl.value) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Screen(ConversationState(ready = true))
            }
        }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Memory").performClick()
        compose.onNodeWithText("No memories yet").assertIsDisplayed()
        compose.runOnIdle { rtl.value = true }
        compose.onNodeWithText("No memories yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Chat").performClick()
        compose.onNodeWithText("Ask anything...").assertIsDisplayed()
    }

    @Test fun drawerStopAndHeaderControlsAreSeparateAccessibleActions() {
        var stopped = 0; var closed = 0; var expanded = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                CompanionTheme {
                    CompanionScreen(ConversationState(ready = true), true, true, "Synthetic device", {}, {}, {}, {},
                        onOverlay = { assertFalse(it); stopped++ }, onExpand = { expanded++ }, onClose = { closed++ }, onClear = {})
                }
            }
        }
        compose.onNodeWithText("Stop floating mascot").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Open full app").assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Close conversation").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, stopped); assertEquals(1, closed); assertEquals(1, expanded) }
    }

    @Test fun settingsExposeDisabledNotificationStateWithoutDisablingStop() {
        var stopped = false
        compose.setContent {
            CompanionTheme {
                CompanionScreen(ConversationState(ready = true), false, true, "Synthetic device", {}, {}, {}, {},
                    onOverlay = { stopped = !it }, onExpand = {}, onClose = {}, onClear = {}, notificationsAvailable = false)
            }
        }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Notification controls unavailable").assertIsDisplayed()
        compose.onNode(isToggleable()).performClick()
        compose.runOnIdle { assertTrue(stopped) }
    }

    @Composable private fun Screen(state: ConversationState) {
        CompanionTheme { CompanionScreen(state, false, false, "Synthetic device", {}, {}, {}, {}, {}, {}, {}, {}) }
    }

    @Test fun navigationDoesNotRemoveDraftAndExplicitConversationRequestReturnsToChat() {
        val request = mutableIntStateOf(0)
        compose.setContent {
            CompanionTheme {
                CompanionScreen(ConversationState(ready = true, draft = "Synthetic draft"), false, false,
                    "Synthetic device", {}, {}, {}, {}, {}, {}, {}, {}, conversationRequest = request.intValue)
            }
        }
        compose.onNodeWithText("Synthetic draft").assertIsDisplayed()
        captureSyntheticUi("navigation-chat.png")
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        captureSyntheticUi("navigation-menu.png")
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("No saved sources").assertIsDisplayed()
        compose.runOnIdle { request.intValue++ }
        compose.onNodeWithText("Synthetic draft").assertIsDisplayed()
    }

    @Test fun floatingConversationDoesNotShowFullAppNavigation() {
        compose.setContent {
            CompanionTheme {
                CompanionScreen(ConversationState(ready = true), true, false,
                    "Synthetic device", {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithContentDescription("Open navigation menu").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open full app").assertIsDisplayed()
    }

    @Test fun menuBackClosesBeforeNavigationAndDestinationRestores() {
        lateinit var back: OnBackPressedDispatcher
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            back = requireNotNull(LocalOnBackPressedDispatcherOwner.current).onBackPressedDispatcher
            Screen(ConversationState(ready = true))
        }
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Ask anything...").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close navigation menu").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.onNodeWithText("Memory").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("No memories yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation menu").performClick()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("No memories yet").assertIsDisplayed()
        compose.runOnIdle { back.onBackPressed() }
        compose.onNodeWithText("Ask anything...").assertIsDisplayed()
    }

    private fun captureSyntheticUi(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use {
            // PixelCopy of a Compose node can capture the Activity behind a platform dialog.
            val bitmap = if (name.contains("sheet")) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                else compose.onRoot().captureToImage().asAndroidBitmap()
            checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
