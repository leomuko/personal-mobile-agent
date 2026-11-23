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
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.attachments.model.AttachmentStatus
import dev.edgecompanion.core.conversation.model.ConversationState
import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.RequestScope
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
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
            val host = if (drawer) "drawer" else "full"
            compose.runOnIdle { floating.value = drawer }
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            // Focus and a selection arrive while the bar is still compact; the switch to the stacked
            // arrangement must move the same field, keeping its node, focus and selection.
            val field = compose.onNodeWithTag("composer-input").fetchSemanticsNode()
            compose.runOnUiThread {
                checkNotNull(field.config[SemanticsActions.RequestFocus].action).invoke()
                checkNotNull(field.config[SemanticsActions.SetSelection].action).invoke(1, 3, true)
            }
            compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").assertIsFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(1, 3)))
            assertEquals("$host: the field was recreated on expanding", field.id,
                compose.onNodeWithTag("composer-input").fetchSemanticsNode().id)
            assertFieldRowThenControls("composer-expanded", host)
            assertChipLabel(shown = true, "$host expanded, off")
            pressBackWhileEditingAndAwaitCompact(host)
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            compose.onNodeWithTag("composer-input").assertIsNotFocused()
            compose.onNodeWithText("Keep").assertIsDisplayed()
            assertChipLabel(shown = false, "$host idle again, off")
            // S5C-F2: leaving focus collapses the selection to its end (the framework's deselect on blur),
            // so the same field state shows as a cursor at 3; a recreated field would start at 0.
            compose.onNodeWithTag("composer-input").assert(SemanticsMatcher.expectValue(
                SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("Keep")))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, TextRange(3)))
            assertEquals("$host: the field was recreated on collapsing", field.id,
                compose.onNodeWithTag("composer-input").fetchSemanticsNode().id)
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
        val library = mutableStateOf<Boolean?>(null)
        var mic = false
        // S5C-F3: this is the S24-width guard; a narrower screen cannot give the composer 411 dp.
        val screenDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics
            .let { it.widthPixels / it.density }
        assumeTrue("Needs a screen at least 411 dp wide (this one is $screenDp dp)", screenDp >= 411f)
        // The S24's width at font scale 1.0 (the S24 itself runs at 0.9), so the one-row fit is pinned at 1.0.
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1f)) {
                Box(Modifier.width(411.dp)) { Fixture(state.value, onMic = { mic = true }, hasSources = library.value) }
            }
        }
        compose.onNodeWithContentDescription("Save message").assertIsNotEnabled()
        assertActionsAligned("composer-compact")
        assertOneRow()
        assertChipLabel(shown = false, "idle, off, unknown library")
        compose.runOnIdle { state.value = state.value.copy(draft = "Hello") }
        compose.onNodeWithContentDescription("Save message").assertIsEnabled()
        assertActionsAligned("composer-compact")
        assertOneRow()
        screenshot("composer-compact.png")
        // The disabled chip on a known-empty library keeps the one row too, with the hint below it.
        compose.runOnIdle { library.value = false; state.value = state.value.copy(draft = "") }
        compose.onNodeWithTag("sources-empty-hint").assertIsDisplayed()
        compose.onNodeWithTag("sources-scope-chip").assertIsNotEnabled()
        assertOneRow()
        assertChipLabel(shown = false, "idle, off, empty library")
        // Turned on, the chip shows its label again (the bar may then stack; that is R1's rule).
        compose.runOnIdle { library.value = true; state.value = state.value.copy(draftScope = RequestScope.SOURCES) }
        compose.onNodeWithTag("sources-scope-chip").assertIsOn()
        assertChipLabel(shown = true, "idle, on")
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
        assertFieldRowThenControls("composer-expanded", "attachments")
        compose.onNodeWithContentDescription("Synthetic.png").assertIsDisplayed()
        assertTrue("The attachment strip comes before the field row",
            bounds(hasContentDescription("Synthetic.png")).bottom <= bounds(hasTestTag("composer-input")).top + 1f)
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
        assertFieldRowThenControls("composer-expanded", "multiline")
    }

    @Test fun narrowRtlLargeTextSheetWrapsAndBackRetainsDraft() {
        val floating = mutableStateOf(false)
        val state = mutableStateOf(ConversationState(ready = true))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f), LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalSoftwareKeyboardController provides null) {
                Box(Modifier.width(320.dp)) { Fixture(state.value, drawer = floating.value) }
            }
        }
        // 320 dp at 2x with the 48 dp icon-only chip (off, idle) leaves about 88 dp for the field. The
        // R1 rule then decides by the field's one-line content: the empty field's placeholder (about
        // 250 dp at 2x) stacks the idle bar, while the short draft "Hi" (about 52 dp) keeps one row.
        // Editing always stacks.
        for (drawer in listOf(false, true)) {
            val host = (if (drawer) "drawer" else "full") + " 320dp 2x RTL"
            compose.runOnIdle { floating.value = drawer; state.value = ConversationState(ready = true) }
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            assertFieldRowThenControls("composer-compact", "$host idle, placeholder", rtl = true)
            compose.runOnIdle { state.value = state.value.copy(draft = SHORT_DRAFT) }
            compose.onNodeWithText(SHORT_DRAFT).assertIsDisplayed()
            compose.onNodeWithTag("composer-compact").assertIsDisplayed()
            assertOneRow(rtl = true, case = "$host idle, short draft")
            compose.onNodeWithTag("composer-input").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
            assertFieldRowThenControls("composer-expanded", "$host editing", rtl = true)
            pressBackWhileEditingAndAwaitCompact(host)
            compose.onNodeWithText(SHORT_DRAFT).assertIsDisplayed()
        }
        compose.onNodeWithContentDescription("Add attachment").performClick()
        listOf("Photos", "Camera", "Files").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        val photos = compose.onNodeWithText("Photos").fetchSemanticsNode().boundsInRoot
        val files = compose.onNodeWithText("Files").fetchSemanticsNode().boundsInRoot
        assertTrue(files.top > photos.bottom)
        screenshot("composer-sheet-large-rtl.png")
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText(SHORT_DRAFT).assertIsDisplayed()
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
        if (compact.isFailure) fail(backCollapseDiagnostic(host, "gave up", elapsed))
        // UIF-F7: waitUntil can return late on a busy device; the budget applies to the success path too.
        assertTrue(backCollapseDiagnostic(host, "collapsed", elapsed), elapsed <= BACK_COLLAPSE_BUDGET_MS)
    }

    private fun backCollapseDiagnostic(host: String, outcome: String, elapsed: Long): String {
        val expanded = runCatching { compose.onAllNodesWithTag("composer-expanded").fetchSemanticsNodes().isNotEmpty() }
        val focused = runCatching {
            compose.onNodeWithTag("composer-input").fetchSemanticsNode().config.getOrElse(SemanticsProperties.Focused) { false }
        }
        val activity = compose.runOnUiThread {
            with(compose.activity) { "finishing=$isFinishing, windowFocus=${hasWindowFocus()}, " +
                "backClaimed=${onBackPressedDispatcher.hasEnabledCallbacks()}" }
        }
        return "$host: Back did not return the composer to compact within the $BACK_COLLAPSE_BUDGET_MS ms " +
            "budget ($outcome after $elapsed ms; expanded=${expanded.getOrElse { it.javaClass.simpleName }}, " +
            "inputFocused=${focused.getOrElse { it.javaClass.simpleName }}, $activity)"
    }

    private fun assertActionsAligned(tag: String) {
        val container = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val mic = compose.onNodeWithContentDescription("Start voice capture").fetchSemanticsNode().boundsInRoot
        val send = compose.onNodeWithContentDescription("Save message").fetchSemanticsNode().boundsInRoot
        assertEquals(mic.center.y, send.center.y, 1f)
        assertTrue(mic.right <= send.left)
        assertTrue(container.contains(mic.center) && container.contains(send.center))
    }

    private fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot

    /** Icon-only chip (idle and off) is its 48 dp touch target; the labelled chip is clearly wider. */
    private fun assertChipLabel(shown: Boolean, case: String) {
        val width = with(compose.density) { bounds(hasTestTag("sources-scope-chip")).width.toDp() }
        if (shown) assertTrue("$case: label expected, chip is $width wide", width >= 64.dp)
        else assertTrue("$case: icon only expected, chip is $width wide", width >= 47.dp && width <= 49.dp)
    }

    /** Compact at a normal width: `+`, chip, field, mic and send share one row, in that order. */
    private fun assertOneRow(rtl: Boolean = false, case: String = "") {
        val add = bounds(hasContentDescription("Add attachment"))
        val chip = bounds(hasTestTag("sources-scope-chip"))
        val field = bounds(hasTestTag("composer-input"))
        val mic = bounds(hasContentDescription("Start voice capture"))
        val send = bounds(hasContentDescription("Save message") or hasContentDescription("Stop response"))
        for (control in listOf(add, chip, mic, send)) assertTrue("$case one row: $control vs $field",
            control.top < field.bottom && field.top < control.bottom)
        // Left to right on screen; RTL mirrors the start-to-end order.
        val ordered = listOf(add, chip, field, mic, send).let { if (rtl) it.reversed() else it }
        ordered.zipWithNext().forEach { (left, right) ->
            assertTrue("$case one row order: $ordered", left.right <= right.left + 1f)
        }
    }

    /**
     * S5-F3 stacked arrangement (expanded, or a compact bar that cannot fit): the field spans the
     * composer's inner width on its own row; below it, `+` and the chip sit at the start and mic and
     * send at the end. Start and end follow the layout direction. 8 dp is the layout's inner padding.
     */
    private fun assertFieldRowThenControls(tag: String, case: String, rtl: Boolean = false) {
        val inset = with(compose.density) { 8.dp.toPx() }
        val container = bounds(hasTestTag(tag))
        val field = bounds(hasTestTag("composer-input"))
        val add = bounds(hasContentDescription("Add attachment"))
        val chip = bounds(hasTestTag("sources-scope-chip"))
        val mic = bounds(hasContentDescription("Start voice capture"))
        val send = bounds(hasContentDescription("Save message") or hasContentDescription("Stop response"))
        assertEquals("$case: field width", container.width - 2 * inset, field.width, 2f)
        assertEquals("$case: field start", container.left + inset, field.left, 2f)
        for (control in listOf(add, chip, mic, send)) {
            assertTrue("$case: controls below the field ($control, field $field)", control.top >= field.bottom - 1f)
        }
        assertEquals("$case: one controls row", add.center.y, send.center.y, 1f)
        assertEquals("$case: one controls row", chip.center.y, send.center.y, 1f)
        val ordered = if (rtl) listOf(send, mic, chip, add) else listOf(add, chip, mic, send)
        ordered.zipWithNext().forEach { (left, right) -> assertTrue("$case: order $ordered", left.right <= right.left + 1f) }
        val start = if (rtl) container.right - inset - add.right else add.left - container.left - inset
        val end = if (rtl) send.left - container.left - inset else container.right - inset - send.right
        assertEquals("$case: + at the start", 0f, start, 2f)
        assertEquals("$case: send at the end", 0f, end, 2f)
    }

    @Composable private fun Fixture(state: ConversationState, drawer: Boolean = false, onMic: () -> Unit = {},
        hasSources: Boolean? = null,
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
                onRemoveAttachment = onRemove, onPreviewAttachment = onPreview, hasSources = hasSources,
                thumbnail = { _, modifier -> Image(bitmap, null, modifier, contentScale = ContentScale.Crop) })
        }
    }

    private companion object {
        /** Test-environment precondition only: the window must hold input focus before a key is sent. */
        const val WINDOW_FOCUS_TIMEOUT_MS = 5_000L
        /** Fits one row at 320 dp and 2x beside the icon-only chip (about 52 dp of about 88 dp). */
        const val SHORT_DRAFT = "Hi"
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
