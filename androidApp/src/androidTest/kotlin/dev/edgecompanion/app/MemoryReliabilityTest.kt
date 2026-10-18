package dev.edgecompanion.app

import android.app.KeyguardManager
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.support.DeviceSupervision
import dev.edgecompanion.core.inference.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** Synthetic prompts only; neither personal conversation storage nor other apps are touched. */
class MemoryReliabilityTest {
    @Test fun gpuAllocationCyclesAndSustainedConversation(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("nativeMemory") == "true")
        require(args.getString("supervisedThermal") == "true")
        val runId = requireNotNull(args.getString("memoryRunId")) { "Supervised run ID is required" }
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val cycles = (args.getString("memoryCycles") ?: "5").toInt().also { require(it in 3..100) }
        val minutes = (args.getString("memorySustainedMinutes") ?: "20").toInt().also { require(it == 0 || it == 20) }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the authorized test phone" }
        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val manager = koin.get<ModelManager>()
        val pressureMode = args.getString("memoryPreflightMode") == "android-pressure"
        check(manager.memoryPolicy.enforceAvailableMemoryFloor == !pressureMode) { "Workload/profile mode mismatch" }
        DeviceSupervision.requireFor(manager, runId).use {
        val recorder = koin.get<MemoryTelemetryRecorder>()
        val resources = koin.get<AndroidModelResources>()
        val output = File(context.noBackupFilesDir, "memory-reliability.json")
        val observations = JSONArray()
        val report = JSONObject().put("schema", 1).put("runId", runId).put("status", "running")
            .put("startedAtMs", System.currentTimeMillis()).put("policy", JSONObject(manager.memoryPolicy.exportFields()))
            .put("backend", "GPU").put("automaticFallback", false).put("requestedCycles", cycles)
            .put("requestedSustainedMinutes", minutes).put("sampleIntervalMs", 500).put("observations", observations)
        var requestNumber = 0
        val history = mutableListOf<Pair<String, String>>()
        suspend fun persist() = withContext(Dispatchers.IO) {
            report.put("telemetry", recorder.report())
            val staged = File(output.parentFile, "memory-reliability.tmp")
            staged.writeText(report.toString(2))
            check(staged.renameTo(output))
        }
        suspend fun chat(cancel: Boolean = false): String {
            val id = "memory-${requestNumber++}"
            val prompt = if (cancel) "List one hundred household objects, one per line." else "Name three colors, separated by commas."
            val first = CompletableDeferred<Unit>()
            var text = ""
            var terminal: ChatEvent.Terminal? = null
            coroutineScope {
                val job = launch {
                    manager.generateChat(GemmaChatBudget.build(prompt, history.toList(), id)).collect { event ->
                        when (event) {
                            is ChatEvent.Text -> { text += event.delta; first.complete(Unit) }
                            is ChatEvent.Terminal -> {
                                terminal = event
                                if (!first.isCompleted) first.completeExceptionally(IllegalStateException("No stream: ${event.error}"))
                            }
                        }
                    }
                }
                withTimeout(180_000) { first.await() }
                if (cancel) manager.cancelChat(id)
                withTimeout(180_000) { job.join() }
            }
            check(terminal?.outcome == if (cancel) ChatOutcome.CANCELLED else ChatOutcome.COMPLETED) {
                "Native outcome ${terminal?.outcome}/${terminal?.error}"
            }
            check(!manager.state.value.telemetryFailed) { "Critical memory sample failed" }
            if (!cancel) {
                history += prompt to text
                if (history.size > 4) history.removeAt(0)
            }
            return text
        }
        manager.release().join()
        recorder.start()
        recorder.baseline()
        persist()
        val sampler = launch(Dispatchers.IO) {
            var count = 0
            while (isActive) {
                recorder.sample()
                if (count++ % 10 == 0) {
                    val staged = File(context.noBackupFilesDir, "memory-samples.tmp")
                    staged.writeText(recorder.report().toString(2))
                    check(staged.renameTo(File(context.noBackupFilesDir, "memory-samples.json")))
                }
                delay(500)
            }
        }
        try {
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use {
                repeat(cycles) { index ->
                    recorder.baseline()
                    val before = resources.telemetrySnapshot()
                    val answer = chat()
                    chat(cancel = true)
                    manager.release().join()
                    check(manager.state.value.status == RuntimeStatus.UNLOADED)
                    delay(5_000)
                    recorder.sample()
                    val after = resources.telemetrySnapshot()
                    observations.put(JSONObject().put("cycle", index).put("responseChars", answer.length)
                        .put("baselinePssBytes", before.appPssBytes ?: JSONObject.NULL)
                        .put("postReleasePssBytes", after.appPssBytes ?: JSONObject.NULL)
                        .put("postReleaseRssBytes", after.appRssBytes ?: JSONObject.NULL)
                        .put("postReleaseAvailableBytes", after.availableMemoryBytes ?: JSONObject.NULL)
                        .put("postReleaseLowMemory", after.lowMemory)
                        .put("postReleaseStatus", manager.state.value.status.name))
                    report.put("completedCycles", index + 1)
                    persist()
                }
                val deadline = SystemClock.elapsedRealtime() + minutes * 60_000L
                var turns = 0
                while (SystemClock.elapsedRealtime() < deadline) {
                    chat(cancel = turns % 5 == 4)
                    turns++
                    if (turns % 10 == 0) { manager.release().join(); delay(5_000) }
                    report.put("sustainedTurns", turns)
                    persist()
                }
                report.put("completedSustainedMinutes", minutes).put("status", "passed")
                    .put("acceptanceComplete", false)
            }
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failureType", failure.javaClass.simpleName)
                .put("runtimeError", manager.state.value.error?.name ?: JSONObject.NULL)
                .put("cleanupError", manager.state.value.cleanupError?.name ?: JSONObject.NULL)
            throw failure
        } finally {
            withContext(NonCancellable) {
                manager.release().join(); delay(5_000); recorder.sample()
                sampler.cancelAndJoin(); recorder.stop()
                val uncertain = manager.state.value.status != RuntimeStatus.UNLOADED
                if (uncertain) report.put("status", "failed").put("cleanupError", manager.state.value.cleanupError?.name)
                val trace = recorder.report()
                val invalidTelemetry = trace.getInt("failedSamples") > 0 || trace.getInt("droppedSamples") > 0
                if (invalidTelemetry) report.put("status", "failed").put("telemetryIncomplete", true)
                report.put("finishedAtMs", System.currentTimeMillis())
                persist()
                check(!uncertain) { "Native cleanup is unconfirmed" }
                check(!invalidTelemetry) { "Memory telemetry is incomplete" }
            }
        }
        }
    }
}
