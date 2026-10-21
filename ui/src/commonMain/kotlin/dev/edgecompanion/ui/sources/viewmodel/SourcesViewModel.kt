package dev.edgecompanion.ui.sources.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.edgecompanion.core.sources.data.SourceLibrary
import dev.edgecompanion.core.sources.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex

data class SourceDetail(val sourceId: Long, val revisionId: Long, val page: SourceTextPage)
enum class SourceImportOutcome { SAVED, BUSY, FAILED }

data class SourcesState(
    val query: String = "", val page: SourcePage = SourcePage(emptyList(), null),
    val selected: SourceSummary? = null, val detail: SourceDetail? = null,
    val loading: Boolean = true, val writing: Boolean = false, val error: SourceError? = null,
    val canGoBack: Boolean = false, val editorOpen: Boolean = false,
    val editTitle: String = "", val editText: String = "", val renameId: Long? = null,
    val importError: SourceError? = null,
) {
    val text: SourceTextPage get() = detail?.takeIf {
        it.sourceId == selected?.id && it.revisionId == selected.revisionId
    }?.page ?: SourceTextPage(emptyList(), null)
}

/** Host-scoped presentation; the session owns durable writes and cross-host change signals. */
class SourcesViewModel(private val library: SourceLibrary) : ViewModel() {
    private val mutable = MutableStateFlow(SourcesState())
    val state = mutable.asStateFlow()
    private var read: Job? = null
    private var sequence = 0L
    private val cursors = mutableListOf(0L)
    private val writeAdmission = Mutex()

    init { viewModelScope.launch { library.sourceChanges.collect { refresh() } } }

    fun query(text: String) {
        mutable.update { it.copy(query = text.take(256), selected = null, detail = null) }
        cursors.clear(); cursors += 0L; refresh()
    }
    fun next() {
        if (state.value.loading || state.value.selected != null) return
        state.value.page.next?.let { cursors += it; refresh() }
    }
    fun previous() { if (cursors.size > 1) { cursors.removeAt(cursors.lastIndex); refresh() } }
    fun inspect(item: SourceSummary) { mutable.update { it.copy(selected = item, detail = null) }; refresh() }
    fun closeDetail() { mutable.update { it.copy(selected = null, detail = null) }; refresh() }
    fun nextText() { state.value.text.next?.let { refresh(it) } }
    fun firstText() = refresh(0)

    fun refresh(textAfter: Long = 0) {
        val id = ++sequence
        read?.cancel()
        val snapshot = state.value
        val cursor = cursors.last()
        mutable.update { it.copy(loading = true, error = null, canGoBack = cursors.size > 1) }
        read = viewModelScope.launch {
            try {
                if (snapshot.selected == null) {
                    val page = library.listSources(snapshot.query, cursor)
                    if (id == sequence) mutable.update { it.copy(page = page, loading = false) }
                } else {
                    val text = library.sourceText(snapshot.selected.id, textAfter)
                    if (id == sequence) mutable.update {
                        if (it.selected?.id == snapshot.selected.id && it.selected.revisionId == snapshot.selected.revisionId)
                            it.copy(detail = SourceDetail(snapshot.selected.id, snapshot.selected.revisionId, text), loading = false)
                        else it
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (id == sequence) mutable.update {
                it.copy(loading = false, detail = null, error = (failure as? SourceException)?.error ?: SourceError.STORAGE)
            } }
        }
    }

    fun edit(item: SourceSummary? = null) { mutable.update { it.copy(editorOpen = true,
        editTitle = item?.title.orEmpty(), editText = "", renameId = item?.id, error = null) } }
    fun editTitle(value: String) { mutable.update { it.copy(editTitle = value.take(180)) } }
    fun editText(value: String) { mutable.update { it.copy(editText = value.take(SourceLimits.TEXT_BYTES + 1)) } }
    fun dismissEditor() { if (!state.value.writing) mutable.update { it.copy(editorOpen = false) } }
    fun save() {
        val snapshot = state.value
        write {
            if (snapshot.renameId == null) library.addTextSource(snapshot.editTitle, snapshot.editText)
            else library.renameSource(snapshot.renameId, snapshot.editTitle)
            mutable.update { it.copy(editorOpen = false, editText = "", editTitle = "", selected = null, detail = null) }
        }
    }
    fun delete(id: Long) = write {
        library.deleteSource(id)
        mutable.update { it.copy(selected = null, detail = null) }
    }
    /** Accepted input transfers ownership; a BUSY result leaves the caller's bytes untouched. */
    suspend fun importUtf8(title: String, bytes: ByteArray): SourceImportOutcome = importUtf8(title) { bytes }

    /** The write reservation spans provider reading, persistence and buffer cleanup. */
    suspend fun importUtf8(title: String, load: suspend () -> ByteArray): SourceImportOutcome {
        currentCoroutineContext().ensureActive()
        if (!beginWrite()) {
            importFailed(SourceError.BUSY)
            return SourceImportOutcome.BUSY
        }
        mutable.update { it.copy(importError = null) }
        var bytes: ByteArray? = null
        try {
            bytes = load()
            currentCoroutineContext().ensureActive()
            library.addUtf8Source(title, bytes)
            mutable.update { it.copy(selected = null, detail = null) }
            refresh()
            return SourceImportOutcome.SAVED
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            importFailed((failure as? SourceException)?.error ?: SourceError.STORAGE)
            return SourceImportOutcome.FAILED
        }
        finally { bytes?.fill(0); endWrite() }
    }
    fun importFailed(error: SourceError = SourceError.STORAGE) { mutable.update { it.copy(importError = error) } }
    private fun beginWrite(): Boolean {
        if (!writeAdmission.tryLock()) return false
        mutable.update { it.copy(writing = true, error = null) }
        return true
    }
    private fun endWrite() { mutable.update { it.copy(writing = false) }; writeAdmission.unlock() }
    private fun write(block: suspend () -> Unit) {
        if (!beginWrite()) return
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try { currentCoroutineContext().ensureActive(); block(); refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { report(failure) }
            finally { endWrite() }
        }
    }
    private fun report(failure: Exception) { mutable.update {
        it.copy(error = (failure as? SourceException)?.error ?: SourceError.STORAGE)
    } }
}
