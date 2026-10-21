package dev.edgecompanion.ui.sources.viewmodel

import dev.edgecompanion.core.sources.data.SourceLibrary
import dev.edgecompanion.core.sources.model.*
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class SourcesViewModelTest {
    private class Library : SourceLibrary {
        override val sourceChanges = MutableStateFlow(0L)
        var failure: SourceError? = null
        var gate: CompletableDeferred<Unit>? = null
        var writes = 0
        val imports = mutableListOf<String>()
        var detailRead: suspend (Long, Long) -> SourceTextPage = { _, _ ->
            SourceTextPage(listOf(SourceText(1, 0, 4, "text")), null)
        }
        val item = SourceSummary(1, "Source", SourceKind.PASTED_TEXT, 0, 1, 4)
        override suspend fun listSources(query: String, after: Long): SourcePage {
            if (query == "old") { withContext(NonCancellable) { gate?.await() } }
            failure?.let { throw SourceException(it) }
            return SourcePage(if (query == "empty") emptyList() else listOf(item.copy(title = query.ifBlank { "Source" })), null)
        }
        override suspend fun sourceText(id: Long, after: Long) = detailRead(id, after)
        override suspend fun addTextSource(title: String, text: String): SourceSummary {
            writes++; gate?.await(); failure?.let { throw SourceException(it) }; return item
        }
        override suspend fun addUtf8Source(title: String, bytes: ByteArray): SourceSummary {
            imports += bytes.decodeToString()
            return addTextSource(title, "text")
        }
        override suspend fun renameSource(id: Long, title: String) { addTextSource(title, "text") }
        override suspend fun deleteSource(id: Long): Boolean { writes++; sourceChanges.value++; return true }
    }
    private fun run(block: suspend TestScope.(Library, SourcesViewModel) -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val library = Library(); val model = SourcesViewModel(library)
        try { block(library, model) }
        finally { library.gate?.complete(Unit); model.viewModelScope.cancel(); Dispatchers.resetMain() }
    }
    @Test fun searchLatestWinsEvenWhenOldReadCompletesAfterCancellation() = run { library, model ->
        runCurrent()
        library.gate = CompletableDeferred()
        model.query("old"); runCurrent(); model.query("new"); runCurrent()
        assertEquals("new", model.state.value.page.items.single().title)
        library.gate!!.complete(Unit); runCurrent()
        assertEquals("new", model.state.value.page.items.single().title)
        model.query("empty"); runCurrent(); assertTrue(model.state.value.page.items.isEmpty())
    }
    @Test fun failedWriteKeepsEditorAndRetryDoesNotDoubleSubmit() = run { library, model ->
        runCurrent(); model.edit(); model.editTitle("Title"); model.editText("text")
        library.gate = CompletableDeferred(); library.failure = SourceError.QUOTA
        model.save(); model.save(); runCurrent()
        assertEquals(1, library.writes)
        library.gate!!.complete(Unit); runCurrent()
        assertTrue(model.state.value.editorOpen); assertEquals(SourceError.QUOTA, model.state.value.error)
        assertEquals("text", model.state.value.editText)
        library.failure = null; model.save(); runCurrent()
        assertFalse(model.state.value.editorOpen); assertEquals(2, library.writes)
    }
    @Test fun sourceChangesRefreshOtherHostsAndImportFailureRemainsVisible() = run { library, model ->
        runCurrent(); model.inspect(library.item); runCurrent()
        assertEquals("text", model.state.value.text.items.single().text)
        model.closeDetail(); runCurrent()
        library.sourceChanges.value++; runCurrent(); assertFalse(model.state.value.loading)
        library.failure = SourceError.INVALID_TEXT
        model.importUtf8("Bad", byteArrayOf(0)); runCurrent()
        assertEquals(SourceError.INVALID_TEXT, model.state.value.importError)
    }
    @Test fun switchingDetailClearsPreviousBodyDuringDelayedAndFailedReads() = run { library, model ->
        runCurrent(); model.inspect(library.item); runCurrent()
        assertEquals("text", model.state.value.text.items.single().text)
        model.closeDetail(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        library.detailRead = { _, _ -> gate.await(); throw SourceException(SourceError.MISSING) }
        model.inspect(library.item.copy(id = 2, revisionId = 2, title = "Other source"))
        assertTrue(model.state.value.text.items.isEmpty())
        runCurrent()
        assertTrue(model.state.value.loading)
        assertTrue(model.state.value.text.items.isEmpty())
        gate.complete(Unit); runCurrent()
        assertEquals(SourceError.MISSING, model.state.value.error)
        assertTrue(model.state.value.text.items.isEmpty())
    }
    @Test fun completedFileDuringSaveReportsRecoverableBusyInsteadOfSilentSuccess() = run { library, model ->
        runCurrent(); model.edit(); model.editTitle("Title"); model.editText("text")
        library.gate = CompletableDeferred()
        model.save(); runCurrent()
        val bytes = "file text".encodeToByteArray()
        val outcome = model.importUtf8("Selected file", bytes)
        // One existing save, no file write, but the selection must not vanish without an error.
        assertEquals(1, library.writes)
        assertEquals(SourceImportOutcome.BUSY, outcome)
        assertEquals(SourceError.BUSY, model.state.value.importError)
        assertEquals("file text", bytes.decodeToString())
        library.gate!!.complete(Unit); runCurrent()
        assertEquals(SourceError.BUSY, model.state.value.importError)
        model.refresh(); runCurrent()
        assertEquals(SourceError.BUSY, model.state.value.importError)
        assertEquals(SourceImportOutcome.SAVED, model.importUtf8("Selected again", bytes))
        runCurrent()
        assertNull(model.state.value.importError)
        assertEquals(2, library.writes)
        assertTrue(bytes.all { it == 0.toByte() })
    }
    @Test fun delayedSuccessfulDetailAndLatePreviousRevisionCannotShowWrongBody() = run { library, model ->
        runCurrent()
        val old = CompletableDeferred<Unit>()
        library.detailRead = { _, _ ->
            withContext(NonCancellable) { old.await() }
            SourceTextPage(listOf(SourceText(1, 0, 3, "old")), null)
        }
        model.inspect(library.item); runCurrent()
        val next = CompletableDeferred<Unit>()
        library.detailRead = { _, _ ->
            next.await(); SourceTextPage(listOf(SourceText(2, 0, 3, "new")), null)
        }
        model.inspect(library.item.copy(revisionId = 2)); runCurrent()
        assertTrue(model.state.value.text.items.isEmpty())
        next.complete(Unit); runCurrent()
        assertEquals("new", model.state.value.text.items.single().text)
        old.complete(Unit); runCurrent()
        assertEquals(2, model.state.value.detail!!.revisionId)
        assertEquals("new", model.state.value.text.items.single().text)
    }
    @Test fun identityGuardRejectsDetailForAnotherSourceOrRevision() {
        val selected = SourceSummary(2, "B", SourceKind.PASTED_TEXT, 0, 3, 3)
        val page = SourceTextPage(listOf(SourceText(1, 0, 3, "old")), 1)
        for (detail in listOf(SourceDetail(1, 3, page), SourceDetail(2, 2, page))) {
            val state = SourcesState(selected = selected, detail = detail)
            assertTrue(state.text.items.isEmpty()); assertNull(state.text.next)
        }
    }
    @Test fun providerReadingOwnsWriteSlotUntilTheSelectedFileHasBeenSaved() = run { library, model ->
        runCurrent(); model.edit(); model.editTitle("Competing paste"); model.editText("draft")
        val loaded = CompletableDeferred<ByteArray>()
        val importing = async { model.importUtf8("Selected file") { loaded.await() } }
        runCurrent()
        assertTrue(model.state.value.writing)
        model.save(); model.delete(1); runCurrent()
        assertEquals(0, library.writes)
        var secondRead = false
        assertEquals(SourceImportOutcome.BUSY, model.importUtf8("Other file") {
            secondRead = true; byteArrayOf(1)
        })
        assertFalse(secondRead)
        val bytes = "selected content".encodeToByteArray()
        loaded.complete(bytes); runCurrent()
        assertEquals(SourceImportOutcome.SAVED, importing.await())
        assertEquals(listOf("selected content"), library.imports)
        assertEquals(1, library.writes)
        assertFalse(model.state.value.writing)
        assertTrue(bytes.all { it == 0.toByte() })
        assertEquals(SourceError.BUSY, model.state.value.importError)
    }
    @Test fun cancellationDuringReadOrSaveReleasesOnlyItsOwnReservation() = run { library, model ->
        runCurrent()
        val reading = launch { model.importUtf8("Cancelled read") { awaitCancellation() } }
        runCurrent(); assertTrue(model.state.value.writing)
        reading.cancelAndJoin(); runCurrent()
        assertFalse(model.state.value.writing); assertEquals(0, library.writes)
        library.gate = CompletableDeferred()
        val bytes = "cancelled save".encodeToByteArray()
        val saving = launch { model.importUtf8("Cancelled save", bytes) }
        runCurrent(); assertTrue(model.state.value.writing)
        saving.cancelAndJoin(); runCurrent()
        assertFalse(model.state.value.writing); assertTrue(bytes.all { it == 0.toByte() })
        library.gate!!.complete(Unit)
        assertEquals(SourceImportOutcome.SAVED, model.importUtf8("Retry", "retry".encodeToByteArray()))
        assertFalse(model.state.value.writing)
        assertNull(model.state.value.importError)
    }
    @Test fun providerFailureDoesNotCallLibraryAndRemainsVisibleAcrossRefresh() = run { library, model ->
        runCurrent()
        val outcome = model.importUtf8("Unreadable") { throw SourceException(SourceError.STORAGE) }
        assertEquals(SourceImportOutcome.FAILED, outcome)
        assertEquals(0, library.writes); assertFalse(model.state.value.writing)
        library.sourceChanges.value++; runCurrent()
        assertEquals(SourceError.STORAGE, model.state.value.importError)
        assertEquals(SourceImportOutcome.SAVED, model.importUtf8("Retry", "text".encodeToByteArray()))
        runCurrent(); assertNull(model.state.value.importError)
    }
}
