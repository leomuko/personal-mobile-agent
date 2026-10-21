package dev.edgecompanion.app.prerequisites

import android.content.*
import android.os.*
import dev.edgecompanion.core.inference.embedding.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.*

data class EncoderWorkerEvent(val phase: String, val monotonicMs: Long, val pssKb: Int, val pid: Int)
data class EncoderWorkerEvidence(val instance: String? = null, val events: List<EncoderWorkerEvent> = emptyList(),
    val confirmedDeath: Boolean = false, val deathAtMs: Long? = null)

/** QA-only platform adapter. ModelManager owns scheduling; each worker owns one native encode. */
class AndroidForegroundEncoder(private val context: Context, private val model: File,
    private val resourcesSafe: () -> Boolean) : ForegroundEncoder {
    private val mutableEvidence = MutableStateFlow(EncoderWorkerEvidence())
    val evidence = mutableEvidence.asStateFlow()
    private var admission: AndroidUseAdmission? = null
    private val active = AtomicReference<Worker?>()
    private val cancellationBatch = AtomicBoolean()
    fun useCancellationBatch(enabled: Boolean) { cancellationBatch.set(enabled) }

    override suspend fun admit(input: EncoderInput): EncoderAdmission {
        return try {
            if (admission == null) admission = AndroidUseAdmission.open(model, internalBuild = true, SdkPacketTransport::bytes)
            requireNotNull(admission).admit(input)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { EncoderAdmission.Rejected(AdmissionError.UNAVAILABLE) }
    }
    override fun accepts(receipt: EncoderAdmissionReceipt, input: EncoderInput) = admission?.matches(receipt, input) == true

    override suspend fun encode(request: EncoderRequest): EncoderResult = withContext(Dispatchers.IO) {
        if (!accepts(request.receipt, request.receipt.input)) return@withContext EncoderResult.Rejected(AdmissionError.MISMATCH)
        val worker = Worker(request.lease)
        if (!active.compareAndSet(null, worker)) {
            worker.disposeUnused()
            return@withContext EncoderResult.Failed
        }
        mutableEvidence.value = EncoderWorkerEvidence(instance = worker.instance)
        worker.start(request.receipt)
        while (true) {
            val event = select<Bundle?> {
                worker.events.onReceiveCatching { it.getOrNull() }
                worker.dead.onAwait { null }
            } ?: withTimeoutOrNull(100) { worker.events.receive() } ?: return@withContext EncoderResult.Failed
            when (event.getString("phase")) {
                "ENCODING" -> {
                    val vector = event.getFloatArray("vector") ?: return@withContext EncoderResult.Failed
                    return@withContext EncoderResult.Completed(request.lease, vector.toList())
                }
                "REJECTED" -> return@withContext EncoderResult.Rejected(
                    AdmissionError.entries.firstOrNull { it.name == event.getString("reason") } ?: AdmissionError.UNAVAILABLE)
                "FAILED" -> return@withContext EncoderResult.Failed
            }
        }
        @Suppress("UNREACHABLE_CODE") EncoderResult.Failed
    }

    override fun cancel(lease: EncoderLease) { active.get()?.takeIf { it.lease === lease }?.stop() }
    override suspend fun cleanup(lease: EncoderLease): Boolean {
        val worker = active.get() ?: return true
        if (worker.lease !== lease) return false
        val confirmed = worker.close()
        if (confirmed) active.compareAndSet(worker, null)
        return confirmed
    }

    private inner class Worker(val lease: EncoderLease) {
        private val id = UUID.randomUUID().toString()
        val instance: String get() = id
        private val parent = Binder()
        private val stopped = AtomicBoolean()
        private val disposed = AtomicBoolean()
        private val connected = CompletableDeferred<Messenger>()
        val dead = CompletableDeferred<Unit>()
        val events = Channel<Bundle>(16)
        private val thread = HandlerThread("encoder-foreground").apply { start() }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var endpoint: Messenger? = null
        @Volatile private var bound = false
        private var attemptedBind = false
        private val replies = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if (message.data.getString("id") != id) return
                mutableEvidence.update { old -> old.copy(events = (old.events + EncoderWorkerEvent(
                    message.data.getString("phase").orEmpty(), message.data.getLong("elapsedMs"),
                    message.data.getInt("pssKb"), message.data.getInt("pid"))).takeLast(32)) }
                if (!events.trySend(message.data).isSuccess) stop()
            }
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try { service.linkToDeath({
                    mutableEvidence.update { it.copy(confirmedDeath = true, deathAtMs = SystemClock.elapsedRealtime()) }
                    dead.complete(Unit)
                }, 0) }
                catch (failure: RemoteException) { dead.complete(Unit); connected.completeExceptionally(failure); return }
                endpoint = Messenger(service)
                connected.complete(requireNotNull(endpoint))
                if (stopped.get()) stop()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Null binding")) }
            override fun onBindingDied(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Binding died")) }
        }

        suspend fun start(receipt: EncoderAdmissionReceipt) {
            currentCoroutineContext().ensureActive()
            attemptedBind = true
            bound = context.bindIsolatedService(Intent(context, EmbeddingGateService::class.java), Context.BIND_AUTO_CREATE,
                id.replace("-", ""), context.mainExecutor, connection)
            check(bound)
            val target = connected.await()
            currentCoroutineContext().ensureActive()
            if (stopped.get()) throw CancellationException()
            check(resourcesSafe())
            ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                target.send(Message.obtain().apply {
                    what = 1; replyTo = replies
                    data = Bundle().apply {
                        putString("id", id); putBinder("parent", parent); putParcelable("model", descriptor)
                        putString("text", receipt.input.text); putLong("deadline", SystemClock.elapsedRealtime() + 5_000)
                        putString("inputDigest", receipt.transportSha256); putInt("expectedTokens", receipt.responseTokens)
                        putBoolean("cancelLoop", cancellationBatch.get())
                        putLong("resourceUntil", SystemClock.elapsedRealtime() + 500)
                    }
                })
            }
            scope.launch {
                while (isActive && !dead.isCompleted) {
                    val safe = try { resourcesSafe() } catch (_: Exception) { false }
                    if (!safe) { stop(); return@launch }
                    try { target.send(Message.obtain().apply {
                        what = 4; data = Bundle().apply {
                            putString("id", id); putLong("resourceUntil", SystemClock.elapsedRealtime() + 500)
                        }
                    }) } catch (_: Exception) { stop(); return@launch }
                    delay(100)
                }
            }
        }
        fun stop() {
            stopped.set(true)
            try { endpoint?.send(Message.obtain().apply { what = 2; data = Bundle().apply { putString("id", id) } }) }
            catch (_: DeadObjectException) { dead.complete(Unit) }
            catch (_: RemoteException) { }
        }
        suspend fun close(): Boolean = withContext(NonCancellable) {
            if (disposed.get()) return@withContext dead.isCompleted || !attemptedBind
            stop()
            val confirmed = if (!attemptedBind || !bound) true else try {
                withTimeout(4_000) { connected.await(); stop(); dead.await() }; true
            } catch (_: Exception) { dead.isCompleted }
            scope.coroutineContext.job.cancelAndJoin()
            if (bound) { context.unbindService(connection); bound = false }
            thread.quitSafely()
            withContext(Dispatchers.IO) { thread.join(500) }
            events.close()
            disposed.set(true)
            confirmed && !thread.isAlive
        }
        fun disposeUnused() { scope.cancel(); thread.quitSafely(); events.close() }
    }
}
