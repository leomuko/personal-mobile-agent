package dev.edgecompanion.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.support.DeviceSupervision
import dev.edgecompanion.core.inference.MemoryAdmission
import dev.edgecompanion.core.inference.ModelManager
import dev.edgecompanion.core.inference.RuntimeStatus
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

class EmbeddingPrerequisiteTest {
    @Test fun isolatedEncoderColdWarmCancellationAndRecovery(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("p2Embedding") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        val manager = (context.applicationContext as CompanionApplication).getKoin().get<ModelManager>()
        val resources = AndroidModelResources(context)
        val report = JSONObject().put("schema", 1).put("runId", args.getString("supervisionRunId"))
            .put("startedAtMs", System.currentTimeMillis()).put("modelSha256", MODEL_SHA)
            .put("runtime", "com.google.mediapipe:tasks-text:1.1.1").put("passed", false)
        val samples = JSONArray()
        report.put("cases", samples)
        val path = File(context.noBackupFilesDir, "p2-embedding-gate.json")
        var active: Worker? = null
        DeviceSupervision.open(requireNotNull(args.getString("supervisionRunId"))) {
            runBlocking { active?.stopAndAwait(); active == null || active!!.dead.isCompleted }
        }.use {
            try {
                check(manager.state.value.status != RuntimeStatus.GENERATING)
                manager.release().join()
                check(manager.state.value.status == RuntimeStatus.UNLOADED)
                check(manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null)
                val model = File(context.noBackupFilesDir, "qa-embedding/$MODEL_SHA.tflite")
                check(model.length() == 6120274L) { "Verified QA encoder candidate is not provisioned" }
                for (mode in listOf("cold-warm", "cancel-active-batch", "fresh-recovery")) {
                    check(manager.state.value.status == RuntimeStatus.UNLOADED)
                    val snapshot = resources.snapshot()
                    check(manager.memoryPolicy.blockedReason(snapshot, MemoryAdmission.LOADING) == null)
                    val sample = JSONObject().put("mode", mode).put("lowMemory", snapshot.lowMemory)
                        .put("thermalStatus", snapshot.thermalStatus).put("availableMemoryBytes", snapshot.availableMemoryBytes)
                        .put("policyId", manager.memoryPolicy.id).put("events", JSONArray())
                    samples.put(sample); path.writeText(report.toString(2))
                    val started = SystemClock.elapsedRealtime()
                    val worker = Worker(context, model, started + 5000) {
                        manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null
                    }
                    active = worker
                    try {
                        worker.start(mode == "cancel-active-batch")
                        var result: Bundle? = null
                        withTimeout(6000) {
                            while (true) {
                                val event = worker.events.receive()
                                val phase = event.getString("phase")
                                sample.getJSONArray("events").put(JSONObject().put("phase", phase)
                                    .put("elapsedMs", event.getLong("elapsedMs")).put("pssKb", event.getInt("pssKb"))
                                    .put("failureClass", event.getString("failureClass")))
                                if (phase == "FAILED" || phase == "STOPPED") error("Encoder prerequisite failed: $phase")
                                if (phase == "ENCODING") sample.put("heads", event.getInt("heads"))
                                if (phase == "RESULT") result = event
                                if (phase == "ENCODING" && mode == "cancel-active-batch") {
                                    val cancelStarted = SystemClock.elapsedRealtime()
                                    worker.stopAndAwait()
                                    val elapsed = SystemClock.elapsedRealtime() - cancelStarted
                                    sample.put("cancelToDeathMs", elapsed)
                                    check(elapsed <= 2000)
                                    break
                                }
                                if (phase == "CLOSED") {
                                    withTimeout(2000) { worker.dead.await() }
                                    break
                                }
                            }
                        }
                        check(worker.dead.isCompleted)
                        val elapsed = SystemClock.elapsedRealtime() - started
                        sample.put("confirmedDeath", true).put("nativeReleasedMs", elapsed)
                        if (mode != "cancel-active-batch") {
                            check(elapsed <= 5000) { "Cold encoder + release missed the retrieval deadline" }
                            val completed = requireNotNull(result)
                            sample.put("dimensions", completed.getInt("dimensions"))
                                .put("loadMs", completed.getLong("loadMs"))
                                .put("encodeMs", JSONArray(completed.getLongArray("encodeMs")))
                        }
                    } finally { worker.close(); active = null }
                    val total = SystemClock.elapsedRealtime() - started
                    sample.put("totalMs", total)
                    check(total <= 5000) { "Worker/monitor cleanup missed the retrieval deadline" }
                    sample.put("passed", true)
                    path.writeText(report.toString(2))
                }
                report.put("passed", true)
            } finally {
                active?.close()
                report.put("finishedAtMs", System.currentTimeMillis())
                path.writeText(report.toString(2))
            }
        }
    }

