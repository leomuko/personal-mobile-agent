package dev.edgecompanion.app.inference

import android.os.SystemClock
import dev.edgecompanion.core.inference.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Opt-in, bounded metadata only. No prompts, responses, file names, heap dumps or keys. */
class MemoryTelemetryRecorder(private val resources: AndroidModelResources,
    private val policy: MemoryPolicy = MemoryPolicies.Current) {
    private val enabled = AtomicBoolean(false)
    private val point = AtomicReference(MemoryTracePoint(MemoryStage.BASELINE, ModelBackend.GPU,
        RuntimeStatus.UNLOADED, false, 0, policy.id))
    private val lock = Mutex()
    private val samples = mutableListOf<JSONObject>()
    private var started = 0L
    private var dropped = 0
    private var failedSamples = 0

    suspend fun start() = lock.withLock {
        samples.clear(); dropped = 0; failedSamples = 0; started = SystemClock.elapsedRealtime()
        point.set(point.get().copy(stage = MemoryStage.BASELINE, monotonicMs = started))
        enabled.set(true)
    }
    fun stop() { enabled.set(false) }

    suspend fun baseline() = recordStage(MemoryTracePoint(MemoryStage.BASELINE, ModelBackend.GPU,
        RuntimeStatus.UNLOADED, false, SystemClock.elapsedRealtime(), policy.id))

    suspend fun recordStage(value: MemoryTracePoint) {
        point.set(value)
        if (enabled.get()) sample()
    }

    suspend fun sample() {
        if (!enabled.get()) return
        val captured = point.get()
        val resource = try { resources.telemetrySnapshot() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { lock.withLock { failedSamples++ }; return }
        if (resource.appPssBytes == null || resource.appRssBytes == null || resource.pressureThresholdBytes == null)
            lock.withLock { failedSamples++ }
        val entry = JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
            .put("stage", captured.stage.name).put("backend", captured.backend.name)
            .put("runtimeStatusAtEvent", captured.runtimeStatus.name).put("engineOwnedAtEvent", captured.engineOwned)
            .put("eventElapsedMs", captured.monotonicMs - started).put("policyId", captured.policyId)
            .put("availableMemoryBytes", resource.availableMemoryBytes ?: JSONObject.NULL)
            .put("pressureThresholdBytes", resource.pressureThresholdBytes ?: JSONObject.NULL)
            .put("lowMemory", resource.lowMemory).put("thermalStatus", resource.thermalStatus ?: JSONObject.NULL)
            .put("appPssBytes", resource.appPssBytes ?: JSONObject.NULL)
            .put("appRssBytes", resource.appRssBytes ?: JSONObject.NULL)
        lock.withLock { if (samples.size < 10_000) samples += entry else dropped++ }
    }

    suspend fun report(): JSONObject = lock.withLock {
        JSONObject().put("schema", 1).put("policy", JSONObject(policy.exportFields()))
            .put("startedAtMonotonicMs", started)
            .put("samples", JSONArray(samples)).put("droppedSamples", dropped)
            .put("failedSamples", failedSamples)
            .put("elapsedMs", SystemClock.elapsedRealtime() - started)
    }
}
