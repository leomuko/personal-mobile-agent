package dev.edgecompanion.core.inference

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import dev.edgecompanion.core.inference.embedding.*

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
    private val memoryTelemetry: suspend (MemoryTracePoint) -> Unit = {},
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
    private val cancellationSignals = mutableSetOf<Job>()
    private var assetWork: Job? = null
    private var chatId: String? = null
    private var chatCancellation = ChatOutcome.CANCELLED
    private var foreground: ForegroundPreparation? = null
    private val lifetimeEpoch = MutableStateFlow(0L)

    override fun generateChat(request: ChatRequest): Flow<ChatEvent> = chatFlow(request.copy(messages = request.messages.toList()))

    /** Internal prerequisite path only; the vector is not used as evidence or persisted. */
    fun prepareChat(request: ChatRequest, input: EncoderInput, encoder: ForegroundEncoder,
        capturedEpoch: Long, currentEpoch: () -> Long): ForegroundCall {
        val capturedLifetime = lifetimeEpoch.value
        val prepared = ForegroundPreparation(request.id, input, encoder, capturedEpoch, currentEpoch, nowMs) {
            lifetimeEpoch.value == capturedLifetime
        }
        return ForegroundCall(chatFlow(request.copy(messages = request.messages.toList()), prepared), prepared.state) {
            prepared.cancelByUser()
            scope.launch { if (foreground === prepared) cancelActive(interrupted = false).join() }
        }
    }

    /** Internal encoder-only prerequisite. No synthetic chat prompt or Gemma generation. */
    fun encode(id: String, input: EncoderInput, encoder: ForegroundEncoder,
        capturedEpoch: Long, currentEpoch: () -> Long): EncodingCall {
        val capturedLifetime = lifetimeEpoch.value
        val prepared = ForegroundPreparation(id, input, encoder, capturedEpoch, currentEpoch, nowMs) {
            lifetimeEpoch.value == capturedLifetime
        }
        val results = channelFlow {
            val worker = scope.launch {
                if (!prepared.claim()) {
                    trySend(EncodingOutcome(EncodingStatus.REJECTED, ModelError.BUSY)); close(); return@launch
                }
                if (!enabled || releasing > 0 || !operation.tryLock()) {
                    val error = if (!enabled) ModelError.NOT_INSTALLED else ModelError.BUSY
                    prepared.finished(error)
                    trySend(EncodingOutcome(if (!enabled) EncodingStatus.UNAVAILABLE else EncodingStatus.REJECTED, error)); close(); return@launch
                }
                var outcome = EncodingOutcome(EncodingStatus.FAILED, ModelError.ENCODER_FAILED)
                var monitor: Job? = null
                try {
                    foreground = prepared
                    generation = currentCoroutineContext().job
                    chatCancellation = ChatOutcome.CANCELLED
                    if (poisoned) throw ModelException(ModelError.RESTART_REQUIRED)
                    mutable.update { it.copy(error = null, commandError = null) }
                    idle?.cancel()
                    prepared.reclaiming()
                    monitor = monitorPreparation(prepared)
                    if (!cleanupOwnedEngine()) throw ModelException(ModelError.RESTART_REQUIRED)
                    prepared.checkLive()
                    memoryPolicy.blockedReason(resources(), MemoryAdmission.LOADING)?.let { throw ModelException(it) }
                    val values = prepared.prepare()
                    prepared.checkLive()
                    outcome = EncodingOutcome(EncodingStatus.COMPLETED, values = values)
                } catch (_: CancellationException) {
                    outcome = EncodingOutcome(if (prepared.userCancelled) EncodingStatus.CANCELLED
                        else if (state.value.error == ModelError.ENCODER_TIMEOUT) EncodingStatus.TIMED_OUT
                        else EncodingStatus.INTERRUPTED, if (prepared.userCancelled) null else state.value.error)
                } catch (failure: Exception) {
                    val error = (failure as? ModelException)?.error ?: ModelError.ENCODER_FAILED
                    outcome = EncodingOutcome(when {
                        prepared.userCancelled -> EncodingStatus.CANCELLED
                        error == ModelError.ENCODER_TIMEOUT -> EncodingStatus.TIMED_OUT
                        error == ModelError.ENCODER_REJECTED && prepared.state.value.admissionError == AdmissionError.UNAVAILABLE -> EncodingStatus.UNAVAILABLE
                        error in setOf(ModelError.ENCODER_REJECTED, ModelError.CONTEXT_INVALIDATED) -> EncodingStatus.REJECTED
                        else -> EncodingStatus.FAILED
                    }, if (prepared.userCancelled) null else error)
                } finally {
                    monitor?.cancel()
                    if (!prepared.cleanup()) {
                        poisoned = true
                        outcome = EncodingOutcome(EncodingStatus.FAILED, ModelError.RESTART_REQUIRED)
                        mutable.update { it.copy(status = RuntimeStatus.FAILED, error = ModelError.RESTART_REQUIRED) }
                    }
                    prepared.finished(outcome.error)
                    foreground = null; generation = null
                    operation.unlock()
                    trySend(outcome); close()
                }
            }
            try {
                worker.join()
                if (trySend(EncodingOutcome(EncodingStatus.INTERRUPTED, ModelError.CONTEXT_INVALIDATED)).isSuccess) {
                    prepared.finished(ModelError.CONTEXT_INVALIDATED); close()
                }
            } finally {
                if (!worker.isCompleted) withContext(NonCancellable) {
                    prepared.invalidate(); worker.cancel()
                    withContext(scope.coroutineContext.minusKey(Job)) {
                        if (foreground === prepared) cancelActive(interrupted = true).join()
                    }
                    worker.join()
                }
            }
        }
        return EncodingCall(results, prepared.state) {
            prepared.cancelByUser()
            scope.launch { if (foreground === prepared) cancelActive(interrupted = false).join() }
        }
    }

    private fun monitorPreparation(prepared: ForegroundPreparation): Job = scope.launch {
        val window = MemoryPressureWindow(memoryPolicy)
        var lastSample = nowMs() - memoryPolicy.sampleIntervalMs
        while (isActive) {
            try {
                prepared.checkLive()
                if (nowMs() - lastSample >= memoryPolicy.sampleIntervalMs) {
                    lastSample = nowMs()
                    window.observe(resources())?.let { throw ModelException(it) }
                }
            } catch (failure: ModelException) {
                mutable.update { it.copy(error = failure.error) }
                prepared.invalidate(failure.error)
                cancelActive(interrupted = true)
                break
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutable.update { it.copy(error = ModelError.RESOURCE_UNKNOWN) }
                prepared.invalidate(ModelError.RESOURCE_UNKNOWN)
                cancelActive(interrupted = true)
                break
            }
            delay(100)
        }
    }

    private fun chatFlow(request: ChatRequest, prepared: ForegroundPreparation? = null): Flow<ChatEvent> = channelFlow {
        val worker = scope.launch {
            if (prepared != null && !prepared.claim()) {
                trySend(ChatEvent.Terminal(request.id, ChatOutcome.REJECTED, ModelError.BUSY))
                close(); return@launch
            }
            if (!enabled || releasing > 0 || !operation.tryLock()) {
                prepared?.finished(if (!enabled) ModelError.NOT_INSTALLED else ModelError.BUSY)
                trySend(ChatEvent.Terminal(request.id, ChatOutcome.REJECTED,
                    if (!enabled) ModelError.NOT_INSTALLED else ModelError.BUSY))
                close()
                return@launch
            }
            var terminal = ChatEvent.Terminal(request.id, ChatOutcome.FAILED, ModelError.GENERATION_FAILED)
            var monitor: Job? = null
            var preparationMonitor: Job? = null
            try {
                foreground = prepared
                chatId = request.id
                generation = currentCoroutineContext().job
                chatCancellation = ChatOutcome.CANCELLED
                GemmaChatBudget.validate(request)
                if (poisoned) throw ModelException(ModelError.RESTART_REQUIRED)
                if (prepared != null && request.outputTokens != 512) throw ModelException(ModelError.CONTEXT_LIMIT)
                mutable.update { it.copy(error = null, commandError = null) }
                idle?.cancel()
                if (prepared != null) {
                    prepared.reclaiming()
                    preparationMonitor = monitorPreparation(prepared)
                    if (!cleanupOwnedEngine()) throw ModelException(ModelError.RESTART_REQUIRED)
                    prepared.checkLive()
                    memoryPolicy.blockedReason(resources(), MemoryAdmission.LOADING)?.let { throw ModelException(it) }
                    prepared.prepare()
                    if (prepared.state.value.cleanup != EncoderCleanup.CONFIRMED) {
                        poisoned = true
                        throw ModelException(ModelError.RESTART_REQUIRED)
                    }
                    prepared.admitGemma()
                }
                if (ownsEngine && state.value.status == RuntimeStatus.FAILED) {
                    if (!cleanupOwnedEngine()) throw ModelException(ModelError.RESTART_REQUIRED)
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
                    recordMemory(MemoryStage.BASELINE, request.backend)
                    val path = assets.verify() ?: throw ModelException(ModelError.NOT_INSTALLED)
                    withContext(NonCancellable) {
                        recordMemory(MemoryStage.LOADING, request.backend)
                        prepared?.checkLive()
                        engine.load(ModelConfig(path, manifest.sha256, request.contextTokens, request.backend,
                            request.outputTokens)).getOrThrow()
                        ownsEngine = true
                        recordMemory(MemoryStage.LOADED_IDLE, request.backend)
                    }
                }
                currentCoroutineContext().ensureActive()
                prepared?.checkLive()
                memoryPolicy.blockedReason(resources(), MemoryAdmission.GENERATION)?.let { throw ModelException(it) }
                mutable.update { it.copy(status = RuntimeStatus.GENERATING, error = null, commandError = null) }
                monitor = resourceMonitor()
                recordMemory(MemoryStage.PREFILL, request.backend)
                var completed = false
                var nonempty = false
                engine.generate(GenerationRequest("", request.outputTokens, request)).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> if (event.text.isNotEmpty()) {
                            if (!nonempty) recordMemory(MemoryStage.DECODING, request.backend)
                            nonempty = true
                            trySend(ChatEvent.Text(request.id, event.text))
                        }
                        is GenerationEvent.Finished -> {
                            completed = true
                            if (prepared != null) mutable.update { it.copy(inputTokens = event.inputTokens, outputTokens = event.tokens) }
                        }
                        is GenerationEvent.Failed -> throw ModelException(event.error)
                        GenerationEvent.Cancelled -> throw CancellationException()
                    }
                }
                terminal = if (completed && nonempty) ChatEvent.Terminal(request.id, ChatOutcome.COMPLETED)
                    else ChatEvent.Terminal(request.id, ChatOutcome.FAILED, ModelError.GENERATION_FAILED)
            } catch (_: CancellationException) {
                terminal = ChatEvent.Terminal(request.id,
                    if (prepared?.userCancelled == true) ChatOutcome.CANCELLED
                    else if (state.value.error == ModelError.ENCODER_TIMEOUT) ChatOutcome.TIMED_OUT else chatCancellation,
                    if (prepared?.userCancelled == true) null else state.value.error)
            } catch (failure: Exception) {
                val error = (failure as? ModelException)?.error ?: ModelError.GENERATION_FAILED
                if (prepared?.userCancelled == true) terminal = ChatEvent.Terminal(request.id, ChatOutcome.CANCELLED)
                else if (error == ModelError.ENCODER_TIMEOUT) terminal = ChatEvent.Terminal(request.id, ChatOutcome.TIMED_OUT, error)
                else if (error in setOf(ModelError.BUSY, ModelError.CONTEXT_LIMIT, ModelError.ENCODER_REJECTED, ModelError.CONTEXT_INVALIDATED))
                    terminal = ChatEvent.Terminal(request.id, ChatOutcome.REJECTED, error)
                else {
                    fail(failure)
                    terminal = ChatEvent.Terminal(request.id, ChatOutcome.FAILED, error)
                }
            } finally {
                preparationMonitor?.cancel()
                if (prepared != null && !prepared.cleanup()) {
                    poisoned = true
                    terminal = ChatEvent.Terminal(request.id, ChatOutcome.FAILED, ModelError.RESTART_REQUIRED)
                    mutable.update { it.copy(status = RuntimeStatus.FAILED, error = ModelError.RESTART_REQUIRED) }
                }
                monitor?.cancel()
                foreground = null
                chatId = null
                generation = null
                if (state.value.error in resourceErrors && !cleanupOwnedEngine())
                    terminal = ChatEvent.Terminal(request.id, ChatOutcome.FAILED, ModelError.RESTART_REQUIRED)
                mutable.update { if (it.status in setOf(RuntimeStatus.GENERATING, RuntimeStatus.CANCELLING, RuntimeStatus.LOADING))
                    it.copy(status = if (ownsEngine) RuntimeStatus.LOADED else RuntimeStatus.UNLOADED) else it }
                if (state.value.status == RuntimeStatus.LOADED) withContext(NonCancellable) {
                    recordMemory(MemoryStage.LOADED_IDLE, state.value.backend)
                }
                prepared?.finished(terminal.error)
                operation.unlock()
                scheduleIdle()
                trySend(terminal)
                close()
            }
        }
        try {
            worker.join()
            // A cancelled manager scope can prevent the worker body from starting at all.
            if (trySend(ChatEvent.Terminal(request.id, ChatOutcome.INTERRUPTED,
                    ModelError.CONTEXT_INVALIDATED)).isSuccess) {
                prepared?.finished(ModelError.CONTEXT_INVALIDATED)
                close()
            }
        }
        finally { if (!worker.isCompleted) withContext(NonCancellable) {
            worker.cancel()
            if (prepared == null) cancelChat(request.id, interrupted = true)
            else withContext(scope.coroutineContext.minusKey(Job)) {
                prepared.invalidate()
                if (foreground === prepared) cancelActive(interrupted = true).join()
            }
            worker.join()
        } }
    }

    override suspend fun cancelChat(id: String, interrupted: Boolean) {
        withContext(scope.coroutineContext.minusKey(Job)) {
            if (foreground == null && chatId == id) cancelActive(interrupted).join()
        }
    }

    private fun resourceMonitor(): Job = scope.launch {
        val window = MemoryPressureWindow(memoryPolicy)
        while (isActive) {
            delay(memoryPolicy.sampleIntervalMs)
            val reason = window.observe(resources())
            if (reason != null) {
                mutable.update { it.copy(error = reason) }
                cancelActive(interrupted = true)
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
        recordMemory(MemoryStage.BASELINE, backend)
        val path = assets.verify() ?: throw ModelException(ModelError.NOT_INSTALLED)
        val started = nowMs()
        withContext(NonCancellable) {
            recordMemory(MemoryStage.LOADING, backend)
            engine.load(ModelConfig(path, manifest.sha256, manifest.configuredContextTokens, backend,
                manifest.maxOutputTokens)).getOrThrow()
            ownsEngine = true
            recordMemory(MemoryStage.LOADED_IDLE, backend)
        }
        memoryPolicy.blockedReason(resources(), MemoryAdmission.GENERATION)?.let { throw ModelException(it) }
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
                recordMemory(MemoryStage.PREFILL, state.value.backend)
                engine.generate(GenerationRequest(prompt, manifest.maxOutputTokens)).collect { event ->
                    when (event) {
                        is GenerationEvent.Token -> {
                            if (state.value.firstTokenMs == null) recordMemory(MemoryStage.DECODING, state.value.backend)
                            mutable.update {
                                if (it.status != RuntimeStatus.GENERATING) it else it.copy(
                                    output = it.output + event.text, firstTokenMs = it.firstTokenMs ?: (nowMs() - started))
                            }
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
            if (state.value.error in resourceErrors) cleanupOwnedEngine()
            mutable.update { if (it.status in setOf(RuntimeStatus.GENERATING, RuntimeStatus.CANCELLING))
                it.copy(status = RuntimeStatus.LOADED) else it }
            if (state.value.status == RuntimeStatus.LOADED) withContext(NonCancellable) {
                recordMemory(MemoryStage.LOADED_IDLE, state.value.backend)
            }
            operation.unlock()
            scheduleIdle()
        }
    }

    // Never acquire the operation mutex here: generation owns it until native completion.
    fun cancel(interrupted: Boolean = false): Job = scope.launch {
        if (chatId != null || foreground != null) { reject(ModelError.BUSY); return@launch }
        cancelActive(interrupted).join()
    }

    private fun cancelActive(interrupted: Boolean = false): Job {
        val targetGeneration = generation
        val targetAsset = assetWork
        val targetForeground = foreground
        return scope.launch {
            if (!enabled || generation !== targetGeneration || assetWork !== targetAsset || foreground !== targetForeground)
                return@launch
            targetAsset?.cancel()
            val active = targetGeneration ?: return@launch
            // The cancelled job may finish before the native signal returns. Do not admit
            // another owner while that signal could still act on the engine.
            releasing++
            val signal = currentCoroutineContext().job
            cancellationSignals += signal
            try {
                mutable.update { it.copy(commandError = null) }
                chatCancellation = if (interrupted) ChatOutcome.INTERRUPTED else ChatOutcome.CANCELLED
                mutable.update { if (it.status == RuntimeStatus.GENERATING) it.copy(status = RuntimeStatus.CANCELLING) else it }
                targetForeground?.invalidate()
                active.cancel()
                try { engine.cancel() } catch (failure: Exception) { fail(failure) }
                recordMemory(MemoryStage.CANCELLING, state.value.backend)
            } finally { cancellationSignals -= signal; releasing-- }
        }
    }

    fun release(): Job = releaseOwned(privileged = false)
    fun releaseForHostLoss(): Job {
        lifetimeEpoch.update { it + 1 }
        return releaseOwned(privileged = true)
    }
    private fun releaseOwned(privileged: Boolean): Job = scope.launch {
        if (!enabled) return@launch
        if (!privileged && (chatId != null || foreground != null)) { reject(ModelError.BUSY); return@launch }
        releasing++
        try {
            mutable.update { it.copy(commandError = null) }
            idle?.cancel()
            foreground?.invalidate()
            cancelActive(interrupted = true).join()
            // A previous generation can finish before its cancellation/telemetry job.
            // Release is not quiescent until every already-owned signal has settled.
            while (cancellationSignals.isNotEmpty()) cancellationSignals.toList().joinAll()
            operation.withLock {
                if (poisoned) return@withLock
                if (cleanupOwnedEngine()) mutable.update { it.copy(status = RuntimeStatus.UNLOADED, error = null) }
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
            else {
                fail(failure)
                if (state.value.error in resourceErrors) cleanupOwnedEngine()
            }
        }
        finally { operation.unlock() }
    }

    private fun reject(error: ModelError) {
        mutable.update { it.copy(commandError = error) }
    }

    // Caller owns operation; never await public release() while holding that mutex.
    private suspend fun cleanupOwnedEngine(): Boolean = withContext(NonCancellable) {
        if (poisoned) return@withContext false
        if (!ownsEngine) return@withContext true
        mutable.update { it.copy(status = RuntimeStatus.RELEASING) }
        try {
            withTimeout(5_000) { engine.release() }
            ownsEngine = false
            mutable.update { it.copy(status = RuntimeStatus.UNLOADED, cleanupError = null) }
            recordMemory(MemoryStage.POST_RELEASE, state.value.backend)
            true
        } catch (failure: Exception) {
            poisoned = true
            mutable.update { it.copy(status = RuntimeStatus.FAILED, error = ModelError.RESTART_REQUIRED,
                cleanupError = (failure as? ModelException)?.error ?: ModelError.RELEASE_FAILED) }
            false
        }
    }

    private val resourceErrors = setOf(ModelError.LOW_MEMORY, ModelError.THERMAL, ModelError.RESOURCE_UNKNOWN)

    private suspend fun recordMemory(stage: MemoryStage, backend: ModelBackend) {
        try { memoryTelemetry(MemoryTracePoint(stage, backend, state.value.status, ownsEngine, nowMs(), memoryPolicy.id)) }
        catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            mutable.update { it.copy(telemetryFailed = true) }
        }
        catch (_: Exception) { mutable.update { it.copy(telemetryFailed = true) } }
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

    suspend fun shutdown() { releaseForHostLoss().join(); scope.cancel() }
}
