package dev.edgecompanion.core.conversation.session

import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.core.attachments.model.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.core.conversation.model.ConversationState

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import dev.edgecompanion.core.inference.ChatRuntime
import dev.edgecompanion.core.sources.data.SourcesRepository
import dev.edgecompanion.core.sources.data.SourceLibrary
import dev.edgecompanion.core.sources.model.*

/** Process-lifetime session; UI hosts may come and go without cancelling writes. */
class ConversationSession(
    dispatcher: CoroutineDispatcher,
    private val controllerProvider: () -> ConversationController,
    private val closeStorage: () -> Unit,
    private val attachmentFiles: AttachmentFiles? = null,
    private val mediaDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val chatRuntime: ChatRuntime? = null,
    private val sourcesProvider: (() -> SourcesRepository)? = null,
) : SourceLibrary {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val mutable = MutableStateFlow(ConversationState())
    val state = mutable.asStateFlow()
    private val sourceVersion = MutableStateFlow(0L)
    override val sourceChanges = sourceVersion.asStateFlow()
    private var controller: ConversationController? = null
    private val available = CompletableDeferred<ConversationController?>()
    private val assistant = ConversationAssistant(scope, { available.await() }, chatRuntime)
    private val imports = Semaphore(1)
    private val importJobs = mutableMapOf<String, Job>()
    private val startup = scope.launch(start = CoroutineStart.LAZY) {
        try {
            val restored = controllerProvider()
            restored.restore()
            if (restored.state.value.ready) attachmentFiles?.let { files ->
                files.retain(restored.state.value.attachments.map { it.id }.toSet())
                restored.state.value.attachments.filter { it.status == AttachmentStatus.READY && !files.contains(it.id) }
                    .forEach { restored.fail(it.id) }
            }
            controller = restored
            available.complete(restored)
            combine(restored.state, assistant.state) { durable, active -> durable.copy(assistant = active) }
                .collect { mutable.value = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = ConversationState(error = ConversationError.ENCRYPTED_STORAGE)
        } finally {
            available.complete(null)
            closeStorage()
        }
    }

    fun start() { startup.start() }
    fun attachmentLimitReached() { scope.launch { controller?.attachmentError(limit = true) } }
    fun editDraft(text: String) { scope.launch { controller?.editDraft(text) } }
    fun submit() { assistant.submit() }
    fun retryAssistant(userId: Long) { assistant.submit(userId) }
    fun cancelAssistant() { assistant.cancel() }
    private suspend fun <T> sources(block: suspend (SourcesRepository) -> T): T {
        val caller = currentCoroutineContext()
        caller.ensureActive()
        val task = scope.async {
            caller.ensureActive()
            start()
            val target = available.await()
            if (target?.state?.value?.ready != true) throw SourceException(SourceError.STORAGE)
            caller.ensureActive()
            val repository = sourcesProvider?.invoke() ?: throw SourceException(SourceError.UNAVAILABLE)
            block(repository)
        }
        try { return task.await() }
        finally { if (!currentCoroutineContext().isActive) withContext(NonCancellable) { task.cancelAndJoin() } }
    }
    override suspend fun listSources(query: String, after: Long) = sources { it.list(query, after) }
    override suspend fun sourceText(id: Long, after: Long) = sources { it.text(id, after) }
    override suspend fun addTextSource(title: String, text: String) = sources {
        it.importText(title, text, SourceKind.PASTED_TEXT).also { sourceVersion.value++ }
    }
    override suspend fun addUtf8Source(title: String, bytes: ByteArray) = sources {
        it.importUtf8(title, bytes).also { sourceVersion.value++ }
    }
    override suspend fun renameSource(id: Long, title: String) = sources {
        it.rename(id, title).also { sourceVersion.value++ }
    }
    override suspend fun deleteSource(id: Long) = sources { repository ->
        assistant.mutateContext {
            repository.delete(id).also { controller?.refresh(); sourceVersion.value++ }
        }
    }
    fun clear() { scope.launch { assistant.clear {
        val ids = controller?.state?.value?.attachments?.map { it.id }.orEmpty()
        controller?.clear()
        if (controller?.state?.value?.error == null) ids.forEach {
            importJobs[it]?.cancel()
            runCatching { attachmentFiles?.remove(it) }
        }
    } } }
    fun importAttachment(item: Attachment, epoch: Long, load: suspend () -> ByteArray) {
        scope.launch {
            val target = available.await() ?: return@launch
            val files = attachmentFiles ?: return@launch
            if (!target.reserve(item, epoch)) return@launch
            importJobs[item.id] = currentCoroutineContext().job
            try { imports.withPermit {
                if (target.state.value.attachments.none { it.id == item.id }) return@withPermit
                try {
                    val bytes = withContext(mediaDispatcher) { load() }
                    try {
                        withContext(mediaDispatcher) { files.write(item.id, bytes) }
                        if (!target.complete(item.id, bytes.size.toLong())) files.remove(item.id)
                    } finally { bytes.fill(0) }
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable + mediaDispatcher) { runCatching { files.remove(item.id) } }
                    throw cancelled
                }
                catch (_: Exception) {
                    runCatching { files.remove(item.id) }
                    runCatching { target.fail(item.id) }.onFailure { target.attachmentError() }
                }
            } } finally { importJobs.remove(item.id) }
        }
    }
    fun removeAttachment(id: String) { scope.launch {
        val target = controller ?: return@launch
        if (target.state.value.attachments.none { it.id == id && it.messageId == null }) return@launch
        try { target.remove(id); importJobs[id]?.cancel(); attachmentFiles?.remove(id) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { target.attachmentError() }
    } }
    fun stop() { scope.cancel() }

    /** Await outstanding work before disposing an isolated test/application container. */
    suspend fun shutdown() {
        assistant.invalidate()
        scope.coroutineContext.job.cancelAndJoin()
    }
}
