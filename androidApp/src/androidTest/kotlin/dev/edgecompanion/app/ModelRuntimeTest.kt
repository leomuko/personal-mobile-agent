package dev.edgecompanion.app

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.core.inference.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** Opt-in native evidence, never replaced by a fake when assets or resources are missing. */
class ModelRuntimeTest {
    @Test fun nativeGenerationSurvivesHostHandoffAndReleasesOnBackground(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Native model test not requested", args.getString("nativeModel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = (context.applicationContext as CompanionApplication).getKoin().get<ModelManager>()
        val backend = ModelBackend.valueOf(args.getString("modelBackend") ?: "CPU")
        manager.release().join()
        try {
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
                manager.load(backend).join()
                check(manager.state.value.status == RuntimeStatus.LOADED) { "Native model unavailable" }
                val generation = manager.testPrompt(ModelTestPrompts.CANCELLATION)
                withTimeout(30_000) { manager.state.first { it.firstTokenMs != null || it.status == RuntimeStatus.FAILED } }
                main.recreate()
                check(manager.state.value.status in setOf(RuntimeStatus.GENERATING, RuntimeStatus.LOADED))
                ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use {
                    check(manager.state.value.status in setOf(RuntimeStatus.GENERATING, RuntimeStatus.LOADED))
                }
                main.moveToState(Lifecycle.State.CREATED)
                withTimeout(30_000) { manager.state.first { it.status == RuntimeStatus.UNLOADED || it.status == RuntimeStatus.FAILED } }
                check(manager.state.value.status == RuntimeStatus.UNLOADED) { "Background native cleanup failed" }
                generation.join()
                main.moveToState(Lifecycle.State.RESUMED)
                manager.load(backend).join()
                check(manager.state.value.status == RuntimeStatus.LOADED)
                withTimeout(30_000) { manager.testPrompt().join() }
                check(manager.state.value.outputTokens != null)
            }
        } finally { withContext(NonCancellable) { manager.release().join() } }
    }

    @Test fun nativeLoadStreamCancelReleaseReload(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Native model test not requested", args.getString("nativeModel") == "true")
        check(BuildConfig.DEBUG)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val manager = koin.get<ModelManager>()
        val resources = koin.get<AndroidModelResources>()
        val backend = ModelBackend.valueOf(args.getString("modelBackend") ?: "CPU")
        val repetitions = (args.getString("modelRepetitions") ?: "1").toInt().also { require(it in 1..100) }
        val sustainedMinutes = (args.getString("modelSustainedMinutes") ?: "0").toInt().also { require(it == 0 || it == 20) }
        require(sustainedMinutes == 0 || args.getString("supervisedThermal") == "true")
        val samples = JSONArray()
        val resourceSamples = JSONArray()
        val report = JSONObject().put("schema", 1).put("status", "running")
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("emulator", Build.FINGERPRINT.startsWith("generic") || Build.MODEL.contains("sdk_gphone"))
            .put("androidSdk", Build.VERSION.SDK_INT).put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
            .put("appVersion", BuildConfig.VERSION_NAME).put("modelSha256", ModelCatalog.gemma4.sha256)
            .put("runtimeVersion", ModelCatalog.RUNTIME_VERSION).put("backend", backend.name)
            .put("automaticFallback", false).put("contextTokens", 2048).put("maxOutputTokens", 512)
            .put("sampling", "topK=1;topP=1.0;temperature=0.0;seed=0;thinking=false")
            .put("cacheState", "engine-unloaded; OS file cache uncontrolled; compiler cache disabled")
            .put("skinTemperatureC", JSONObject.NULL).put("pssSampleIntervalMs", 500)
            .put("batteryBefore", battery()).put("samples", samples).put("resources", resourceSamples)
        val output = File(context.noBackupFilesDir, "model-benchmark.json")
        fun persist() { synchronized(resourceSamples) { output.writeText(report.toString(2)) } }
        persist()
        val cold = mutableListOf<Long>(); val warm = mutableListOf<Long>()
        val cancellation = mutableListOf<Long>(); val idleRelease = mutableListOf<Long>()
        val activeRelease = mutableListOf<Long>()
        val started = SystemClock.elapsedRealtime()
        val sampler = launch(Dispatchers.IO) {
            while (isActive) {
                val resource = resources.snapshot()
                synchronized(resourceSamples) {
                    resourceSamples.put(JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("pssKb", Debug.getPss()).put("thermal", resource.thermalStatus ?: JSONObject.NULL)
                        .put("availableMemoryBytes", resource.availableMemoryBytes ?: JSONObject.NULL))
                }
                delay(500)
            }
        }
        suspend fun expectLoaded() {
            check(manager.state.value.status == RuntimeStatus.LOADED) { "Runtime: ${manager.state.value.status}/${manager.state.value.error}" }
            check(manager.state.value.error == null) { "Resource/runtime failure: ${manager.state.value.error}" }
        }
        suspend fun shortPrompt(): ModelRuntimeState {
            withTimeout(180_000) { manager.testPrompt().join() }
            expectLoaded()
            return manager.state.value.also {
                val output = it.output.trim()
                check(output.isNotEmpty() && it.firstTokenMs != null && it.outputTokens != null)
                check(listOf("Message(", "Content.Text(", "Text(text=", "ImageFile(").none(output::contains)) {
                    "Native output contains a LiteRT-LM object representation"
                }
                check(output.contains(',') && Regex("[A-Za-z]+").findAll(output).count() >= 3) {
                    "Short fixture did not produce comma-separated color text"
                }
            }
        }
        suspend fun beginCancellationPrompt(): Job {
            val job = manager.testPrompt(ModelTestPrompts.CANCELLATION)
            withTimeout(180_000) { manager.state.first {
                (it.status == RuntimeStatus.GENERATING && it.firstTokenMs != null) || it.status == RuntimeStatus.FAILED
            } }
            check(manager.state.value.status == RuntimeStatus.GENERATING) { "No active stream to cancel" }
            return job
        }
        try {
            manager.release().join()
            resources.snapshot().blockedReason()?.let { error("Resource admission blocked: $it") }
            manager.verify().join()
            check(manager.assets.state.value.status == AssetStatus.INSTALLED) { "Import the pinned model into this QA app first" }
            repeat(repetitions) { index ->
                manager.load(backend).join(); expectLoaded()
                val first = shortPrompt()
                cold += checkNotNull(first.loadMs) + checkNotNull(first.firstTokenMs)
                val second = shortPrompt()
                warm += checkNotNull(second.firstTokenMs)
                samples.put(JSONObject().put("case", "short").put("index", index)
                    .put("loadMs", first.loadMs).put("coldFirstTokenMs", first.firstTokenMs)
                    .put("warmFirstTokenMs", second.firstTokenMs).put("warmElapsedMs", second.generationMs)
                    .put("outputTokens", second.outputTokens).put("inputTokens", second.inputTokens)
                    .put("decodeTokensPerSecond", second.decodeTokensPerSecond ?: JSONObject.NULL))
                check(checkNotNull(second.inputTokens) <= 1000) { "Warm fixture exceeds the accepted input budget" }
                val generating = beginCancellationPrompt()
                var mark = SystemClock.elapsedRealtime()
                manager.cancel().join(); generating.join(); expectLoaded()
                cancellation += SystemClock.elapsedRealtime() - mark
                shortPrompt() // A cancelled native conversation is never reused.
                beginCancellationPrompt()
                mark = SystemClock.elapsedRealtime(); manager.release().join()
                activeRelease += SystemClock.elapsedRealtime() - mark
                check(manager.state.value.status == RuntimeStatus.UNLOADED)
                manager.load(backend).join(); expectLoaded(); shortPrompt()
                mark = SystemClock.elapsedRealtime(); manager.release().join()
                idleRelease += SystemClock.elapsedRealtime() - mark
                check(manager.state.value.status == RuntimeStatus.UNLOADED)
                samples.put(JSONObject().put("case", "lifecycle").put("index", index)
                    .put("cancelMs", cancellation.last()).put("activeReleaseMs", activeRelease.last())
                    .put("idleReleaseMs", idleRelease.last()))
                persist()
            }
            if (sustainedMinutes > 0) {
                manager.load(backend).join(); expectLoaded()
                val until = SystemClock.elapsedRealtime() + sustainedMinutes * 60_000L
                var loops = 0
                var cancelledLoops = 0
                var reloads = 0
                val sustainedSamples = JSONArray()
                while (SystemClock.elapsedRealtime() < until) {
                    resources.snapshot().blockedReason()?.let { error("Sustained safety stop: $it") }
                    val prompt = if (loops % 2 == 0) ModelTestPrompts.SHORT else ModelTestPrompts.MEDIUM
                    withTimeout(180_000) { manager.testPrompt(prompt).join() }
                    expectLoaded()
                    val result = manager.state.value
                    check(result.output.isNotBlank() && checkNotNull(result.inputTokens) <= 1000)
                    sustainedSamples.put(JSONObject().put("index", loops).put("prompt", if (loops % 2 == 0) "short" else "medium")
                        .put("inputTokens", result.inputTokens).put("outputTokens", result.outputTokens ?: JSONObject.NULL)
                        .put("generationMs", result.generationMs ?: JSONObject.NULL))
                    loops++
                    if (loops % 5 == 0) {
                        val generating = beginCancellationPrompt()
                        manager.cancel().join(); generating.join(); expectLoaded()
                        cancelledLoops++
                    }
                    if (loops % 10 == 0) {
                        manager.release().join()
                        check(manager.state.value.status == RuntimeStatus.UNLOADED)
                        manager.load(backend).join(); expectLoaded()
                        reloads++
                    }
                    report.put("sustainedLoops", loops).put("sustainedCancelledLoops", cancelledLoops)
                        .put("sustainedReloads", reloads).put("sustainedSamples", sustainedSamples)
                    persist()
                }
                report.put("sustainedCompletedMinutes", sustainedMinutes)
            }
            fun summary(values: List<Long>, gate: Long): JSONObject {
                val result = summarizeLatency(values, gate)
                return JSONObject().put("rawMs", JSONArray(values)).put("count", result.count)
                    .put("p95Ms", result.p95Ms ?: JSONObject.NULL).put("gateMs", gate)
                    .put("gatePassed", result.gatePassed ?: JSONObject.NULL)
            }
            report.put("coldLoadAndFirstToken", summary(cold, 12_000)).put("warmFirstToken", summary(warm, 3_000))
                .put("cancel", summary(cancellation, 2_000)).put("idleRelease", summary(idleRelease, 3_000))
                .put("activeRelease", summary(activeRelease, 5_000)).put("status", "smoke_passed")
                .put("acceptanceComplete", false)
        } catch (failure: Throwable) {
            report.put("status", "failed").put("failure", failure.javaClass.simpleName)
                .put("runtimeError", manager.state.value.error?.name ?: JSONObject.NULL)
            throw failure
        } finally {
            withContext(NonCancellable) {
                sampler.cancelAndJoin()
                val peakPssKb = (0 until resourceSamples.length()).maxOfOrNull {
                    resourceSamples.getJSONObject(it).getLong("pssKb")
                }
                report.put("sampledPeakPssKb", peakPssKb ?: JSONObject.NULL)
                    .put("pssTargetGiB", 2.5)
                    .put("pssTargetMet", peakPssKb?.let { it <= 2.5 * 1024 * 1024 } ?: JSONObject.NULL)
                report.put("batteryAfter", battery()).put("elapsedMs", SystemClock.elapsedRealtime() - started)
                persist() // Keep evidence even if native shutdown subsequently cannot finish.
                manager.release().join()
            }
        }
    }

    private fun battery(): JSONObject {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val info = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return JSONObject().put("level", info?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: JSONObject.NULL)
            .put("scale", info?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: JSONObject.NULL)
            .put("plugged", info?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: JSONObject.NULL)
    }
}
