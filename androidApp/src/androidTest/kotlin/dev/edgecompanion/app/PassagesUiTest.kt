package dev.edgecompanion.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PassagesUiTest {
    @get:Rule val compose = createComposeRule()
    private val ref = ConsultedPassage(1, 2, 3, 4, 20, 29)
    private val user = Message(1, "When is the synthetic training run?", 0, scope = RequestScope.SOURCES)
    private val answer = Message(2, "The synthetic training run starts at noon.", 0, MessageRole.ASSISTANT, user.id)
    private val ready = ConversationState(ready = true, assistant = AssistantState(enabled = true))
    private val grounded = ready.copy(messages = listOf(user, answer),
        attempts = listOf(AssistantAttempt("done", user.id, 0, TurnStatus.COMPLETED, consulted = listOf(ref))))

    @Test fun chipSwitchStateFollowsTheSharedDraftAcrossHostsAndRecreation() {
        val state = mutableStateOf(ready.copy(draft = "Synthetic draft"))
        val drawer = mutableStateOf(false)
        val tester = StateRestorationTester(compose)
        var sent: RequestScope? = null
        tester.setContent {
            Host(state.value, drawer.value, onToggle = { on ->
                state.value = state.value.copy(draftScope = if (on) RequestScope.SOURCES else RequestScope.ORDINARY)
            }, onSend = { sent = state.value.draftScope })
        }
        chip().assertIsOff().assertIsEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .performClick().assertIsOn()
        compose.onNodeWithContentDescription("Save message").performClick()
        compose.runOnIdle { assertEquals(RequestScope.SOURCES, sent); drawer.value = true }
        chip().assertIsOn()
        tester.emulateSavedInstanceStateRestore()
        chip().assertIsOn().performClick().assertIsOff()
        compose.runOnIdle { drawer.value = false }
        tester.emulateSavedInstanceStateRestore()
        chip().assertIsOff()
        compose.onNodeWithText("Synthetic draft").assertIsDisplayed()
    }

    @Test fun emptyLibraryDisablesTheChipAndKeepsItsSavedChoice() {
        val present = mutableStateOf<Boolean?>(false)
        val scope = mutableStateOf(RequestScope.SOURCES)
        val drawer = mutableStateOf(false)
        val toggles = mutableListOf<Boolean>()
        compose.setContent {
            Host(ready.copy(draftScope = scope.value), drawer.value, hasSources = present.value, onToggle = { on ->
                toggles += on
                scope.value = if (on) RequestScope.SOURCES else RequestScope.ORDINARY
            })
        }
        for ((floating, available) in listOf(false to false, true to null)) {
            compose.runOnIdle { drawer.value = floating; present.value = available; scope.value = RequestScope.SOURCES }
            chip().assertIsEnabled().assertIsOn().performClick()
            chip().assertIsNotEnabled().assertIsOff()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Off. Add a source to use this"))
            compose.onNodeWithTag("sources-empty-hint").assertIsDisplayed()
        }
        compose.runOnIdle { present.value = true; assertEquals(listOf(false, false), toggles) }
        chip().assertIsEnabled().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("sources-empty-hint").assertDoesNotExist()
    }

    @Test fun bothHostsOpenTheHighlightedSpanAndDeletedRowsLoseTheirClickAction() {
        val drawer = mutableStateOf(false)
        val state = mutableStateOf(grounded.copy(attempts = emptyList()))
        val preview = SourceSpanPreview("Synthetic roster", "At noon.")
        val reads = MutableStateFlow<ConsultedRead>(ConsultedRead.Loaded(listOf(preview)))
        val library = SyntheticSpanLibrary(ref.span)
        val sources = SourcesViewModel(library)
        try {
            compose.setContent {
                Host(state.value, drawer.value, observeConsulted = { reads },
                    sourceSpanContent = { span, modifier -> SourceSpanRoute(sources, span, modifier) })
            }
            compose.onNodeWithText("Passages consulted").assertDoesNotExist()
            compose.runOnIdle { state.value = grounded }
            for (floating in listOf(false, true)) {
                compose.runOnIdle { drawer.value = floating; reads.value = ConsultedRead.Loaded(listOf(preview)) }
                compose.onNodeWithText("From my sources").assertIsDisplayed()
                compose.onNodeWithText("Synthetic roster").assertDoesNotExist()
                compose.onNodeWithText("Passages consulted")
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
                    .performClick()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
                compose.onNodeWithTag("consulted-source").assertHasClickAction().performClick()
                compose.onNodeWithTag("source-span-detail").assertIsDisplayed()
                compose.onNode(hasText("At noon.") and
                    (hasTestTag("source-highlight") or hasAnyAncestor(hasTestTag("source-highlight"))),
                    useUnmergedTree = true).assertIsDisplayed()
                compose.onNodeWithContentDescription("Back to chat").performClick()
                if (compose.onAllNodesWithTag("consulted-source").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithText("Passages consulted").performClick()
                }
                compose.runOnIdle { reads.value = ConsultedRead.Loaded(listOf(null)) }
                compose.onNodeWithText("Source deleted").assertIsDisplayed().assertHasNoClickAction()
                compose.onNodeWithTag("consulted-source").assertDoesNotExist()
            }
        } finally { compose.runOnIdle { sources.viewModelScope.cancel() } }
    }

    @Test fun newConversationRequestClosesTheFloatingPassage() {
        val request = mutableIntStateOf(0)
        val reads = flowOf(ConsultedRead.Loaded(listOf(SourceSpanPreview("Synthetic roster", "At noon."))))
        compose.setContent {
            Host(grounded, drawer = true, conversationRequest = request.intValue, observeConsulted = { reads },
                sourceSpanContent = { _, modifier -> Box(modifier.fillMaxWidth().testTag("selected-passage")) })
        }
        compose.onNodeWithText("Passages consulted").performClick()
        compose.onNodeWithTag("consulted-source").performClick()
        compose.onNodeWithTag("selected-passage").assertIsDisplayed()
        compose.runOnIdle { request.intValue++ }
        compose.onNodeWithTag("selected-passage").assertDoesNotExist()
        compose.onNodeWithTag("composer-input").assertIsDisplayed()
    }

    @Test fun typedNoAnswersUseTheMascotBubbleAndRetryOnlyForTemporaryReasons() {
        val drawer = mutableStateOf(false)
        val state = mutableStateOf(ready.copy(messages = listOf(user)))
        val retries = mutableListOf<Long>()
        val expected = listOf(
            Triple(SourcesNoAnswer.NO_MATCH, "Your sources don’t cover this.", false),
            Triple(SourcesNoAnswer.NOT_INDEXED, "Your sources are still being indexed.", true),
            Triple(SourcesNoAnswer.RETRIEVAL_INCOMPLETE, "I couldn’t finish searching your sources.", true),
            Triple(SourcesNoAnswer.PASSAGES_UNUSABLE, "I couldn’t use the passages I found.", false),
        )
        compose.setContent { Host(state.value, drawer.value, onRetry = { retries += it }) }
        for (floating in listOf(false, true)) {
            compose.runOnIdle { drawer.value = floating }
            for ((reason, text, canRetry) in expected) {
                compose.runOnIdle { state.value = state.value.copy(attempts = listOf(
                    AssistantAttempt("result", user.id, 0, TurnStatus.COMPLETED, sourcesResult = reason))) }
                compose.onNodeWithTag("sources-no-answer").assertIsDisplayed()
                compose.onNodeWithText(text).assertIsDisplayed()
                compose.onNodeWithText("Passages consulted").assertDoesNotExist()
                val retry = compose.onNodeWithText("Retry response")
                if (canRetry) retry.assertIsEnabled().performClick() else retry.assertDoesNotExist()
            }
        }
        compose.runOnIdle { assertEquals(List(4) { user.id }, retries) }
    }

    @Test fun largeFontKeepsTheChipBetweenAddAndInputAndPassagesExpandable() {
        val drawer = mutableStateOf(false)
        val previews = MutableStateFlow<ConsultedRead>(ConsultedRead.Loaded(listOf(SourceSpanPreview("Synthetic roster", "At noon."))))
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                Box(Modifier.width(320.dp)) {
                    Host(grounded.copy(draft = "Draft", draftScope = RequestScope.SOURCES), drawer.value,
                        observeConsulted = { previews })
                }
            }
        }
        for (floating in listOf(false, true)) {
            compose.runOnIdle { drawer.value = floating }
            val add = compose.onNodeWithContentDescription("Add attachment").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val chip = chip().assertIsDisplayed().assertIsOn().fetchSemanticsNode().boundsInRoot
            val field = compose.onNodeWithTag("composer-input").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(add.right <= chip.left && chip.right <= field.left)
            compose.onNodeWithText("Passages consulted").performScrollTo().performClick()
            compose.onNodeWithTag("consulted-source").performScrollTo().assertIsDisplayed()
        }
    }

    private fun chip() = compose.onNodeWithContentDescription("My sources, answer from my sources only")

    @Composable private fun Host(
        state: ConversationState, drawer: Boolean, hasSources: Boolean? = true, conversationRequest: Int = 0,
        onToggle: (Boolean) -> Unit = {}, onSend: () -> Unit = {}, onRetry: (Long) -> Unit = {},
        observeConsulted: (List<ConsultedPassage>) -> Flow<ConsultedRead> = { flowOf(ConsultedRead.Unavailable) },
        sourceSpanContent: (@Composable (SourceSpan, Modifier) -> Unit)? = null,
    ) {
        CompanionTheme {
            CompanionScreen(state, drawer, false, "Synthetic", {}, onSend, {}, {}, {}, {}, {}, {},
                conversationRequest = conversationRequest,
                hasSources = hasSources, onSourcesOnly = onToggle, onRetryAssistant = onRetry,
                observeConsulted = observeConsulted, sourceSpanContent = sourceSpanContent)
        }
    }

    private class SyntheticSpanLibrary(private val span: SourceSpan) : SourceLibrary {
        override val sourceChanges = MutableStateFlow(0L)
        override suspend fun sourceAtSpan(span: SourceSpan): SourceAtSpan? =
            if (span == this.span) SourceAtSpan("Synthetic roster", listOf("Synthetic lead-in."), "At noon.", listOf("Synthetic ending.")) else null
        override suspend fun listSources(query: String, after: Long) = SourcePage(emptyList(), null)
        override suspend fun sourceText(id: Long, after: Long): SourceTextPage = error("Unexpected source read")
        override suspend fun addTextSource(title: String, text: String): SourceSummary = error("Unexpected source write")
        override suspend fun addUtf8Source(title: String, bytes: ByteArray): SourceSummary = error("Unexpected source import")
        override suspend fun renameSource(id: Long, title: String) { error("Unexpected source rename") }
        override suspend fun deleteSource(id: Long): Boolean = error("Unexpected source deletion")
    }
}
