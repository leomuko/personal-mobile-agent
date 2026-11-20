package dev.edgecompanion.app

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.attachments.model.AttachmentStatus
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class ComposerLayoutTest {
    // Same ComponentActivity that createComposeRule() launches; the activity is needed for the Back diagnostics.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun delayedDraftAcknowledgementsDoNotReplaceNewerTyping() {
        val state = mutableStateOf(ConversationState(ready = true))
        val edits = mutableListOf<String>()
        compose.setContent { Fixture(state.value, onDraft = { edits += it }) }
        compose.onNodeWithTag("composer-input").performTextInput("H")
        compose.onNodeWithTag("composer-input").performTextInput("ello")
        compose.onNodeWithTag("composer-input").assertTextEquals("Hello")
        compose.onNodeWithContentDescription("Save message").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(draft = "H") }
        compose.onNodeWithTag("composer-input").assertTextEquals("Hello")
        compose.runOnIdle { state.value = state.value.copy(draft = "Hello") }
        compose.onNodeWithContentDescription("Save message").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("H", "Hello"), edits) }
        compose.runOnIdle { state.value = state.value.copy(draft = "External draft") }
        compose.onNodeWithTag("composer-input").assertTextEquals("External draft")
        compose.onNodeWithTag("composer-input").performTextInput(" pending")
        compose.runOnIdle { state.value = state.value.copy(draft = "", draftEpoch = 1) }
        compose.onNodeWithTag("composer-input").assert(SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
    }

    @Test fun focusWithoutSoftwareKeyboardExpandsAndBackPreservesDraftInBothHosts() {
        val floating = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalSoftwareKeyboardController provides null) {
                Fixture(ConversationState(ready = true, draft = "Keep"), drawer = floating.value)
            }
        }
        for (drawer in listOf(false, true)) {
            compose.runOnIdle { floating.value = drawer }
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").assertIsFocused()
            pressBackWhileEditingAndAwaitCompact(if (drawer) "drawer" else "full")
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").assertIsNotFocused()
            compose.onNodeWithText("Keep").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
            compose.onNodeWithContentDescription("Add attachment").performClick()
            compose.onNodeWithText("Photos").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
        }
    }

    @Test fun compactMicIsInsideAndBesideSendForEmptyAndShortDrafts() {
        val state = mutableStateOf(ConversationState(ready = true))
        var mic = false
        compose.setContent { Fixture(state.value, onMic = { mic = true }) }
        compose.onNodeWithContentDescription("Save message").assertIsNotEnabled()
        assertActionsAligned("composer-compact")
        compose.runOnIdle { state.value = state.value.copy(draft = "Hello") }
        compose.onNodeWithContentDescription("Save message").assertIsEnabled()
        assertActionsAligned("composer-compact")
        screenshot("composer-compact.png")
        compose.onNodeWithContentDescription("Start voice capture").performClick()
        compose.runOnIdle { assertTrue(mic) }
    }

    @Test fun mixedTilesExpandComposerAndRemovalDoesNotOpenPreview() {
        val photo = Attachment("image", "Synthetic.png", "image/png", 512, AttachmentStatus.READY)
        val document = Attachment("document", "Brief.pdf", "application/pdf", 1024, AttachmentStatus.READY)
        val audio = Attachment("audio", "Voice.wav", "audio/wav", 2048, AttachmentStatus.READY)
        val state = mutableStateOf(ConversationState(ready = true, draft = "Compare these", attachments = listOf(photo, document, audio)))
        var preview: String? = null
        compose.setContent { Fixture(state.value, onPreview = { preview = it.id }, onRemove = { id ->
            state.value = state.value.copy(attachments = state.value.attachments.filterNot { it.id == id })
        }) }
        assertActionsAligned("composer-expanded")
        compose.onNodeWithContentDescription("Synthetic.png").assertIsDisplayed()
        compose.onNodeWithText("Synthetic.png").assertDoesNotExist()
        screenshot("composer-mixed.png")
        compose.onNodeWithContentDescription("Remove Synthetic.png").performClick()
        compose.runOnIdle { assertNull(preview); assertEquals(2, state.value.attachments.size) }
        compose.onNodeWithText("Brief.pdf").performClick()
        compose.runOnIdle { assertEquals("document", preview) }
        compose.onNodeWithText("Voice.wav").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("audio", preview) }
        compose.onNodeWithText("Compare these").assertIsDisplayed()
    }

    @Test fun pendingAndFailedItemsKeepSendDisabled() {
        val item = Attachment("pending", "Pending.pdf", "application/pdf")
        val state = mutableStateOf(ConversationState(ready = true, draft = "Wait", attachments = listOf(item)))
        compose.setContent { Fixture(state.value) }
        compose.onNodeWithContentDescription("Save message").assertIsNotEnabled()
        compose.runOnIdle { state.value = state.value.copy(attachments = listOf(item.copy(status = AttachmentStatus.FAILED))) }
        compose.onNodeWithContentDescription("Save message").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Remove Pending.pdf").assertIsEnabled()
        compose.runOnIdle { state.value = state.value.copy(attachments = listOf(item.copy(status = AttachmentStatus.READY))) }
        compose.onNodeWithContentDescription("Save message").assertIsEnabled()
    }

    @Test fun multilineAndSentAttachmentsHaveNoDraftRemovalControls() {
        val item = Attachment("sent", "Sent.pdf", "application/pdf", 10, AttachmentStatus.READY, messageId = 1)
        compose.setContent { Fixture(ConversationState(ready = true, draft = "Line one\nLine two",
            messages = listOf(Message(1, "", 0)), attachments = listOf(item))) }
        compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
        compose.onNodeWithText("Sent.pdf").assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove Sent.pdf").assertDoesNotExist()
        assertActionsAligned("composer-expanded")
    }

    @Test fun narrowRtlLargeTextSheetWrapsAndBackRetainsDraft() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl) {
                Box(Modifier.width(320.dp)) { Fixture(ConversationState(ready = true, draft = "Keep"), drawer = true) }
            }
        }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        listOf("Photos", "Camera", "Files").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        val photos = compose.onNodeWithText("Photos").fetchSemanticsNode().boundsInRoot
        val files = compose.onNodeWithText("Files").fetchSemanticsNode().boundsInRoot
        assertTrue(files.top > photos.bottom)
        screenshot("composer-sheet-large-rtl.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Keep").assertIsDisplayed()
        compose.onNodeWithTag("composer-compact").assertIsDisplayed()
    }

    /**
     * Sends a real Back key while the field is focused and requires the compact composer within a
     * 1 s budget (the collapse is synchronous, so the original test asserted it at the next idle).
     * Preconditions: the test window has input focus (key injection reaches only the focused window)
     * and the composer has claimed Back while editing. On failure the message records which link
     * of the chain broke, which the S24 run on 2026-11-20 could not tell apart.
     */
    private fun pressBackWhileEditingAndAwaitCompact(host: String) {
        compose.waitUntil(WINDOW_FOCUS_TIMEOUT_MS) { compose.runOnUiThread { compose.activity.hasWindowFocus() } }
        assertTrue("$host: editing must claim Back before the key is sent",
            compose.runOnUiThread { compose.activity.onBackPressedDispatcher.hasEnabledCallbacks() })
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        val sentAt = SystemClock.uptimeMillis()
        val compact = runCatching {
            compose.waitUntil(BACK_COLLAPSE_BUDGET_MS) { compose.onAllNodesWithTag("composer-compact").fetchSemanticsNodes().isNotEmpty() }
        }
        val elapsed = SystemClock.uptimeMillis() - sentAt
        if (compact.isFailure) {
            val expanded = runCatching { compose.onAllNodesWithTag("composer-expanded").fetchSemanticsNodes().isNotEmpty() }
            val focused = runCatching {
                compose.onNodeWithTag("composer-input").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
            }
            val activity = compose.runOnUiThread {
                with(compose.activity) { "finishing=$isFinishing, windowFocus=${hasWindowFocus()}, " +
                    "backClaimed=${onBackPressedDispatcher.hasEnabledCallbacks()}" }
            }
            fail("$host: Back did not return the composer to compact within the $BACK_COLLAPSE_BUDGET_MS ms " +
                "budget (gave up after $elapsed ms; expanded=${expanded.getOrElse { it.javaClass.simpleName }}, " +
                "inputFocused=${focused.getOrElse { it.javaClass.simpleName }}, $activity)")
        }
    }

    private fun assertActionsAligned(tag: String) {
        val container = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithContentDescription("Start voice capture").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Save message").fetchSemanticsNode().boundsInRoot
        assertEquals(mic.center.y, send.center.y, 1f)
        assertTrue(mic.right <= send.left)
        assertTrue(container.contains(mic.center) && container.contains(send.center))
    }

    @Composable private fun Fixture(state: ConversationState, drawer: Boolean = false, onMic: () -> Unit = {},
        onRemove: (String) -> Unit = {}, onPreview: (Attachment) -> Unit = {}, onDraft: (String) -> Unit = {}) {
        val bitmap = remember {
            Bitmap.createBitmap(160, 160, Bitmap.Config.ARGB_8888).apply {
                val canvas = android.graphics.Canvas(this)
                canvas.drawColor(android.graphics.Color.WHITE)
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.rgb(26, 102, 82) }
                canvas.drawRect(12f, 16f, 148f, 60f, paint)
                paint.color = android.graphics.Color.DKGRAY
                (0..4).forEach { canvas.drawRect(12f, 78f + it * 14, 128f, 82f + it * 14, paint) }
            }.asImageBitmap()
        }
        CompanionTheme {
            CompanionScreen(state, drawer, false, "Synthetic", onDraft, {}, onMic, {}, {}, {}, {}, {},
                onRemoveAttachment = onRemove, onPreviewAttachment = onPreview,
                thumbnail = { _, modifier -> Image(bitmap, null, modifier, contentScale = ContentScale.Crop) })
        }
    }

    private companion object {
        /** Test-environment precondition only: the window must hold input focus before a key is sent. */
        const val WINDOW_FOCUS_TIMEOUT_MS = 5_000L
        /** Collapse after Back is synchronous; a delay a user would notice must fail. */
        const val BACK_COLLAPSE_BUDGET_MS = 1_000L
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use {
            val bitmap = if (name.contains("sheet")) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                else compose.onRoot().captureToImage().asAndroidBitmap()
            checkNotNull(bitmap).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
