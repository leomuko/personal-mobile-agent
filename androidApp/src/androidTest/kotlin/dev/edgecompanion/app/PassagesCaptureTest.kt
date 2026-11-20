package dev.edgecompanion.app

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.sources.data.SourceLibrary
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.conversation.presentation.ConsultedRead
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import dev.edgecompanion.ui.sources.screen.SourceSpanRoute
import dev.edgecompanion.ui.sources.viewmodel.SourcesViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * S24 review evidence for the S5 "Planned S24 captures" (docs/P2_PASSAGES_UI_RESULTS.md, items 1-9).
 * Renders the real shared CompanionScreen and Sources detail with synthetic state only: no model,
 * network, database or personal data. Nothing here asserts pixels; each capture first checks that the
 * node it documents exists. PNGs are written only when `-e passagesCaptureRunId <id>` is given
 * (scripts/verify-chat-context.sh ui), into this app's no_backup storage, for the script to pull.
 */
class PassagesCaptureTest {
    // The test activity is given MainActivity's window setup (edge-to-edge, adjustResize) before content.
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val ref = ConsultedPassage(1, 2, 3, 4, 20, 46)
    private val title = "Synthetic project notes"
    private val preview = SourceSpanPreview(title, "Review starts at noon on Friday.")
    private val user = Message(1, "When does the synthetic review start?", 0, scope = RequestScope.SOURCES)
    private val answer = Message(2, "The synthetic review starts at noon on Friday.", 0, MessageRole.ASSISTANT, user.id)
    private val ready = ConversationState(ready = true, assistant = AssistantState(enabled = true))
    private val grounded = ready.copy(messages = listOf(user, answer),
        attempts = listOf(AssistantAttempt("done", user.id, 0, TurnStatus.COMPLETED, consulted = listOf(ref))))
    private val typed = "Summarize the synthetic project notes"

    /** MainActivity calls enableEdgeToEdge() and declares adjustResize; give the test window the same. */
    @org.junit.Before fun matchHostWindow() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    /** Item 1: both hosts at the device's default width, chip off and on, idle and typing. */
    @Test fun item1ComposerAtDefaultWidthInBothHosts() {
        val state = mutableStateOf(ready)
        val drawer = mutableStateOf(false)
        val tester = StateRestorationTester(compose)
        tester.setContent {
            Frame(drawer.value) {
                Host(state.value, drawer.value, onToggle = { on -> state.value = state.value.copy(draftScope = scopeOf(on)) })
            }
        }
        for (floating in listOf(false, true)) {
            val host = hostName(floating)
            for (on in listOf(false, true)) {
                compose.runOnIdle { drawer.value = floating; state.value = ready.copy(draftScope = scopeOf(on)) }
                idle()
                chip().apply { if (on) assertIsOn() else assertIsOff() }
                capture(1, "$host-${onOff(on)}-idle")
                typing(state)
                capture(1, "$host-${onOff(on)}-typing")
                stopTyping(state)
            }
        }
        // Turned on in the full app with a tap, then shown by the drawer from the same shared state.
        compose.runOnIdle { drawer.value = false; state.value = ready }
        idle()
        chip().assertIsOff().performClick().assertIsOn()
        compose.runOnIdle { drawer.value = true }
        chip().assertIsOn()
        capture(1, "drawer-on-after-host-switch")
        // Compose-level stand-in for reopening: saved-state recreation with the stored draft scope.
        compose.runOnIdle { drawer.value = false }
        tester.emulateSavedInstanceStateRestore()
        chip().assertIsOn()
        capture(1, "full-on-after-recreation")
    }

