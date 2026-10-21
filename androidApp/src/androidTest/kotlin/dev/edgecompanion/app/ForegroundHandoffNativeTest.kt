package dev.edgecompanion.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.app.support.DeviceSupervision
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.embedding.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** Opt-in integration prerequisites, not semantic retrieval or the standalone Gemma smoke. */
class ForegroundHandoffNativeTest {
    @Test fun verifiedEncoderCleanupGatesGemmaAndRecovery(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("p2Foreground") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        val manager = (context.applicationContext as CompanionApplication).getKoin().get<ModelManager>()
        val resources = AndroidModelResources(context)
        val telemetry = (context.applicationContext as CompanionApplication).getKoin().get<MemoryTelemetryRecorder>()
        val model = File(context.noBackupFilesDir, "qa-embedding/$MODEL_SHA.tflite")
        // Reflection keeps the debug instrumentation APK compilable without QA-only dependencies.
        val type = Class.forName("dev.edgecompanion.app.prerequisites.AndroidForegroundEncoder")
        val safe: () -> Boolean = { manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null }
        val real = type.constructors.single().newInstance(context, model, safe) as ForegroundEncoder
        @Suppress("UNCHECKED_CAST")
        val evidence = type.getMethod("getEvidence").invoke(real) as StateFlow<Any>
        fun field(value: Any, getter: String): Any? = value.javaClass.getMethod(getter).invoke(value)
        val report = JSONObject().put("schema", 1).put("runId", args.getString("supervisionRunId"))
            .put("modelSha256", MODEL_SHA).put("runtime", "com.google.mediapipe:tasks-text:1.1.1")
            .put("startedAtMs", System.currentTimeMillis()).put("passed", false)
            .put("contextTokens", 2048).put("outputReservation", 512).put("fullRetrievalVerified", false)
        val samples = JSONArray(); report.put("cases", samples)
        val output = File(context.noBackupFilesDir, "p2-foreground-gate.json")
        var active: ForegroundCall? = null
        DeviceSupervision.open(requireNotNull(args.getString("supervisionRunId"))) {
            runBlocking { active?.cancel(); manager.releaseForHostLoss().join(); manager.state.value.status == RuntimeStatus.UNLOADED }
        }.use {
            try {
                manager.releaseForHostLoss().join()
                check(manager.state.value.status == RuntimeStatus.UNLOADED)
                for (mode in listOf("cold", "resident-reclaim", "cancel-counting", "cancel-loading", "cancel-encoding", "timeout-before-handoff", "fresh-worker-recovery")) {
                    val snapshot = resources.snapshot()
                    check(manager.memoryPolicy.blockedReason(snapshot, MemoryAdmission.LOADING) == null)
                    val sample = JSONObject().put("mode", mode).put("policyId", manager.memoryPolicy.id)
                        .put("lowMemory", snapshot.lowMemory).put("thermalStatus", snapshot.thermalStatus).put("passed", false)
                    samples.put(sample); output.writeText(report.toString(2))
                    type.getMethod("useCancellationBatch", Boolean::class.javaPrimitiveType).invoke(real, mode == "cancel-encoding")
                    val encoder = if (mode == "timeout-before-handoff") object : ForegroundEncoder by real {
                        override suspend fun encode(request: EncoderRequest): EncoderResult {
                            val result = real.encode(request)
                            delay(5_100) // Deliberately withhold a real result; no native hang is claimed.
                            return result
                        }
                    } else real
                    val previousInstance = field(evidence.value, "getInstance")
                    val question = when (mode) {
                        "resident-reclaim" -> "Reply READY for this synthetic record: " + "x ".repeat(600)
                        "fresh-worker-recovery" -> "Reply with READY. Synthetic Unicode note: 日本語 🙂 cafe\u0301."
                        else -> "Reply with exactly the word READY. Synthetic context: pilot on 6 October; John owns the contract."
                    }
                    val request = ChatRequest(mode, listOf(ChatMessage(ChatRole.USER, question)))
                    sample.put("inputBound", GemmaChatBudget.validate(request)).put("backend", "GPU")
                    val call = manager.prepareChat(request,
                        EncoderInput(if (mode == "fresh-worker-recovery") "Synthetic 日本語 🙂 pilot." else "Synthetic pilot launch on 6 October. John owns the contract."), encoder, 1) { 1 }
                    active = call
                    val started = SystemClock.elapsedRealtime()
                    telemetry.start()
                    val collection = async { call.events.toList() }
                    try {
                        if (mode == "cold") {
                            withTimeout(5_000) { call.preparation.first { it.stage == PreparationStage.ENCODING || it.stage == PreparationStage.GEMMA } }
                            manager.cancel().join(); manager.release().join(); manager.load(ModelBackend.CPU).join(); manager.testPrompt().join()
                            assertEquals(ModelError.BUSY, manager.state.value.commandError)
                            assertFalse(collection.isCompleted)
                            sample.put("panelIsolationChecked", true)
                        }
                        if (mode == "cancel-counting") {
                            withTimeout(5_000) { call.preparation.first { it.stage == PreparationStage.COUNTING } }
                            call.cancel()
                        }
                        if (mode == "cancel-loading" || mode == "cancel-encoding") {
                            val target = if (mode == "cancel-loading") "LOADING" else "ENCODING"
                            withTimeout(5_000) {
                                evidence.first { value ->
                                    @Suppress("UNCHECKED_CAST") val events = field(value, "getEvents") as List<Any>
                                    field(value, "getInstance") != previousInstance && events.any { field(it, "getPhase") == target }
                                }
                            }
                            call.cancel()
                        }
                        val events = withTimeout(90_000) { collection.await() }
                        assertEquals(1, events.count { it is ChatEvent.Terminal })
                        val terminal = events.last() as ChatEvent.Terminal
                        val trace = telemetry.report()
                        val traceStart = trace.getLong("startedAtMonotonicMs")
                        val memory = trace.getJSONArray("samples")
                        val loads = (0 until memory.length()).map { memory.getJSONObject(it) }
                            .filter { it.getString("stage") == "LOADING" }.map { traceStart + it.getLong("eventElapsedMs") }
                        sample.put("parentMemory", trace)
                        val cancelled = mode.startsWith("cancel-")
                        if (cancelled) assertEquals(ChatOutcome.CANCELLED, terminal.outcome)
                        else if (mode == "timeout-before-handoff") {
                            assertEquals(ModelError.ENCODER_TIMEOUT, terminal.error)
                            assertEquals(ChatOutcome.TIMED_OUT, terminal.outcome)
                        }
                        else {
                            assertEquals("Native preparation failed: ${terminal.error}", ChatOutcome.COMPLETED, terminal.outcome)
                            val text = events.filterIsInstance<ChatEvent.Text>().joinToString("") { it.delta }
                            assertTrue(text.contains("READY", ignoreCase = true))
                            assertFalse(text.contains("Message(")); assertFalse(text.contains("Content.Text("))
                            assertTrue(requireNotNull(call.preparation.value.retrievalMs) < 5_000)
                            val actualInput = requireNotNull(manager.state.value.inputTokens)
                            requireContextBudget(actualInput, 512, 2048)
                            sample.put("sdkInputTokens", actualInput)
                        }
                        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
                        sample.put("outcome", terminal.outcome.name).put("error", terminal.error?.name)
                            .put("encoderCleanup", call.preparation.value.cleanup.name)
                            .put("preparationMs", call.preparation.value.retrievalMs)
                            .put("totalMs", SystemClock.elapsedRealtime() - started)
                        val worker = evidence.value
                        sample.put("workerInstance", field(worker, "getInstance"))
                        if (mode != "cancel-counting") {
                            assertNotEquals(previousInstance, field(worker, "getInstance"))
                            assertEquals(true, field(worker, "getConfirmedDeath"))
                            sample.put("confirmedDeath", true)
                            val deathAtMs = field(worker, "getDeathAtMs") as Long
                            sample.put("encoderDeathAtMs", deathAtMs).put("gemmaLoadStarts", JSONArray(loads))
                            if (cancelled || mode == "timeout-before-handoff") assertTrue(loads.isEmpty())
                            else { assertTrue(loads.isNotEmpty()); assertTrue(loads.all { it >= deathAtMs }) }
                            @Suppress("UNCHECKED_CAST") val phases = field(worker, "getEvents") as List<Any>
                            sample.put("workerEvents", JSONArray(phases.map { event -> JSONObject()
                                .put("phase", field(event, "getPhase")).put("monotonicMs", field(event, "getMonotonicMs"))
                                .put("pssKb", field(event, "getPssKb")).put("pid", field(event, "getPid")) }))
                        } else { assertEquals(previousInstance, field(worker, "getInstance")); sample.put("nativeEncoderInvoked", false) }
                        sample.put("passed", true)
                    } finally {
                        call.cancel(); collection.cancelAndJoin(); telemetry.stop(); active = null
                        if (mode != "cold") {
                            manager.releaseForHostLoss().join()
                            check(manager.state.value.status == RuntimeStatus.UNLOADED) { "Gemma cleanup not confirmed" }
                        }
                        output.writeText(report.toString(2))
                    }
                }
                report.put("passed", true)
            } finally {
                active?.cancel(); telemetry.stop(); manager.releaseForHostLoss().join()
                if (manager.state.value.status != RuntimeStatus.UNLOADED) report.put("passed", false).put("gemmaCleanup", "UNCERTAIN")
                else report.put("gemmaCleanup", "CONFIRMED")
                report.put("finishedAtMs", System.currentTimeMillis()); output.writeText(report.toString(2))
            }
        }
    }
    companion object { private const val MODEL_SHA = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749" }
}
