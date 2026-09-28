package dev.edgecompanion.app

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.attachments.model.AttachmentStatus
import dev.edgecompanion.core.conversation.session.ConversationSession
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Real capture uses only the separate synthetic-data QA package. */
class CaptureFlowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val session get() = GlobalContext.get().get<ConversationSession>()

    private fun prepare(permission: String) {
        assumeTrue(context.packageName.endsWith(".secureqa"))
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        compose.waitUntil(10_000) { session.state.value.ready }
        session.clear()
        compose.waitUntil(5_000) { session.state.value.messages.isEmpty() && session.state.value.attachments.isEmpty() }
    }

    @Test fun microphoneRecordsInlineAndStopCreatesUnsentPlayableAttachment() {
        prepare(Manifest.permission.RECORD_AUDIO)
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            compose.onNodeWithContentDescription("Start voice capture").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("00:01").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Recording").assertIsDisplayed()
            compose.onNodeWithContentDescription("Stop recording").performClick()
            compose.waitUntil(10_000) { session.state.value.attachments.any { it.status == AttachmentStatus.READY } }
            val item = session.state.value.attachments.single()
            assertEquals("audio/wav", item.mimeType)
            assertNull(item.messageId); assertTrue(session.state.value.messages.isEmpty())
            host.recreate()
            compose.onNodeWithText("Voice recording.wav").assertIsDisplayed()
            compose.onNodeWithContentDescription("Start voice capture").performClick()
            compose.onNodeWithContentDescription("Cancel recording").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Recording").fetchSemanticsNodes().isEmpty() }
            assertEquals(listOf(item.id), session.state.value.attachments.map { it.id })
        }
    }

    @Test fun cameraSelectionReturnsToChatWithUnsentImageAndRetainsPrompt() {
        prepare(Manifest.permission.CAMERA)
        session.editDraft("Synthetic camera prompt")
        compose.waitUntil { session.state.value.draft == "Synthetic camera prompt" }
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            compose.onNodeWithContentDescription("Add attachment").performClick()
            compose.onNodeWithText("Camera").performClick()
            compose.waitUntil(15_000) {
                runCatching { compose.onNodeWithText("Capture").assertIsEnabled() }.isSuccess
            }
            compose.onNodeWithText("Capture").performClick()
            compose.waitUntil(15_000) { session.state.value.attachments.any { it.mimeType == "image/jpeg" && it.status == AttachmentStatus.READY } }
            compose.onNodeWithText("Synthetic camera prompt").assertIsDisplayed()
            compose.onNodeWithContentDescription("Camera photo.jpg").assertIsDisplayed()
            assertTrue(session.state.value.messages.isEmpty())
            host.recreate()
            compose.onNodeWithContentDescription("Camera photo.jpg").assertIsDisplayed()
        }
    }

    @Test fun keyboardExpansionKeepsFocusAndCollapseKeepsDraft() {
        assumeTrue(context.packageName.endsWith(".secureqa"))
        compose.waitUntil(10_000) { session.state.value.ready }
        val previousEpoch = session.state.value.draftEpoch
        session.clear()
        compose.waitUntil { session.state.value.draftEpoch > previousEpoch &&
            session.state.value.draft.isEmpty() && session.state.value.attachments.isEmpty() }
        ActivityScenario.launch(MainActivity::class.java).use { host ->
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("composer-expanded").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("composer-input").assertIsFocused()
            if (InstrumentationRegistry.getArguments().getString("expectIme") == "true") {
                compose.waitUntil(10_000) {
                    var visible = false
                    host.onActivity {
                        visible = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
                    }
                    visible
                }
            }
            compose.onNodeWithTag("composer-input").performTextInput("Hello")
            compose.waitUntil { session.state.value.draft == "Hello" }
            // The session update precedes lifecycle-aware UI collection; wait for the rendered outcome.
            try {
                compose.waitUntil(5_000) {
                    compose.onAllNodes(hasContentDescription("Save message") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
                }
            } catch (failure: ComposeTimeoutException) {
                val snapshot = session.state.value
                val inputLength = compose.onNodeWithTag("composer-input").fetchSemanticsNode()
                    .config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].length
                throw AssertionError("Composer not ready: host=${host.state}, ready=${snapshot.ready}, " +
                    "draftLength=${snapshot.draft.length}, inputLength=$inputLength, " +
                    "attachments=${snapshot.attachments.size}, error=${snapshot.error}", failure)
            }
            compose.onNodeWithTag("composer-input").assertIsFocused()
            compose.onNodeWithContentDescription("Save message").assertIsDisplayed().assertIsEnabled()
            instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("composer-compact").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Hello").assertIsDisplayed()
            assertTrue(session.state.value.messages.isEmpty())
            compose.onNodeWithTag("composer-input").performClick()
            compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
            host.recreate()
            compose.onNodeWithText("Hello").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").performClick()
            compose.onNodeWithTag("composer-input").assertIsFocused()
            compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
        }
    }
}
