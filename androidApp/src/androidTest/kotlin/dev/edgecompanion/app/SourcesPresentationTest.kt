package dev.edgecompanion.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import dev.edgecompanion.core.sources.data.SourceLibrary
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import dev.edgecompanion.ui.sources.screen.SourcesRoute
import dev.edgecompanion.ui.sources.viewmodel.SourcesViewModel
import dev.edgecompanion.ui.sources.viewmodel.SourceImportOutcome
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SourcesPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun switchingSourcesNeverShowsPreviousBodyWhileReadingOrAfterFailure() {
        val library = Library()
        val first = SourceSummary(1, "Source A", SourceKind.PASTED_TEXT, 1, 1, 6)
        val second = first.copy(id = 2, revisionId = 2, title = "Source B")
        library.rows = listOf(first, second)
        val gate = CompletableDeferred<Unit>()
        library.detailRead = { id, _ ->
            if (id == first.id) SourceTextPage(listOf(SourceText(1, 0, 6, "Body A")), null)
            else { gate.await(); throw SourceException(SourceError.MISSING) }
        }
        val model = SourcesViewModel(library)
        try {
            compose.setContent { CompanionTheme { SourcesRoute(model, {}) } }
            compose.onNodeWithText("Source A").performClick()
            compose.onNodeWithText("Body A").assertIsDisplayed()
            compose.onNodeWithContentDescription("Sources").performClick()
            compose.onNodeWithText("Source B").performClick()
            compose.onNodeWithText("Body A").assertDoesNotExist()
            compose.runOnIdle { gate.complete(Unit) }
            compose.onNodeWithText("This source is no longer available.").assertIsDisplayed()
            compose.onNodeWithText("Body A").assertDoesNotExist()
        } finally { gate.complete(Unit); compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun selectedFileDuringSaveShowsRecoverableBusyAndCanBeSelectedAgain() {
        val library = Library()
        val gate = CompletableDeferred<Unit>()
        library.saveGate = gate
        val model = SourcesViewModel(library)
        val outcome = mutableStateOf<SourceImportOutcome?>(null)
        var providerRead = false
        val busy = "The selected file was not imported because another source operation is in progress. Select it again after that operation finishes."
        try {
            compose.setContent { CompanionTheme { SourcesRoute(model, {}) } }
            compose.onNodeWithContentDescription("Add text").performClick()
            compose.onNodeWithTag("source-title").performTextInput("Pasted text")
            compose.onNodeWithTag("source-body").performTextInput("Body")
            compose.onNodeWithText("Save source").performClick()
            compose.runOnIdle { model.viewModelScope.launch {
                outcome.value = model.importUtf8("Selected file") {
                    providerRead = true; "Selected contents".encodeToByteArray()
                }
            } }
            compose.waitUntil { outcome.value != null }
            compose.runOnIdle {
                assertEquals(SourceImportOutcome.BUSY, outcome.value)
                assertFalse(providerRead); assertEquals(1, library.writes)
            }
            compose.onAllNodesWithText(busy).onLast().assertIsDisplayed()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil { !model.state.value.editorOpen }
            compose.onNodeWithTag("source-import-error").assertIsDisplayed()
            compose.runOnIdle { model.viewModelScope.launch {
                outcome.value = model.importUtf8("Selected again") {
                    providerRead = true; "Selected contents".encodeToByteArray()
                }
            } }
            compose.waitUntil { outcome.value == SourceImportOutcome.SAVED }
            compose.runOnIdle {
                assertTrue(providerRead); assertEquals(2, library.writes)
                assertFalse(model.state.value.writing)
            }
            compose.onNodeWithTag("source-import-error").assertDoesNotExist()
        } finally { gate.complete(Unit); compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun compactLargeTextInBothThemesKeepsTitleAndActionsSeparate() {
        val library = Library()
        val title = "A very long synthetic source title for compact screens"
        library.item = SourceSummary(1, title, SourceKind.PASTED_TEXT, 1, 1, 21)
        val model = SourcesViewModel(library)
        val dark = mutableStateOf(false)
        try {
            compose.setContent {
                CompanionTheme(dark.value) {
                    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                        Surface { Box(Modifier.size(320.dp, 600.dp)) { SourcesRoute(model, {}) } }
                    }
                }
            }
            for (mode in listOf(false, true)) {
                compose.runOnIdle { dark.value = mode }
                compose.onNodeWithText(title).performClick()
                val text = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
                val edit = compose.onNodeWithContentDescription("Rename source").fetchSemanticsNode().boundsInRoot
                assertTrue(text.right <= edit.left)
                compose.onNodeWithText("Synthetic source body").assertIsDisplayed()
                compose.onNodeWithContentDescription("Delete source").assertIsDisplayed()
                compose.onNodeWithContentDescription("Sources").performClick()
            }
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    @Test fun pasteInspectRenameAndConfirmedDeleteKeepKeywordOnlyStatus() {
        val library = Library()
        val model = SourcesViewModel(library)
        try {
            compose.setContent { CompanionTheme { SourcesRoute(model, {}) } }
            compose.onNodeWithText("No saved sources").assertIsDisplayed()
            compose.onNodeWithContentDescription("Add text").performClick()
            compose.onNodeWithTag("source-title").performTextInput("Contract")
            compose.onNodeWithTag("source-body").performTextInput("Synthetic source body")
            compose.onNodeWithText("Save source").performClick()
            compose.waitUntil { library.item != null }
            compose.onNodeWithText("Text \u00B7 Keyword-ready").assertIsDisplayed()
            compose.onNodeWithText("Contract").performClick()
            compose.onNodeWithText("Synthetic source body").assertIsDisplayed()
            compose.onNodeWithContentDescription("Rename source").performClick()
            compose.onNodeWithTag("source-title").performTextReplacement("Work")
            compose.onNodeWithText("Save source").performClick()
            compose.onNodeWithText("Work").performClick()
            compose.onNodeWithContentDescription("Delete source").performClick()
            compose.onNodeWithText("Delete this source?").assertIsDisplayed()
            compose.onNodeWithText("Cancel").performClick()
            assertNotNull(library.item)
            compose.onNodeWithContentDescription("Delete source").performClick()
            compose.onNodeWithText("Delete source").performClick()
            compose.waitUntil { library.item == null }
            compose.onNodeWithText("No saved sources").assertIsDisplayed()
        } finally { compose.runOnIdle { model.viewModelScope.cancel() } }
    }
    private class Library : SourceLibrary {
        override val sourceChanges = MutableStateFlow(0L)
        var item: SourceSummary? = null
        var rows: List<SourceSummary>? = null
        var writes = 0
        var saveGate: CompletableDeferred<Unit>? = null
        var detailRead: suspend (Long, Long) -> SourceTextPage = { _, _ ->
            SourceTextPage(listOf(SourceText(1, 0, 21, "Synthetic source body")), null)
        }
        override suspend fun listSources(query: String, after: Long) = SourcePage(rows ?: listOfNotNull(item), null)
        override suspend fun sourceText(id: Long, after: Long) = detailRead(id, after)
        override suspend fun addTextSource(title: String, text: String): SourceSummary {
            writes++; saveGate?.await()
            val added = SourceSummary(1, title, SourceKind.PASTED_TEXT, 1, 1, text.length.toLong())
            item = added; sourceChanges.value++; return added
        }
        override suspend fun addUtf8Source(title: String, bytes: ByteArray) = addTextSource(title, bytes.decodeToString())
        override suspend fun renameSource(id: Long, title: String) { item = item?.copy(title = title); sourceChanges.value++ }
        override suspend fun deleteSource(id: Long): Boolean { item = null; sourceChanges.value++; return true }
    }
}
