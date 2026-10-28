package dev.edgecompanion.core.inference.litert

import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.verification.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.flow

/** Vendor-free handle seam for deterministic setup/send/cancel/close races on the host. */
internal interface ConstraintHandle {
    fun send(callback: ConstraintCallback)
    fun statistics(): GenerationEvent.Finished
    fun cancel()
    fun close()
}
internal interface ConstraintCallback {
    fun text(value: String)
    fun done()
    fun error(value: Throwable)
}

/** One conversation, under the adapter's existing lifecycle lock. Never owns a model or scheduler. */
internal class ConstrainedSession(
    private val constraint: ConstrainedDecoding,
    private val nowMs: () -> Long,
    private val dispatcher: CoroutineDispatcher,
    private val create: suspend () -> ConstraintHandle,
    private val uncertain: () -> Unit,
    private val checkLive: () -> Unit = {},
) {
    private val signalLock = Any()
    private val cancelled = AtomicBoolean(false)
    private val terminal = AtomicBoolean(false)
    private val first = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val done = CompletableDeferred<Unit>()
    private val events = Channel<GenerationEvent>(Channel.UNLIMITED)
    @Volatile private var handle: ConstraintHandle? = null
    @Volatile private var started = false
    @Volatile private var closed = false
    private var unknownSendFailure = false

    private fun point(phase: ConstraintPhase, detail: String? = null) = constraint.trace.record(phase, nowMs(), detail)

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) point(ConstraintPhase.CANCEL_SIGNAL)
        // No lifecycle/send/setup lock is held here. A not-yet-published handle retains the signal.
        synchronized(signalLock) {
            if (!closed && started && !terminal.get()) {
                try { handle?.cancel() } catch (_: Exception) { uncertain() }
            }
        }
    }

    fun events() = flow {
        try {
            currentCoroutineContext().ensureActive()
            checkLive()
            if (cancelled.get()) { emit(GenerationEvent.Cancelled); return@flow }
            withContext(NonCancellable + dispatcher) {
                checkLive()
                point(ConstraintPhase.SETUP_ENTER)
                try {
                    handle = create()
                } catch (failure: Exception) {
                    point(ConstraintPhase.NATIVE_ERROR, diagnostic(failure))
                    // A throwing setup exposes no reclaimable partial native handle.
                    uncertain()
                    throw ModelException(ModelError.RESTART_REQUIRED)
                } finally { point(ConstraintPhase.SETUP_RETURN) }
                point(ConstraintPhase.HANDLE_PUBLISHED)
            }
            currentCoroutineContext().ensureActive()
            if (!cancelled.get()) {
                withContext(NonCancellable + dispatcher) {
                    if (!cancelled.get()) {
                        checkLive()
                        started = true
                        point(ConstraintPhase.SEND_ENTER)
                        try {
                            checkNotNull(handle).send(object : ConstraintCallback {
                                override fun text(value: String) {
                                    constraint.trace.receiveText(value)
                                    if (terminal.get() || value.isEmpty()) return
                                    if (first.compareAndSet(false, true)) point(ConstraintPhase.FIRST_TEXT)
                                    if (!cancelled.get()) events.trySend(GenerationEvent.Token(value))
                                }
                                override fun done() = finish(null)
                                override fun error(value: Throwable) = finish(value)
                            })
                        } catch (failure: Exception) {
                            // No terminal callback proves quiescence after an arbitrary synchronous throw.
                            unknownSendFailure = !terminal.get()
                            point(ConstraintPhase.NATIVE_ERROR, diagnostic(failure))
                            if (unknownSendFailure) uncertain()
                            throw ModelException(if (unknownSendFailure) ModelError.RESTART_REQUIRED else ModelError.GENERATION_FAILED)
                        } finally { point(ConstraintPhase.SEND_RETURN) }
                        if (cancelled.get()) this@ConstrainedSession.cancel()
                    }
                }
                if (started) for (event in events) if (!cancelled.get()) emit(event)
            }
            if (cancelled.get()) emit(GenerationEvent.Cancelled)
            else if (!failed.get()) emit(withContext(dispatcher) { checkNotNull(handle).statistics() })
        } finally {
            withContext(NonCancellable + dispatcher) {
                // Normal completion must not be reported as a test cancellation signal.
                synchronized(signalLock) {
                    if (started && !terminal.get() && !closed) try { handle?.cancel() }
                    catch (_: Exception) { uncertain() }
                }
                val quiescent = !started || (!unknownSendFailure &&
                    (withTimeoutOrNull(5_000) { done.await(); true } ?: false))
                if (!quiescent) {
                    uncertain()
                    throw ModelException(ModelError.CANCEL_TIMEOUT)
                }
                synchronized(signalLock) {
                    try { handle?.let { it.close(); closed = true; point(ConstraintPhase.CLOSED); constraint.trace.seal() } }
                    catch (_: Exception) { uncertain(); throw ModelException(ModelError.RELEASE_FAILED) }
                    finally { events.cancel() }
                }
            }
        }
    }

    private fun finish(error: Throwable?) {
        if (!terminal.compareAndSet(false, true)) return
        if (error != null) {
            failed.set(true)
            point(ConstraintPhase.NATIVE_ERROR, diagnostic(error))
            if (!cancelled.get()) events.trySend(GenerationEvent.Failed(ModelError.GENERATION_FAILED))
        }
        point(ConstraintPhase.TERMINAL)
        done.complete(Unit)
        events.close()
    }

    private fun diagnostic(error: Throwable) = generateSequence(error) { it.cause }.take(4)
        .joinToString(" | ") { it.javaClass.name + ": " + it.message.orEmpty().take(768) }.take(1024)
}
