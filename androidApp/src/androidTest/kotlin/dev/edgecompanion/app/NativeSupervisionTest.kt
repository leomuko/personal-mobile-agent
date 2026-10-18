package dev.edgecompanion.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.support.DeviceSupervision
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.litert.LiteRtLlmEngine
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** Destructive only to this QA process. No conversation/database writes or user payloads. */
class NativeSupervisionTest {
    @Test fun lossDuringRealNativeWork(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeSupervision") == "true")
        val target = requireNotNull(args.getString("nativePhase"))
        require(target in setOf("INITIALIZING", "PREFILL", "DECODING", "RECOVERY"))
        require(args.getString("supervisedThermal") == "true")
        val id = requireNotNull(args.getString("supervisionRunId"))
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && BuildConfig.SECURE_WINDOWS && context.packageName.endsWith(".secureqa"))
        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val policy = koin.get<ModelManager>().memoryPolicy
        val resources = AndroidModelResources(context)
        val directory = File(context.noBackupFilesDir, "qa-supervision/$id")
        val prompt = if (target == "PREFILL") ModelTestPrompts.SUPERVISION_PREFILL else ModelTestPrompts.CANCELLATION
        val promptSha = MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray())
            .joinToString("") { "%02x".format(it) }
        data class Entry(val phase: String, val elapsedMs: Long)
        val timeline = AtomicReference<List<Entry>>(emptyList())
        // Never block SDK callbacks on file I/O, resource probes or the reporter thread.
        val engine = LiteRtLlmEngine(true) { phase ->
            val entry = Entry(phase.name, SystemClock.elapsedRealtime())
            timeline.updateAndGet { it + entry }
        }
        val manager = ModelManager(koin.get<ModelAssets>(), engine, Dispatchers.Default, resources::snapshot,
            SystemClock::elapsedRealtime, enabled = true, memoryPolicy = policy)
        fun evidence(): JSONObject {
            val trace = timeline.get()
            val captured = SystemClock.elapsedRealtime()
            val sample = resources.snapshot()
            return JSONObject().put("schema", 1).put("runId", id).put("target", target)
                .put("phase", trace.lastOrNull()?.phase ?: "VERIFYING").put("elapsedMs", captured)
                .put("timeline", JSONArray().apply { trace.forEach {
                    put(JSONObject().put("phase", it.phase).put("elapsedMs", it.elapsedMs))
                } }).put("backend", "GPU")
                .put("automaticFallback", false).put("policyId", policy.id)
                .put("modelSha256", ModelCatalog.gemma4.sha256)
                .put("runtimeVersion", ModelCatalog.RUNTIME_VERSION)
                .put("promptSha256", promptSha).put("contextTokens", 2048).put("maxOutputTokens", 512)
                .put("runtimeStatus", manager.state.value.status.name)
                .put("runtimeError", manager.state.value.error?.name ?: JSONObject.NULL)
                .put("availableMemoryBytes", sample.availableMemoryBytes ?: JSONObject.NULL)
                .put("pressureThresholdBytes", sample.pressureThresholdBytes ?: JSONObject.NULL)
                .put("lowMemory", sample.lowMemory).put("thermalStatus", sample.thermalStatus ?: JSONObject.NULL)
        }
        fun persist(name: String, report: JSONObject) {
            val temp = File(directory, "$name.tmp")
            temp.writeText(report.toString())
            check(temp.renameTo(File(directory, "$name.json")))
        }
        DeviceSupervision.open(id) {
            val report = evidence().put("lossObserved", true)
            persist("native-loss", report)
            runBlocking { manager.release().join() }
            report.put("cleanupFinishedMs", SystemClock.elapsedRealtime())
                .put("cleanupStatus", manager.state.value.status.name)
            persist("native-loss", report)
            manager.state.value.status == RuntimeStatus.UNLOADED
        }.use {
            val reporter = launch(Dispatchers.IO) {
                while (isActive) { persist("native-phase", evidence()); delay(25) }
            }
            try {
                withTimeout(150_000) {
                    manager.load(ModelBackend.GPU).join()
                    check(manager.state.value.status == RuntimeStatus.LOADED) {
                        "Native load blocked/failed: ${manager.state.value.error}"
                    }
                    if (target == "INITIALIZING") error("Native initialization completed without observed supervision loss")
                    manager.testPrompt(prompt).join()
                    if (target != "RECOVERY") error("Native generation completed without observed supervision loss")
                    check(manager.state.value.error == null && manager.state.value.outputTokens != null)
                    check(manager.state.value.output.isNotBlank())
                    manager.release().join()
                    check(manager.state.value.status == RuntimeStatus.UNLOADED)
                    persist("native-recovery", evidence().put("passed", true))
                }
            } finally {
                withContext(NonCancellable) {
                    reporter.cancelAndJoin()
                    persist("native-phase", evidence())
                    manager.shutdown()
                }
            }
        }
    }
}