    private class Worker(private val context: Context, private val model: File, private val deadline: Long,
        private val resourcesSafe: () -> Boolean) {
        private val id = UUID.randomUUID().toString()
        private val parent = Binder()
        val events = Channel<Bundle>(16)
        val dead = CompletableDeferred<Unit>()
        private val connected = CompletableDeferred<Messenger>()
        private val thread = HandlerThread("encoder-gate-replies").apply { start() }
        private val replies = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(message: Message) {
                if (message.data.getString("id") == id && !events.trySend(message.data).isSuccess) requestStop()
            }
        })
        private var bound = false
        private val closed = AtomicBoolean()
        private var endpoint: Messenger? = null
        private val resourceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var resourceJob: Job? = null
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                try { service.linkToDeath({ dead.complete(Unit) }, 0) }
                catch (failure: RemoteException) {
                    dead.complete(Unit); connected.completeExceptionally(failure); return
                }
                val value = Messenger(service)
                endpoint = value
                connected.complete(value)
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
            override fun onNullBinding(name: ComponentName) { connected.completeExceptionally(IllegalStateException("Null worker")) }
            override fun onBindingDied(name: ComponentName) {
                connected.completeExceptionally(IllegalStateException("Worker binding died"))
            }
        }

        suspend fun start(cancelLoop: Boolean) {
            bound = context.bindIsolatedService(Intent().setComponent(ComponentName(context.packageName,
                "dev.edgecompanion.app.prerequisites.EmbeddingGateService")), Context.BIND_AUTO_CREATE,
                id.replace("-", ""), context.mainExecutor, connection)
            check(bound)
            val service = withTimeout(5000) { connected.await() }
            check(resourcesSafe())
            ParcelFileDescriptor.open(model, ParcelFileDescriptor.MODE_READ_ONLY).use { file ->
                service.send(Message.obtain().apply {
                    what = 1; replyTo = replies
                    data = Bundle().apply {
                        putString("id", id); putBinder("parent", parent); putParcelable("model", file)
                        putLong("deadline", deadline); putBoolean("cancelLoop", cancelLoop)
                        putLong("resourceUntil", SystemClock.elapsedRealtime() + 500)
                    }
                })
            }
            // Isolated UIDs cannot access every resource service. The owning app checks
            // the shared policy; loss of these fresh leases makes the worker self-stop.
            resourceJob = resourceScope.launch {
                while (isActive && !dead.isCompleted) {
                    val safe = try { resourcesSafe() } catch (_: Exception) { false }
                    if (!safe) { requestStop(); break }
                    try {
                        service.send(Message.obtain().apply {
                            what = 4; data = Bundle().apply {
                                putString("id", id); putLong("resourceUntil", SystemClock.elapsedRealtime() + 500)
                            }
                        })
                    } catch (_: RemoteException) { requestStop(); break }
                    delay(100)
                }
            }
        }
        private fun requestStop() {
            try { endpoint?.send(Message.obtain().apply { what = 2; data = Bundle().apply { putString("id", id) } }) }
            catch (_: DeadObjectException) { dead.complete(Unit) }
        }
        suspend fun stopAndAwait() { requestStop(); withTimeout(5000) { dead.await() } }
        suspend fun close() = withContext(NonCancellable) {
            if (!closed.compareAndSet(false, true)) return@withContext
            try { if (endpoint != null && !dead.isCompleted) stopAndAwait() }
            finally {
                resourceScope.cancel()
                try { withTimeout(1000) { resourceJob?.join() } }
                finally {
                    try { if (bound) { bound = false; context.unbindService(connection) } }
                    finally {
                        thread.quitSafely()
                        withContext(Dispatchers.IO) { thread.join(1000) }
                        events.close()
                        check(!thread.isAlive)
                    }
                }
            }
        }
    }
    companion object { private const val MODEL_SHA = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749" }
}
