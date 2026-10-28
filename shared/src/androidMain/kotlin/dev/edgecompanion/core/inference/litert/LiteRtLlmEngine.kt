package dev.edgecompanion.core.inference.litert

import com.google.ai.edge.litertlm.*
import dev.edgecompanion.core.inference.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import dev.edgecompanion.core.inference.verification.ConstrainedDecoding
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(ExperimentalApi::class)
class LiteRtLlmEngine(
    private val enabled: Boolean,
    private val constrainedQaEnabled: Boolean = false,
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val phaseObserver: ((LiteRtPhase) -> Unit)? = null,
) : LocalLlmEngine {
    private val lifecycle = Mutex()
    private val handles = Any()
    private var engine: Engine? = null
    private var config: ModelConfig? = null
    @Volatile private var active: Running? = null
    @Volatile private var poisoned = false
    @Volatile private var activeConstraint: ConstrainedSession? = null

    private class Running(val conversation: Conversation) {
        val done = CompletableDeferred<Unit>()
        val events = Channel<GenerationEvent>(Channel.UNLIMITED)
        val cancelled = AtomicBoolean(false)
        val failed = AtomicBoolean(false)
        val firstText = AtomicBoolean(false)
        var started = false
    }

    override suspend fun load(config: ModelConfig): Result<Unit> = lifecycle.withLock {
        withContext(NonCancellable + Dispatchers.IO) {
            if (!enabled || poisoned) return@withContext Result.failure(ModelException(ModelError.RESTART_REQUIRED))
            if (engine != null) return@withContext Result.failure(ModelException(ModelError.BUSY))
            var candidate: Engine? = null
            try {
                ExperimentalFlags.enableBenchmark = true
                ExperimentalFlags.enableSpeculativeDecoding = false
                Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
                candidate = Engine(EngineConfig(modelPath = config.path,
                    backend = if (config.backend == ModelBackend.CPU) Backend.CPU(threadCount = 4) else Backend.GPU(),
                    visionBackend = null, audioBackend = null, maxNumTokens = config.contextTokens,
                    cacheDir = ":nocache"))
                // No automatic backend retry: failed initialization cleanup must first be proved on this SDK/device.
                phase(LiteRtPhase.INITIALIZING)
                candidate.initialize()
                engine = candidate
                this@LiteRtLlmEngine.config = config
                phase(LiteRtPhase.LOADED)
                Result.success(Unit)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LinkageError) { Result.failure(ModelException(ModelError.UNSUPPORTED_DEVICE)) }
            catch (_: Exception) {
                // The SDK does not expose cleanup of a partially initialized handle. Do not retry over it.
                poisoned = candidate != null
                Result.failure(ModelException(if (poisoned) ModelError.RESTART_REQUIRED else ModelError.LOAD_FAILED))
            }
        }
    }

    override fun generate(request: GenerationRequest): Flow<GenerationEvent> = flow {
        lifecycle.withLock {
            if (poisoned) throw ModelException(ModelError.RESTART_REQUIRED)
            val current = config ?: throw ModelException(ModelError.NOT_INSTALLED)
            val constraint = request.constraint
            if (constraint != null) {
                if (!enabled || !constrainedQaEnabled) throw ModelException(ModelError.CONTEXT_LIMIT)
                val chat = request.chat ?: throw ModelException(ModelError.CONTEXT_LIMIT)
                val checkLive = request.constraintEligibility ?: throw ModelException(ModelError.CONTEXT_LIMIT)
                constraint.check(chat)
                // Rehash independently at the platform boundary; no injectable hash can authorize native work.
                ConstrainedDecoding.admit(constraint.caseId, chat.id, constraint.payload, constraint.question,
                    constraint.schema) { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }
                if (current.sha256 != ModelCatalog.gemma4.sha256 || current.backend != ModelBackend.GPU ||
                    current.contextTokens != 2048 || current.maxOutputTokens != 512 || request.maxTokens != constraint.decodeTokenCap)
                    throw ModelException(ModelError.CONTEXT_LIMIT)
                val bound = GemmaChatBudget.validate(chat)
                val session = ConstrainedSession(constraint, monotonicMs, Dispatchers.IO, create = {
                    val conversation = checkNotNull(engine).createConversation(ConversationConfig(
                        automaticToolCalling = false, enableResponseFormat = true,
                        maxOutputToken = constraint.decodeTokenCap, thinkingConfig = ThinkingConfig(enableThinking = false),
                        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)))
                    object : ConstraintHandle {
                        override fun send(callback: ConstraintCallback) {
                            conversation.sendMessageAsync(constraint.payload, object : MessageCallback {
                                override fun onMessage(message: Message) = callback.text(extractText(message))
                                override fun onDone() = callback.done()
                                override fun onError(throwable: Throwable) = callback.error(throwable)
                            }, responseFormat = ResponseFormat.json(constraint.schema))
                        }
                        override fun statistics(): GenerationEvent.Finished {
                            val info = conversation.getBenchmarkInfo()
                            if (info.lastPrefillTokenCount > bound || info.lastDecodeTokenCount > constraint.decodeTokenCap)
                                throw ModelException(ModelError.CONTEXT_LIMIT)
                            return GenerationEvent.Finished(info.lastDecodeTokenCount, info.lastPrefillTokenCount,
                                info.lastDecodeTokensPerSecond.takeIf { it.isFinite() && it >= 0 })
                        }
                        override fun cancel() = conversation.cancelProcess()
                        override fun close() = conversation.close()
                    }
                }, uncertain = { poisoned = true }, checkLive = checkLive)
                activeConstraint = session
                try { emitAll(session.events()) } finally { activeConstraint = null }
                return@withLock
            }
            // This spike accepts only bounded synthetic fixtures. Free-form input waits for a verified preflight tokenizer.
            if (request.maxTokens !in 1..current.maxOutputTokens)
                throw ModelException(ModelError.CONTEXT_LIMIT)
            val chat = request.chat
            if (chat == null && request.prompt !in ModelTestPrompts.allowed) throw ModelException(ModelError.CONTEXT_LIMIT)
            if (chat != null) {
                if (current.sha256 != ModelCatalog.gemma4.sha256 || current.contextTokens != chat.contextTokens ||
                    request.maxTokens != chat.outputTokens) throw ModelException(ModelError.CONTEXT_LIMIT)
                GemmaChatBudget.validate(chat)
            }
            var owned: Running? = null
            try {
                // Publish ownership before crossing a cancellable dispatcher boundary.
                withContext(NonCancellable + Dispatchers.IO) {
                    val conversation = (engine ?: throw ModelException(ModelError.NOT_INSTALLED))
                        .createConversation(ConversationConfig(automaticToolCalling = false,
                            initialMessages = chat?.messages?.dropLast(1)?.map {
                                if (it.role == ChatRole.USER) Message.user(it.text) else Message.model(it.text)
                            }.orEmpty(),
                            maxOutputToken = request.maxTokens, thinkingConfig = ThinkingConfig(enableThinking = false),
                            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)))
                    owned = Running(conversation)
                    active = owned
                }
                val running = checkNotNull(owned)
                val conversation = running.conversation
                currentCoroutineContext().ensureActive()
                withContext(Dispatchers.IO) {
                    synchronized(handles) {
                        if (running.cancelled.get()) {
                            running.done.complete(Unit)
                            running.events.close()
                        } else {
                            try {
                                phase(LiteRtPhase.PREFILL)
                                conversation.sendMessageAsync(chat?.messages?.last()?.text ?: request.prompt, object : MessageCallback {
                                    override fun onMessage(message: Message) {
                                        val text = extractText(message)
                                        if (text.isNotEmpty() && running.firstText.compareAndSet(false, true))
                                            phase(LiteRtPhase.DECODING)
                                        if (text.isNotEmpty() && !running.cancelled.get() && active === running)
                                            running.events.trySend(GenerationEvent.Token(text))
                                    }
                                    override fun onDone() {
                                        phase(LiteRtPhase.TERMINAL)
                                        running.done.complete(Unit); running.events.close()
                                    }
                                    override fun onError(throwable: Throwable) {
                                        phase(LiteRtPhase.TERMINAL)
                                        running.failed.set(true)
                                        if (throwable !is java.util.concurrent.CancellationException)
                                            running.events.trySend(GenerationEvent.Failed(ModelError.GENERATION_FAILED))
                                        running.done.complete(Unit)
                                        running.events.close()
                                    }
                                })
                                running.started = true
                            } catch (failure: Exception) {
                                running.done.complete(Unit)
                                throw ModelException(ModelError.GENERATION_FAILED)
                            }
                        }
                    }
                }
                for (event in running.events) if (!running.cancelled.get()) emit(event)
                if (running.cancelled.get()) emit(GenerationEvent.Cancelled)
                else if (!running.failed.get()) {
                    val info = withContext(Dispatchers.IO) { conversation.getBenchmarkInfo() }
                    if (chat != null && info.lastPrefillTokenCount > GemmaChatBudget.validate(chat))
                        throw ModelException(ModelError.CONTEXT_LIMIT)
                    emit(GenerationEvent.Finished(info.lastDecodeTokenCount, info.lastPrefillTokenCount,
                        info.lastDecodeTokensPerSecond.takeIf { it.isFinite() && it >= 0 }))
                }
            } finally {
                owned?.let { running -> withContext(NonCancellable + Dispatchers.IO) {
                    signalCancel(running)
                    val stopped = withTimeoutOrNull(5_000) { running.done.await(); true } ?: false
                    if (!stopped) {
                        poisoned = true
                        throw ModelException(ModelError.CANCEL_TIMEOUT)
                    }
                    synchronized(handles) {
                        try { running.conversation.close() }
                        catch (_: Exception) { poisoned = true; throw ModelException(ModelError.RELEASE_FAILED) }
                        finally { active = null; running.events.cancel() }
                    }
                } }
            }
        }
    }

    override suspend fun cancel() = withContext(Dispatchers.IO) {
        activeConstraint?.cancel()
        active?.let { signalCancel(it) }
        Unit
    }

    private fun signalCancel(running: Running) {
        val firstSignal = running.cancelled.compareAndSet(false, true)
        synchronized(handles) {
            if (firstSignal && active === running && running.started && !running.done.isCompleted) {
                try { running.conversation.cancelProcess() }
                catch (_: Exception) { poisoned = true; throw ModelException(ModelError.RESTART_REQUIRED) }
            }
            else if (!running.started) { running.done.complete(Unit); running.events.close() }
        }
    }

    override suspend fun release() {
        cancel()
        lifecycle.withLock {
            withContext(Dispatchers.IO) {
                if (poisoned) throw ModelException(ModelError.RESTART_REQUIRED)
                try { engine?.close(); engine = null; config = null; phase(LiteRtPhase.RELEASED) }
                catch (_: Exception) { poisoned = true; throw ModelException(ModelError.RELEASE_FAILED) }
            }
        }
    }

    // Optional metadata-only QA observation; never let diagnostics change native ownership.
    private fun phase(value: LiteRtPhase) {
        if (enabled) try { phaseObserver?.invoke(value) } catch (_: Exception) { }
    }
}

/** PREFILL spans send submission to the first nonempty text callback; no artificial delay. */
enum class LiteRtPhase { INITIALIZING, LOADED, PREFILL, DECODING, TERMINAL, RELEASED }

internal fun extractText(message: Message): String = message.contents.contents
    .mapNotNull { (it as? Content.Text)?.text }
    .joinToString(separator = "")
