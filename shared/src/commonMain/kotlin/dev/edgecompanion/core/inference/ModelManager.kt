package dev.edgecompanion.core.inference

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ModelManager(
    val assets: ModelAssets,
    private val engine: LocalLlmEngine,
    dispatcher: CoroutineDispatcher,
    private val resources: () -> ResourceSnapshot,
    private val nowMs: () -> Long,
    override val enabled: Boolean,
    private val manifest: ModelManifest = ModelCatalog.gemma4,
    private val idleMs: Long = 60_000,
    val memoryPolicy: MemoryPolicy = MemoryPolicies.Current,
) : ChatRuntime {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val operation = Mutex()
    private val mutable = MutableStateFlow(ModelRuntimeState())
    val state = mutable.asStateFlow()
    private var idle: Job? = null
    private var generation: Job? = null
    private var poisoned = false
    private var ownsEngine = false
    private var releasing = 0
    private var assetWork: Job? = null
    private var chatId: String? = null
    private var chatCancellation = ChatOutcome.CANCELLED

    override fun generateChat(request: ChatRequest): Flow<ChatEvent> = chatFlow(request.copy(messages = request.messages.toList()))

    private fun chatFlow(request: ChatRequest): Flow<ChatEvent> = channelFlow {
        val worker = scope.launch {
            if (!enabled || releasing > 0 || !operation.tryLock()) {
                trySend(ChatEvent.Terminal(request.id, ChatOutcome.REJECTED,
                    if (!enabled) ModelError.NOT_INSTALLED else ModelError.BUSY))
                close()
                return@launch
            }
            var terminal = ChatEvent.Terminal(request.id, ChatOutcome.FAILED, ModelError.GENERATION_FAILED)
            var monitor: Job? = null
            try {
                GemmaChatBudget.validate(request)
                if (poisoned) throw ModelException(ModelError.RESTART_REQUIRED)
                if (ownsEngine && state.value.status == RuntimeStatus.FAILED) {
                    withContext(NonCancellable) { engine.release(); ownsEngine = false }
                }
                if (ownsEngine && (state.value.status != RuntimeStatus.LOADED || state.value.backend != request.backend))
                    throw ModelException(ModelError.BUSY)
                memoryPolicy.blockedReason(resources(), if (ownsEngine) MemoryAdmission.GENERATION else MemoryAdmission.LOADING)
                    ?.let { throw ModelException(it) }
                idle?.cancel()
                chatId = request.id
                chatCancellation = ChatOutcome.CANCELLED
                generation = currentCoroutineContext().job
                if (!ownsEngine) {
                    mutable.value = ModelRuntimeState(RuntimeStatus.LOADING, request.backend)
                    val path = assets.verify() ?: throw ModelException(ModelError.NOT_INSTALLED)
                    withContext(NonCancellable) {
                        engine.load(ModelConfig(path, manifest.sha256, request.contextTokens, request.backend,
                            request.outputTokens)).getOrThrow()
                        ownsEngine = true
                    }
                }
                currentCoroutineContext().ensureActive()
                memoryPolicy.blockedReason(resources(), MemoryAdmission.GENERATION)?.let { throw ModelException(it) }
                mutable.update { it.copy(status = RuntimeStatus.GENERATING, error = null, commandError = null) }
                monitor = resourceMonitor()
                var completed = false
                var nonempty = false
                engine.generate(GenerationRequest("", request.outputTokens, request)).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> if (event.text.isNotEmpty()) {
                            nonempty = true
                            trySend(ChatEvent.Text(request.id, event.text))
                        }
                        is GenerationEvent.Finished -> completed = true
                        is GenerationEvent.Failed -> throw ModelException(event.error)
                        GenerationEvent.Cancelled -> throw CancellationException()
                    }
                }
                terminal = if (completed && nonempty) ChatEvent.Terminal(request.id, ChatOutcome.COMPLETED)
                    else ChatEvent.Terminal(request.id, ChatOutcome.FAILED, ModelError.GENERATION_FAILED)
            } catch (_: CancellationException) {
                terminal = ChatEvent.Terminal(request.id, chatCancellation, state.value.error)
            } catch (failure: Exception) {
                val error = (failure as? ModelException)?.error ?: ModelError.GENERATION_FAILED
                if (error == ModelError.BUSY || error == ModelError.CONTEXT_LIMIT)
                    terminal = ChatEvent.Terminal(request.id, ChatOutcome.REJECTED, error)
                else {
                    fail(failure)
                    terminal = ChatEvent.Terminal(request.id, ChatOutcome.FAILED, error)
                }
            } finally {
                monitor?.cancel()
                chatId = null
                generation = null
                mutable.update { if (it.status in setOf(RuntimeStatus.GENERATING, RuntimeStatus.CANCELLING, RuntimeStatus.LOADING))
                    it.copy(status = if (ownsEngine) RuntimeStatus.LOADED else RuntimeStatus.UNLOADED) else it }
                operation.unlock()
                scheduleIdle()
                trySend(terminal)
                close()
            }
        }
        try { worker.join() }
        finally { if (!worker.isCompleted) withContext(NonCancellable) {
            worker.cancel()
            cancelChat(request.id, interrupted = true)
            worker.join()
        } }
    }

    override suspend fun cancelChat(id: String, interrupted: Boolean) {
        withContext(scope.coroutineContext.minusKey(Job)) {
            if (chatId == id) cancel(interrupted).join()
        }
    }

    private fun resourceMonitor(): Job = scope.launch {
        val window = MemoryPressureWindow(memoryPolicy)
        while (isActive) {
            delay(memoryPolicy.sampleIntervalMs)
            val reason = window.observe(resources())
            if (reason != null) {
                mutable.update { it.copy(error = reason) }
                cancel(interrupted = true)
                break
            }
        }
    }

    fun verify() = work { assets.verify() }
    fun install(source: ModelSource) = work {
        if (state.value.status != RuntimeStatus.UNLOADED) throw ModelException(ModelError.BUSY)
        assetWork = currentCoroutineContext()[Job]
        try { assets.install(source) } finally { assetWork = null }
    }

    fun load(backend: ModelBackend) = work {
        if (poisoned) throw ModelException(ModelError.RESTART_REQUIRED)
        if (state.value.status == RuntimeStatus.LOADED) return@work
        if (ownsEngine) throw ModelException(ModelError.BUSY)
        memoryPolicy.blockedReason(resources(), MemoryAdmission.LOADING)?.let { error ->
            mutable.update { it.copy(status = RuntimeStatus.BLOCKED, error = error) }
            return@work
        }
        mutable.value = ModelRuntimeState(status = RuntimeStatus.LOADING, backend = backend)
        val path = assets.verify() ?: throw ModelException(ModelError.NOT_INSTALLED)
        val started = nowMs()
        withContext(NonCancellable) {
            engine.load(ModelConfig(path, manifest.sha256, manifest.configuredContextTokens, backend,
                manifest.maxOutputTokens)).getOrThrow()
            ownsEngine = true
        }
        mutable.update { it.copy(status = RuntimeStatus.LOADED, loadMs = nowMs() - started, error = null) }
        scheduleIdle()
    }

    fun testPrompt(prompt: String = ModelTestPrompts.SHORT): Job = scope.launch {
        if (!enabled) return@launch
        if (releasing > 0 || !operation.tryLock()) {
            reject(ModelError.BUSY)
            return@launch
        }
        try {
            if (state.value.status != RuntimeStatus.LOADED || poisoned) {
                reject(if (poisoned) ModelError.RESTART_REQUIRED else ModelError.BUSY)
                return@launch
            }
            mutable.update { it.copy(commandError = null) }
            require(prompt in ModelTestPrompts.allowed)
            memoryPolicy.blockedReason(resources(), MemoryAdmission.GENERATION)?.let { throw ModelException(it) }
            idle?.cancel()
            generation = currentCoroutineContext()[Job]
            val started = nowMs()
            mutable.update { it.copy(status = RuntimeStatus.GENERATING, output = "", error = null,
                firstTokenMs = null, generationMs = null, outputTokens = null, inputTokens = null, decodeTokensPerSecond = null) }
            val monitor = resourceMonitor()
            try {
                engine.generate(GenerationRequest(prompt, manifest.maxOutputTokens)).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> mutable.update {
                            if (it.status != RuntimeStatus.GENERATING) it else it.copy(
                                output = it.output + event.text, firstTokenMs = it.firstTokenMs ?: (nowMs() - started))
                        }
                        is GenerationEvent.Finished -> mutable.update { it.copy(outputTokens = event.tokens,
                            inputTokens = event.inputTokens, decodeTokensPerSecond = event.decodeTokensPerSecond) }
                        is GenerationEvent.Failed -> throw ModelException(event.error)
                        GenerationEvent.Cancelled -> Unit
                    }
                }
            } finally { monitor.cancel() }
            mutable.update { it.copy(generationMs = nowMs() - started) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) { fail(failure) }
        finally {
            generation = null
            mutable.update { if (it.status in setOf(RuntimeStatus.GENERATING, RuntimeStatus.CANCELLING))
                it.copy(status = RuntimeStatus.LOADED) else it }
            operation.unlock()
            scheduleIdle()
        }
    }

    // Never acquire the operation mutex here: generation owns it until native completion.
    fun cancel(interrupted: Boolean = false): Job = scope.launch {
        if (!enabled) return@launch
        mutable.update { it.copy(commandError = null) }
        assetWork?.cancel()
        val active = generation ?: return@launch
        chatCancellation = if (interrupted) ChatOutcome.INTERRUPTED else ChatOutcome.CANCELLED
        mutable.update { if (it.status == RuntimeStatus.GENERATING) it.copy(status = RuntimeStatus.CANCELLING) else it }
        active.cancel()
        try { engine.cancel() } catch (failure: Exception) { fail(failure) }
    }

    fun release(): Job = scope.launch {
        if (!enabled) return@launch
        releasing++
        try {
            mutable.update { it.copy(commandError = null) }
            idle?.cancel()
            this@ModelManager.cancel(interrupted = true).join()
            operation.withLock {
                if (poisoned) return@withLock
                mutable.update { it.copy(status = RuntimeStatus.RELEASING) }
                try {
                    engine.release()
                    ownsEngine = false
                    mutable.update { it.copy(status = RuntimeStatus.UNLOADED, error = null) }
                } catch (failure: Exception) { poisoned = true; fail(failure) }
            }
        } finally { releasing-- }
    }

    private fun work(block: suspend () -> Unit) = scope.launch {
        if (!enabled) return@launch
        if (releasing > 0 || !operation.tryLock()) {
            reject(ModelError.BUSY)
            return@launch
        }
        mutable.update { it.copy(commandError = null) }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if ((failure as? ModelException)?.error == ModelError.BUSY) reject(ModelError.BUSY)
            else fail(failure)
        }
        finally { operation.unlock() }
    }

    private fun reject(error: ModelError) {
        mutable.update { it.copy(commandError = error) }
    }

    private fun fail(failure: Exception) {
        val error = (failure as? ModelException)?.error ?: ModelError.LOAD_FAILED
        if (error in setOf(ModelError.CANCEL_TIMEOUT, ModelError.RELEASE_FAILED, ModelError.RESTART_REQUIRED)) poisoned = true
        mutable.update { it.copy(status = RuntimeStatus.FAILED, error = error) }
    }

    private fun scheduleIdle() {
        idle?.cancel()
        if (state.value.status == RuntimeStatus.LOADED) idle = scope.launch { delay(idleMs); release() }
    }

    suspend fun shutdown() { release().join(); scope.cancel() }
}