    /** Item 2 (and S5-F6): last source deleted with the chip on, then turned off; unknown shows no hint. */
    @Test fun item2EmptyLibraryChip() {
        val scope = mutableStateOf(RequestScope.SOURCES)
        val drawer = mutableStateOf(false)
        val available = mutableStateOf<Boolean?>(false)
        compose.setContent {
            Frame(drawer.value) {
                Host(ready.copy(draftScope = scope.value), drawer.value, hasSources = available.value,
                    onToggle = { on -> scope.value = scopeOf(on) })
            }
        }
        for (floating in listOf(false, true)) {
            val host = hostName(floating)
            compose.runOnIdle { drawer.value = floating; available.value = false; scope.value = RequestScope.SOURCES }
            chip().assertIsOn().assertIsEnabled()
            compose.onNodeWithTag("sources-empty-hint").assertIsDisplayed()
            capture(2, "$host-empty-chip-on")
            chip().performClick().assertIsOff().assertIsNotEnabled()
            compose.onNodeWithText("Add a source to use this").assertIsDisplayed()
            capture(2, "$host-empty-chip-off-hint")
        }
        compose.runOnIdle { drawer.value = false; available.value = null; scope.value = RequestScope.ORDINARY }
        chip().assertIsOff().assertIsNotEnabled()
        compose.onNodeWithTag("sources-empty-hint").assertDoesNotExist()
        capture(2, "full-unknown-availability-no-hint")
    }

    /** Item 3: a source-scoped turn with its label, and Passages consulted collapsed and expanded. */
    @Test fun item3PassagesConsultedCollapsedAndExpanded() {
        val drawer = mutableStateOf(false)
        compose.setContent {
            Frame(drawer.value) { Host(grounded, drawer.value, observeConsulted = { flowOf(ConsultedRead.Loaded(listOf(preview))) }) }
        }
        for (floating in listOf(false, true)) {
            val host = hostName(floating)
            compose.runOnIdle { drawer.value = floating }
            compose.onNodeWithText("From my sources").assertIsDisplayed()
            compose.onNodeWithText("Passages consulted").assertIsDisplayed()
            if (compose.onAllNodesWithTag("consulted-source").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Passages consulted").performClick()
            }
            compose.onNodeWithTag("consulted-source").assertDoesNotExist()
            capture(3, "$host-passages-collapsed")
            compose.onNodeWithText("Passages consulted").performClick()
            compose.onNodeWithTag("consulted-source").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(title, substring = true).assertExists()
            capture(3, "$host-passages-expanded")
        }
    }

    /** Item 4: the Sources detail opened from a passage, with the exact span highlighted, in each host. */
    @Test fun item4SourceDetailAtTheHighlightedSpan() {
        val drawer = mutableStateOf(false)
        val sources = SourcesViewModel(SyntheticSpanLibrary(ref.span))
        try {
            compose.setContent {
                Frame(drawer.value) {
                    Host(grounded, drawer.value, observeConsulted = { flowOf(ConsultedRead.Loaded(listOf(preview))) },
                        sourceSpanContent = { span, modifier -> SourceSpanRoute(sources, span, modifier) })
                }
            }
            for (floating in listOf(false, true)) {
                compose.runOnIdle { drawer.value = floating }
                if (compose.onAllNodesWithTag("consulted-source").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithText("Passages consulted").performClick()
                }
                compose.onNodeWithTag("consulted-source").performScrollTo().performClick()
                compose.onNodeWithTag("source-span-detail").assertIsDisplayed()
                compose.onNode(hasText(preview.text) and
                    (hasTestTag("source-highlight") or hasAnyAncestor(hasTestTag("source-highlight"))),
                    useUnmergedTree = true).assertIsDisplayed()
                capture(4, "${hostName(floating)}-source-detail-highlight")
                compose.onNodeWithContentDescription("Back to chat").performClick()
            }
        } finally { compose.runOnIdle { sources.viewModelScope.cancel() } }
    }

