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

/** Process-lifetime session; UI hosts may come and go without cancelling writes. */
class ConversationSession(
    dispatcher: CoroutineDispatcher,
    private val controllerProvider: () -> ConversationController,
    private val closeStorage: () -> Unit,
    private val attachmentFiles: AttachmentFiles? = null,
    private val mediaDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutable = MutableStateFlow(ConversationState())
    val state = mutable.asStateFlow()
    private var controller: ConversationController? = null
    private val available = CompletableDeferred<ConversationController?>()
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
            restored.state.collect { mutable.value = it }
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
    fun submit() { scope.launch { controller?.submit() } }
    fun clear() { scope.launch {
        val ids = controller?.state?.value?.attachments?.map { it.id }.orEmpty()
        controller?.clear()
        if (controller?.state?.value?.error == null) ids.forEach {
            importJobs[it]?.cancel()
            runCatching { attachmentFiles?.remove(it) }
        }
    } }
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
        scope.coroutineContext.job.cancelAndJoin()
    }
}
