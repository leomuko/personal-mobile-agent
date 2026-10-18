package dev.edgecompanion.app.support

import android.content.Context
import android.os.Process
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.BuildConfig
import dev.edgecompanion.core.inference.ModelManager
import dev.edgecompanion.core.inference.RuntimeStatus
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Only compiled into test APKs. Loss is irreversible and stops the isolated QA process. */
class DeviceSupervision private constructor(
    private val context: Context,
    private val runId: String,
    private val cleanup: () -> Boolean,
) : AutoCloseable {
    private val gate = Any()
    private val lease = SupervisionLease(runId)
    private val root = File(context.noBackupFilesDir, "qa-supervision")
    private val directory = File(root, runId)
    private val owner = SupervisionOwnership.acquire(root)
    private var closed = false
    private var lost = false
    private data class Signals(val heartbeat: SupervisionHeartbeat?, val stop: Boolean = false)
    private val latest = AtomicReference(Signals(null))

    private fun readJson(name: String): JSONObject {
        val bytes = File(directory, "$name.json").inputStream().use {
            val buffer = ByteArray(4097)
            var count = 0
            while (count < buffer.size) {
                val read = it.read(buffer, count, buffer.size - count)
                if (read < 0) break
                count += read
            }
            check(count <= 4096)
            buffer.decodeToString(0, count)
        }
        return JSONObject(bytes).also { check(it.getInt("schema") == 2 && it.getString("runId") == runId) }
    }

    private fun signals(): Signals = try {
        val json = readJson("heartbeat")
        val stop = File(directory, "stop.json").exists() && readJson("stop").also {
            check(it.getString("action") == "stop")
        }.let { true }
        Signals(SupervisionHeartbeat(json.getString("runId"), json.getLong("sequence"), json.getLong("elapsedMs")), stop)
    } catch (_: Exception) { Signals(null) }

    private fun observe(): SupervisionLoss? = latest.get().let {
        lease.observe(it.heartbeat, SystemClock.elapsedRealtime(), it.stop)
    }

    private fun status(state: String, reason: SupervisionLoss? = null, cleaned: Boolean = false) {
        val json = JSONObject().put("schema", 2).put("runId", runId).put("status", state)
            .put("reason", reason?.name ?: JSONObject.NULL).put("cleanupConfirmed", cleaned)
            .put("elapsedMs", SystemClock.elapsedRealtime())
            .put("capturedAtMs", System.currentTimeMillis()).put("leaseMs", SupervisionLease.LEASE_MS)
            .put("pollMs", SupervisionLease.POLL_MS).put("cleanupMs", SupervisionLease.CLEANUP_MS)
        val temporary = File(directory, "status.tmp")
        temporary.writeText(json.toString())
        check(temporary.renameTo(File(directory, "status.json")))
    }

    init {
        try {
            check(directory.isDirectory || directory.mkdirs())
            latest.set(signals())
            val failure = observe()
            status(if (failure == null) "active" else "rejected", failure)
            check(failure == null) { "Supervision admission rejected: $failure" }
        } catch (failure: Throwable) {
            owner.close()
            throw failure
        }
    }

    private val reader = thread(name = "qa-supervision-reader", isDaemon = true) {
        while (!synchronized(gate) { closed }) {
            latest.set(signals())
            try { Thread.sleep(SupervisionLease.POLL_MS) }
            catch (_: InterruptedException) { if (synchronized(gate) { closed }) return@thread }
        }
    }

    private val watcher = thread(name = "qa-supervision", isDaemon = true) {
        while (true) {
            val failure = synchronized(gate) {
                if (closed) return@thread
                observe().also { if (it != null) lost = true }
            }
            if (failure != null) {
                val stop = CountDownLatch(1)
                thread(name = "qa-supervision-hard-stop", isDaemon = true) {
                    try { stop.await(SupervisionLease.CLEANUP_MS, TimeUnit.MILLISECONDS) }
                    finally { Process.killProcess(Process.myPid()) }
                }
                try { status("lost", failure) } catch (_: Exception) { /* A failed report must not prevent the stop. */ }
                val finished = CountDownLatch(1)
                val confirmed = AtomicBoolean(false)
                thread(name = "qa-supervision-cleanup", isDaemon = true) {
                    try { confirmed.set(cleanup()) } catch (_: Throwable) { }
                    finally { finished.countDown() }
                }
                try { finished.await(SupervisionLease.CLEANUP_MS, TimeUnit.MILLISECONDS) }
                finally {
                    try { status("terminated", failure, confirmed.get()) } catch (_: Exception) { }
                    // A separate watchdog thread cannot be wedged behind a native load/cancel call.
                    stop.countDown()
                }
                return@thread
            }
            try { Thread.sleep(SupervisionLease.POLL_MS) }
            catch (_: InterruptedException) { synchronized(gate) { if (closed) return@thread } }
        }
    }

    override fun close() {
        synchronized(gate) {
            if (observe() != null) lost = true
            check(!lost) { "Supervision was lost; this workload cannot pass" }
            closed = true
        }
        watcher.interrupt()
        reader.interrupt()
        watcher.join(1_000)
        reader.join(1_000)
        check(!watcher.isAlive)
        check(!reader.isAlive)
        status("closed")
        owner.close()
    }

    companion object {
        fun requireFor(manager: ModelManager, expectedRunId: String? = null): DeviceSupervision {
            val args = InstrumentationRegistry.getArguments()
            require(args.getString("supervisedThermal") == "true")
            val id = requireNotNull(args.getString("supervisionRunId")) { "Supervision run ID is required" }
            require(expectedRunId == null || id == expectedRunId)
            return open(id) { runBlocking {
                manager.release().join()
                manager.state.value.status == RuntimeStatus.UNLOADED
            } }
        }

        fun open(runId: String, cleanup: () -> Boolean): DeviceSupervision {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(BuildConfig.DEBUG && BuildConfig.SECURE_WINDOWS && context.packageName.endsWith(".secureqa"))
            require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
            return DeviceSupervision(context, runId, cleanup)
        }
    }
}