    /** Item 5: the same answer after its source is deleted, and an ordinary answer with no row. */
    @Test fun item5DeletedSourceAndOrdinaryAnswer() {
        val drawer = mutableStateOf(false)
        val state = mutableStateOf(grounded)
        compose.setContent {
            Frame(drawer.value) { Host(state.value, drawer.value, observeConsulted = { flowOf(ConsultedRead.Loaded(listOf(null))) }) }
        }
        for (floating in listOf(false, true)) {
            compose.runOnIdle { drawer.value = floating }
            if (compose.onAllNodesWithText("Source deleted").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithText("Passages consulted").performClick()
            }
            compose.onNodeWithText("Source deleted").performScrollTo().assertIsDisplayed().assertHasNoClickAction()
            capture(5, "${hostName(floating)}-source-deleted")
        }
        val ordinaryUser = Message(1, "Say hello to the synthetic team.", 0)
        val ordinaryAnswer = Message(2, "Hello, synthetic team.", 0, MessageRole.ASSISTANT, ordinaryUser.id)
        compose.runOnIdle {
            drawer.value = false
            state.value = ready.copy(messages = listOf(ordinaryUser, ordinaryAnswer),
                attempts = listOf(AssistantAttempt("ordinary", ordinaryUser.id, 0, TurnStatus.COMPLETED)))
        }
        compose.onNodeWithText("Hello, synthetic team.").assertIsDisplayed()
        compose.onNodeWithText("Passages consulted").assertDoesNotExist()
        capture(5, "full-ordinary-no-passages")
    }

    /** Item 6: the four source-scoped no-answer messages; Retry only on the temporary ones. */
    @Test fun item6NoAnswerMessages() {
        val state = mutableStateOf(ready.copy(messages = listOf(user)))
        compose.setContent { Frame(false) { Host(state.value, false) } }
        for ((reason, name, retry) in listOf(
            Triple(SourcesNoAnswer.NO_MATCH, "no-match", false),
            Triple(SourcesNoAnswer.NOT_INDEXED, "not-indexed-retry", true),
            Triple(SourcesNoAnswer.RETRIEVAL_INCOMPLETE, "retrieval-incomplete-retry", true),
            Triple(SourcesNoAnswer.PASSAGES_UNUSABLE, "passages-unusable", false),
        )) {
            compose.runOnIdle { state.value = state.value.copy(attempts = listOf(
                AssistantAttempt("result", user.id, 0, TurnStatus.COMPLETED, sourcesResult = reason))) }
            compose.onNodeWithTag("sources-no-answer").assertIsDisplayed()
            val button = compose.onNodeWithText("Retry response")
            if (retry) button.assertIsDisplayed() else button.assertDoesNotExist()
            capture(6, "full-$name")
        }
    }

    /** Item 7: large font (2x) in both hosts, with the composer and the expanded passage list. */
    @Test fun item7LargeFontInBothHosts() {
        val drawer = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, LARGE_FONT)) {
                Frame(drawer.value) {
                    Host(grounded.copy(draft = "Draft", draftScope = RequestScope.SOURCES), drawer.value,
                        observeConsulted = { flowOf(ConsultedRead.Loaded(listOf(preview))) })
                }
            }
        }
        for (floating in listOf(false, true)) {
            compose.runOnIdle { drawer.value = floating }
            if (compose.onAllNodesWithTag("consulted-source").fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithText("Passages consulted").performScrollTo().performClick()
            }
            compose.onNodeWithTag("consulted-source").performScrollTo().assertIsDisplayed()
            chip().assertIsDisplayed().assertIsOn()
            compose.onNodeWithTag("composer-input").assertIsDisplayed()
            capture(7, "${hostName(floating)}-large-font-passages-expanded", fontScale = LARGE_FONT)
        }
    }

    /** Item 8: item 1's composer states at a narrow width; the actual width is in the capture metadata. */
    @Test fun item8ComposerAtNarrowWidth() {
        val state = mutableStateOf(ready)
        val drawer = mutableStateOf(false)
        compose.setContent { Frame(drawer.value, width = NARROW) { Host(state.value, drawer.value) } }
        for (floating in listOf(false, true)) for (on in listOf(false, true)) {
            val name = "${hostName(floating)}-${onOff(on)}"
            compose.runOnIdle { drawer.value = floating; state.value = ready.copy(draftScope = scopeOf(on)) }
            idle()
            capture(8, "$name-idle-narrow")
            typing(state)
            capture(8, "$name-typing-narrow")
            stopTyping(state)
        }
    }

    /**
     * Item 9, Compose-level stand-in: the drawer with a passage open, then the new-conversation request
     * that MainActivity.onNewIntent raises on mascot re-entry. Home and the real re-entry are not driven.
     */
    @Test fun item9DrawerPassageThenChatAfterReentryRequest() {
        val request = mutableIntStateOf(0)
        val sources = SourcesViewModel(SyntheticSpanLibrary(ref.span))
        try {
            compose.setContent {
                Frame(true) {
                    Host(grounded, drawer = true, conversationRequest = request.intValue,
                        observeConsulted = { flowOf(ConsultedRead.Loaded(listOf(preview))) },
                        sourceSpanContent = { span, modifier -> SourceSpanRoute(sources, span, modifier) })
                }
            }
            compose.onNodeWithText("Passages consulted").performClick()
            compose.onNodeWithTag("consulted-source").performScrollTo().performClick()
            compose.onNodeWithTag("source-span-detail").assertIsDisplayed()
            capture(9, "drawer-passage-open")
            compose.runOnIdle { request.intValue++ }
            compose.onNodeWithTag("source-span-detail").assertDoesNotExist()
            compose.onNodeWithTag("composer-input").assertIsDisplayed()
            capture(9, "drawer-chat-after-reentry-request")
        } finally { compose.runOnIdle { sources.viewModelScope.cancel() } }
    }

    private fun chip() = compose.onNodeWithContentDescription("My sources, answer from my sources only")
    private fun scopeOf(on: Boolean) = if (on) RequestScope.SOURCES else RequestScope.ORDINARY
    private fun onOff(on: Boolean) = if (on) "on" else "off"
    private fun hostName(drawer: Boolean) = if (drawer) "drawer" else "full"

    private fun idle() {
        compose.onNodeWithTag("composer-compact").assertIsDisplayed()
        compose.onNodeWithTag("composer-input").assertIsNotFocused()
    }

    /** Typing: a synthetic draft in a focused field, which expands the composer as on the device. */
    private fun typing(state: MutableState<ConversationState>) {
        compose.runOnIdle { state.value = state.value.copy(draft = typed) }
        compose.onNodeWithTag("composer-input").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("composer-expanded").assertIsDisplayed()
        compose.onNodeWithText(typed).assertIsDisplayed()
    }

    /** Leaves the field and clears the draft, so the next idle capture starts unfocused and empty. */
    private fun stopTyping(state: MutableState<ConversationState>) {
        compose.runOnIdle { focusSink?.invoke(); state.value = state.value.copy(draft = "") }
        compose.onNodeWithTag("composer-input").assertIsNotFocused()
    }

    private var focusSink: (() -> Unit)? = null
    /** Status-bar top, navigation-bar bottom and IME bottom insets (px) at the last composition. */
    private var insets = Triple(0, 0, 0)

    /**
     * Frames the content as MainActivity does: system-bar and IME padding on the host box, and a
     * surface of full padded height (full app) or 86 % of it with rounded top corners (drawer). The
     * drawer's backdrop is a plain grey stand-in for whatever is behind the translucent drawer window.
     */
    @Composable private fun Frame(drawer: Boolean, width: Dp? = null, content: @Composable () -> Unit) {
        val focus = LocalFocusManager.current
        val density = LocalDensity.current
        val bars = Triple(WindowInsets.statusBars.getTop(density), WindowInsets.navigationBars.getBottom(density),
            WindowInsets.ime.getBottom(density))
        SideEffect { focusSink = { focus.clearFocus(force = true) }; insets = bars }
        CompositionLocalProvider(LocalSoftwareKeyboardController provides null) {
            CompanionTheme {
                Box((if (width == null) Modifier.fillMaxSize() else Modifier.width(width).fillMaxHeight())
                    .testTag(FRAME)
                    .background(if (drawer) Color(0xFF5F6662) else MaterialTheme.colorScheme.background)
                    .statusBarsPadding().navigationBarsPadding().imePadding(),
                    contentAlignment = Alignment.BottomCenter) {
                    Surface(Modifier.fillMaxWidth().then(if (drawer) Modifier.fillMaxHeight(0.86f) else Modifier.fillMaxHeight()),
                        shape = if (drawer) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(0.dp)) {
                        content()
                    }
                }
            }
        }
    }

    @Composable private fun Host(
        state: ConversationState, drawer: Boolean, hasSources: Boolean? = true, conversationRequest: Int = 0,
        onToggle: (Boolean) -> Unit = {},
        observeConsulted: (List<ConsultedPassage>) -> Flow<ConsultedRead> = { flowOf(ConsultedRead.Unavailable) },
        sourceSpanContent: (@Composable (SourceSpan, Modifier) -> Unit)? = null,
    ) {
        CompanionScreen(state, drawer, false, "Synthetic", {}, {}, {}, {}, {}, {}, {}, {},
            conversationRequest = conversationRequest, hasSources = hasSources, onSourcesOnly = onToggle,
            observeConsulted = observeConsulted, sourceSpanContent = sourceSpanContent)
    }

    /**
     * Exports only this test's synthetic Compose frame (never a real app window or the system UI) as
     * no_backup/passages-captures/<runId>/NN-name.png, and records its measured size in <runId>.json.
     */
    private fun capture(item: Int, name: String, fontScale: Float = 1f) {
        compose.waitForIdle()
        val node = compose.onNodeWithTag(FRAME).assertExists()
        val runId = InstrumentationRegistry.getArguments().getString(RUN_ARGUMENT) ?: return
        assertTrue("Invalid capture run ID", RUN_ID.matches(runId))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.noBackupFilesDir, "passages-captures")
        val directory = File(root, runId).apply { mkdirs() }
        val file = File(directory, String.format(Locale.ROOT, "%02d-%s.png", item, name))
        assertTrue("Capture names must match NN-name.png: ${file.name}", NAME.matches(file.name))
        assertFalse("Duplicate capture ${file.name}", file.exists())
        val bitmap = node.captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        // The device-side size and digest let the script detect a truncated or altered pull.
        val bytes = file.readBytes()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.ROOT, it) }
        val density = context.resources.displayMetrics.density
        val metadataFile = File(root, "$runId.json")
        val metadata = if (metadataFile.exists()) JSONObject(metadataFile.readText())
            else JSONObject().put("schema", 1).put("runId", runId).put("captures", JSONObject())
        metadata.getJSONObject("captures").put(file.name, JSONObject()
            .put("item", item).put("widthPx", bitmap.width).put("heightPx", bitmap.height)
            .put("density", density.toDouble()).put("widthDp", bitmap.width / density.toDouble())
            .put("fontScale", fontScale.toDouble()).put("bytes", bytes.size).put("sha256", sha256)
            .put("statusBarTopPx", insets.first).put("navigationBarBottomPx", insets.second).put("imeBottomPx", insets.third))
        val temporary = File(root, "$runId.json.tmp")
        temporary.writeText(metadata.toString(2))
        assertTrue(temporary.renameTo(metadataFile))
    }

    private class SyntheticSpanLibrary(private val span: SourceSpan) : SourceLibrary {
        override val sourceChanges = MutableStateFlow(0L)
        override suspend fun sourceAtSpan(span: SourceSpan): SourceAtSpan? =
            if (span == this.span) SourceAtSpan("Synthetic project notes", listOf("Synthetic planning notes for the review."),
                "Review starts at noon on Friday.", listOf("Bring the synthetic checklist.")) else null
        override suspend fun listSources(query: String, after: Long) = SourcePage(emptyList(), null)
        override suspend fun sourceText(id: Long, after: Long): SourceTextPage = error("Unexpected source read")
        override suspend fun addTextSource(title: String, text: String): SourceSummary = error("Unexpected source write")
        override suspend fun addUtf8Source(title: String, bytes: ByteArray): SourceSummary = error("Unexpected source import")
        override suspend fun renameSource(id: Long, title: String) { error("Unexpected source rename") }
        override suspend fun deleteSource(id: Long): Boolean = error("Unexpected source deletion")
    }

    private companion object {
        const val FRAME = "capture-frame"
        const val RUN_ARGUMENT = "passagesCaptureRunId"
        const val LARGE_FONT = 2f
        val NARROW = 320.dp
        val RUN_ID = Regex("[A-Za-z0-9-]{1,96}")
        val NAME = Regex("[0-9]{2}-[a-z0-9-]+\\.png")
    }
}
