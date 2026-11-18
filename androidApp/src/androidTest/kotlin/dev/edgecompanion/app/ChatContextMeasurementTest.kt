package dev.edgecompanion.app

import android.app.KeyguardManager
import android.os.Build
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.app.support.DeviceSupervision
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.litert.LiteRtLlmEngine
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/**
 * Opt-in S24 evidence only. No conversation writes, backend fallback or automatic retries.
 * Do not interact with the app UI during the experiment; its manager must remain unloaded.
 * Cold measurements mean engine-cold only; the OS file cache is uncontrolled.
 */
class ChatContextMeasurementTest {
    @Test fun gpuBakedProfileNearBoundShortControlAndLongAnswer(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Context measurement not requested", args.getString("chatContextMeasurement") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "secureQa" && BuildConfig.SECURE_WINDOWS &&
            context.packageName == "dev.edgecompanion.app.secureqa")
        require(args.getString("supervisedThermal") == "true")
        val runId = requireNotNull(args.getString("supervisionRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        // Neither backend nor profile can be overridden by instrumentation arguments.
        require(args.getString("modelBackend").let { it == null || it == "GPU" })
        require(args.getString("chatContextTokens") == null)
        // Engine-cold iterations per case, chosen by the host script; no automatic retries.
        val repeat = requireNotNull(args.getString("chatContextRepeat")?.toIntOrNull()) { "chatContextRepeat is required" }
        require(repeat in 1..MAX_REPEAT)
        check(Build.MODEL == "SM-S928B") { "Only the designated S24 Ultra is authorized" }
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the authorized phone" }
        val profile = ChatContextProfile.entries.single { it.contextTokens == BuildConfig.CHAT_CONTEXT_TOKENS }
        check(profile.outputTokens == 512)

        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val appManager = koin.get<ModelManager>()
        val resources = koin.get<AndroidModelResources>()
        val recorder = koin.get<MemoryTelemetryRecorder>()
        val measured = MeasuredEngine(LiteRtLlmEngine(enabled = true,
            monotonicMs = SystemClock::elapsedRealtime), profile)
        // Observe native statistics without changing ordinary chatFlow or invoking testPrompt.
        // The test owns its engine and uses only existing private QA assets; no acquisition.
        // The app manager is released under supervision before any test-engine allocation.
        val manager = ModelManager(koin.get<ModelAssets>(), measured, Dispatchers.Default,
            resources::snapshot, SystemClock::elapsedRealtime, enabled = true,
            memoryPolicy = appManager.memoryPolicy, memoryTelemetry = recorder::recordStage)
        val output = File(context.noBackupFilesDir, "chat-context-$runId.json")
        val liveTrace = File(context.noBackupFilesDir, "chat-context-$runId-telemetry.json")
        val stopMarker = File(context.noBackupFilesDir, "chat-context-$runId-supervision-stop.json")
        val cases = JSONArray()
        val report = JSONObject().put("schema", 2).put("runId", runId).put("status", "running")
            .put("startedAtMs", System.currentTimeMillis()).put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("androidSdk", Build.VERSION.SDK_INT).put("appVersion", BuildConfig.VERSION_NAME)
            .put("buildType", BuildConfig.BUILD_TYPE).put("backend", "GPU").put("automaticFallback", false)
            .put("bakedContextTokens", profile.contextTokens).put("reservedOutputTokens", profile.outputTokens)
            .put("modelSha256", ModelCatalog.gemma4.sha256).put("runtimeVersion", ModelCatalog.RUNTIME_VERSION)
            .put("budgetVersion", GemmaChatBudget.VERSION).put("policy", JSONObject(manager.memoryPolicy.exportFields()))
            .put("memoryFloorsCalibratedForExtendedContext", false).put("automaticRetries", 0)
            .put("sampleIntervalMs", SAMPLE_MS).put("acceptanceComplete", false)
            .put("repeat", repeat).put("iterationOrder", "per iteration: near_bound, short_control, long_answer")
            .put("decodeRateCase", LONG_ANSWER).put("minimumLongAnswerOutputTokens", MIN_LONG_ANSWER_TOKENS)
            .put("decodeRateNote", "Only $LONG_ANSWER decode rates with at least $MIN_LONG_ANSWER_TOKENS output tokens are meaningful")
            .put("experimentConditions", "No app UI interaction; app manager must remain unloaded")
            .put("coldDefinition", "engine-cold only; not file-cache-cold")
            .put("cacheState", "engine-unloaded per case; OS file cache uncontrolled; compiler cache disabled")
            .put("metricSource", "test delegate around test-owned LiteRtLlmEngine; ordinary ModelManager.generateChat")
            .put("liveTelemetryFile", liveTrace.name).put("cases", cases)
        var recording = false
        var sampler: Job? = null
        var failure: Throwable? = null

        // Report file writes are serialized with the supervision-loss recorder, which runs on the
        // watcher's cleanup thread while this coroutine may be blocked in a non-cancellable native load.
        val reportLock = ReentrantLock()
        val supervisionLost = AtomicBoolean(false)

        suspend fun persist() {
            if (recording) report.put("telemetry", recorder.report())
            withContext(Dispatchers.IO) {
                reportLock.withLock {
                    if (supervisionLost.get()) markSupervisionLoss(report)
                    writeJson(output, report)
                }
            }
        }
        // Called first by the supervision watcher (host stop, deadline or lease loss) before cleanup
        // and the hard process stop, so the run ends with a recorded failure rather than "running".
        fun recordSupervisionLoss() {
            supervisionLost.set(true)
            val at = System.currentTimeMillis()
            try {
                writeJson(stopMarker, JSONObject().put("schema", 1).put("runId", runId).put("status", "failed")
                    .put("failureType", SUPERVISION_LOSS).put("recordedAtMs", at))
            } catch (_: Throwable) { }
            if (!reportLock.tryLock(1, TimeUnit.SECONDS)) return // The marker file above still records it.
            try {
                val persisted = if (output.exists()) JSONObject(output.readText())
                    else JSONObject().put("schema", 1).put("runId", runId).put("cases", JSONArray())
                markSupervisionLoss(persisted)
                writeJson(output, persisted.put("supervisionLossAtMs", at))
            } catch (_: Throwable) { } finally { reportLock.unlock() }
        }
        fun recordFailure(problem: Throwable) {
            if (failure == null) failure = problem else if (failure !== problem) failure!!.addSuppressed(problem)
            report.put("status", "failed").put("failureType", failure!!.javaClass.simpleName)
                .put("runtimeError", manager.state.value.error?.name ?: JSONObject.NULL)
                .put("cleanupError", manager.state.value.cleanupError?.name ?: JSONObject.NULL)
        }

        try {
            persist()
            DeviceSupervision.open(runId) {
                recordSupervisionLoss()
                runBlocking {
                    // Serial cleanup of both independent owners under the same device lease.
                    manager.releaseForHostLoss().join()
                    appManager.releaseForHostLoss().join()
                    unloaded(manager.state.value) && unloaded(appManager.state.value)
                }
            }.use {
                try {
                    withTimeout(30_000) { appManager.releaseForHostLoss().join() }
                    report.put("appManagerBefore", runtime(appManager.state.value))
                    check(unloaded(appManager.state.value)) { "App engine ownership is not released" }
                    recorder.start()
                    recording = true
                    recorder.baseline()
                    persist()
                    sampler = launch(Dispatchers.IO) {
                        var ticks = 0
                        while (isActive) {
                            recorder.sample()
                            // Retain partial evidence even if native work or cleanup later hangs.
                            if (ticks++ % 10 == 0) writeJson(liveTrace, recorder.report())
                            delay(SAMPLE_MS)
                        }
                    }
                    val fixtures = listOf("near_bound" to nearBound(profile),
                        "short_control" to "Name three colors, separated by commas.",
                        LONG_ANSWER to LONG_ANSWER_PROMPT)
                    for (iteration in 1..repeat) for ((name, prompt) in fixtures) {
                        check(unloaded(manager.state.value)) { "Each case must start with no resident engine" }
                        measured.reset()
                        val request = GemmaChatBudget.build(prompt, emptyList(), "$runId-$name-$iteration", profile)
                        val bound = GemmaChatBudget.validate(request)
                        val entry = JSONObject().put("case", name).put("iteration", iteration).put("status", "running")
                            .put("cleanupConfirmed", false)
                            .put("promptBytes", prompt.encodeToByteArray().size).put("promptSha256", sha256(prompt))
                            .put("inputTokenByteBound", bound)
                            .put("boundSlackTokens", profile.contextTokens - profile.outputTokens - bound)
                            .put("before", runtime(manager.state.value))
                            .put("appManagerBefore", runtime(appManager.state.value))
                            .put("thermalBefore", resources.snapshot().thermalStatus ?: JSONObject.NULL)
                        cases.put(entry)
                        val sampleStart = recorder.report().getJSONArray("samples").length()
                        persist() // The case is on disk as "running" before any native load starts.
                        val started = SystemClock.elapsedRealtime()
                        var terminal: ChatEvent.Terminal? = null
                        var responseChars = 0
                        try {
                            check(unloaded(appManager.state.value)) { "App manager re-entered before case; no UI interaction is allowed" }
                            withTimeout(180_000) {
                                manager.generateChat(request).collect { event ->
                                    check(event.id == request.id)
                                    when (event) {
                                        is ChatEvent.Text -> responseChars += event.delta.length
                                        is ChatEvent.Terminal -> {
                                            check(terminal == null) { "Duplicate terminal event" }
                                            terminal = event
                                        }
                                    }
                                }
                            }
                            val resident = manager.state.value
                            entry.put("resident", runtime(resident))
                            check(terminal?.outcome == ChatOutcome.COMPLETED && terminal?.error == null && responseChars > 0) {
                                "Ordinary native generation did not complete"
                            }
                            check(resident.status == RuntimeStatus.LOADED && resident.backend == ModelBackend.GPU &&
                                resident.loadedContextTokens == profile.contextTokens && resident.loadedOutputTokens == profile.outputTokens &&
                                resident.error == null && resident.commandError == null && !resident.telemetryFailed)
                            val native = measured.metrics.get()
                            check(native.loads == 1 && native.finishedCount == 1) { "Expected exactly one engine-cold load and generation" }
                            check(native.loadMs != null && native.firstTextMs != null && native.finished != null)
                            val statistics = checkNotNull(native.finished)
                            check(statistics.inputTokens?.let { it > 0 && it.toLong() <= bound } == true)
                            check(statistics.tokens?.let { it in 1..profile.outputTokens } == true)
                            check(statistics.decodeTokensPerSecond?.let { it.isFinite() && it > 0 } == true)
                            // A long answer under MIN_LONG_ANSWER_TOKENS does not fail the run; it is recorded
                            // with decodeRateMeaningful=false and excluded from the decode-rate summary.
                            entry.put("status", "measured")
                        } catch (problem: Throwable) {
                            entry.put("status", "failed").put("failureType", problem.javaClass.simpleName)
                            throw problem
                        } finally {
                            withContext(NonCancellable) {
                                val native = measured.metrics.get()
                                entry.put("loadMs", native.loadMs ?: JSONObject.NULL)
                                    .put("firstTextMs", native.firstTextMs ?: JSONObject.NULL)
                                    .put("generationMs", native.generationMs ?: JSONObject.NULL)
                                    .put("coldRequestFirstTextMs", native.firstTextAtMs?.let { it - started } ?: JSONObject.NULL)
                                    .put("inputTokens", native.finished?.inputTokens ?: JSONObject.NULL)
                                    .put("outputTokens", native.finished?.tokens ?: JSONObject.NULL)
                                    .put("decodeTokensPerSecond", native.finished?.decodeTokensPerSecond ?: JSONObject.NULL)
                                    .put("decodeRateMeaningful", name == LONG_ANSWER &&
                                        (native.finished?.tokens ?: 0) >= MIN_LONG_ANSWER_TOKENS)
                                    .put("nativeLoadCount", native.loads).put("nativeFinishedCount", native.finishedCount)
                                    .put("responseChars", responseChars).put("residentAfter", runtime(manager.state.value))
                                    .put("outcome", terminal?.outcome?.name ?: JSONObject.NULL)
                                    .put("terminalError", terminal?.error?.name ?: JSONObject.NULL)
                                    .put("thermalAfter", resources.snapshot().thermalStatus ?: JSONObject.NULL)
                                recorder.sample()
                                val samples = recorder.report().getJSONArray("samples")
                                entry.put("sampleStartIndex", sampleStart).put("sampleEndExclusive", samples.length())
                                    .put("sampledPeakPssBytes", peak(samples, sampleStart, "appPssBytes") ?: JSONObject.NULL)
                                    .put("sampledPeakRssBytes", peak(samples, sampleStart, "appRssBytes") ?: JSONObject.NULL)
                                persist() // Preserve measurements/failures before awaiting native cleanup.
                                withTimeout(30_000) { manager.releaseForHostLoss().join() }
                                entry.put("cleanup", runtime(manager.state.value)).put("cleanupConfirmed", unloaded(manager.state.value))
                                    .put("appManagerAfter", runtime(appManager.state.value))
                                val appStayedUnloaded = unloaded(appManager.state.value)
                                if (!appStayedUnloaded) entry.put("status", "failed").put("appManagerInterference", true)
                                persist()
                                check(unloaded(manager.state.value)) { "Native cleanup unconfirmed" }
                                check(appStayedUnloaded) { "App manager re-entered during case; measurements are inconclusive" }
                            }
                        }
                    }
                    val trace = recorder.report()
                    check(trace.getInt("failedSamples") == 0 && trace.getInt("droppedSamples") == 0) { "Telemetry incomplete" }
                    val all = trace.getJSONArray("samples")
                    check((0 until all.length()).any { index ->
                        val sample = all.getJSONObject(index)
                        sample.optInt("contextTokensAtEvent", -1) == profile.contextTokens &&
                            sample.optInt("outputTokensAtEvent", -1) == profile.outputTokens
                    }) { "No loaded profile in recorder trace" }
                    check((0 until cases.length()).all { index ->
                        val entry = cases.getJSONObject(index)
                        !entry.isNull("sampledPeakPssBytes") && !entry.isNull("sampledPeakRssBytes") &&
                            !entry.isNull("thermalBefore") && !entry.isNull("thermalAfter")
                    }) { "Required memory/thermal evidence missing" }
                    report.put("status", "measured")
                } catch (problem: Throwable) {
                    recordFailure(problem)
                } finally {
                    withContext(NonCancellable) {
                        try { persist() } catch (problem: Throwable) { recordFailure(problem) }
                        try {
                            withTimeout(30_000) { manager.shutdown() }
                            withTimeout(30_000) { appManager.releaseForHostLoss().join() }
                            report.put("cleanup", runtime(manager.state.value)).put("appManagerAfter", runtime(appManager.state.value))
                            val clean = unloaded(manager.state.value) && unloaded(appManager.state.value)
                            report.put("cleanupConfirmed", clean)
                            check(clean) { "Manager cleanup unconfirmed" }
                            if (recording) {
                                recorder.sample()
                                val trace = recorder.report()
                                check(trace.getInt("failedSamples") == 0 && trace.getInt("droppedSamples") == 0 &&
                                    !manager.state.value.telemetryFailed) { "Final telemetry incomplete" }
                            }
                        } catch (problem: Throwable) { recordFailure(problem) }
                        finally {
                            sampler?.cancelAndJoin()
                            if (recording) recorder.stop()
                            persist()
                        }
                    }
                }
            }
        } catch (problem: Throwable) {
            recordFailure(problem)
        } finally {
            withContext(NonCancellable) {
                report.put("finishedAtMs", System.currentTimeMillis())
                persist()
            }
        }
        failure?.let { throw it }
    }

    private data class NativeMetrics(val loads: Int = 0, val loadMs: Long? = null,
        val firstTextMs: Long? = null, val firstTextAtMs: Long? = null, val generationMs: Long? = null,
        val finishedCount: Int = 0, val finished: GenerationEvent.Finished? = null)

    /** Observation only: every operation/event is forwarded once, including errors and cancellation. */
    private class MeasuredEngine(private val delegate: LocalLlmEngine, private val profile: ChatContextProfile) : LocalLlmEngine {
        val metrics = AtomicReference(NativeMetrics())
        fun reset() { metrics.set(NativeMetrics()) }
        override suspend fun load(config: ModelConfig): Result<Unit> {
            check(config.backend == ModelBackend.GPU && config.contextTokens == profile.contextTokens &&
                config.maxOutputTokens == profile.outputTokens)
            val started = SystemClock.elapsedRealtime()
            metrics.updateAndGet { it.copy(loads = it.loads + 1) }
            return try { delegate.load(config) }
            finally { metrics.updateAndGet { it.copy(loadMs = SystemClock.elapsedRealtime() - started) } }
        }
        override fun generate(request: GenerationRequest): Flow<GenerationEvent> {
            check(request.chat != null && request.constraint == null)
            val started = SystemClock.elapsedRealtime()
            return delegate.generate(request).onEach { event ->
                val now = SystemClock.elapsedRealtime()
                when (event) {
                    is GenerationEvent.Token -> if (event.text.isNotEmpty()) metrics.updateAndGet {
                        if (it.firstTextMs == null) it.copy(firstTextMs = now - started, firstTextAtMs = now) else it
                    }
                    is GenerationEvent.Finished -> metrics.updateAndGet {
                        it.copy(finishedCount = it.finishedCount + 1, finished = event, generationMs = now - started)
                    }
                    else -> Unit
                }
            }
        }
        override suspend fun cancel() = delegate.cancel()
        override suspend fun release() = delegate.release()
    }

    private fun nearBound(profile: ChatContextProfile): String {
        val prefix = "Synthetic filler follows. Ignore it and name three colors, separated by commas.\n"
        val overhead = GemmaChatBudget.inputBound(listOf(ChatMessage(ChatRole.USER, "")))
        val target = (profile.contextTokens - profile.outputTokens - overhead).toInt()
        val filler = "amber birch cedar dune elm fern grove hazel iris jade kelp larch moss oak pine quartz reed sage thyme umber violet willow yarrow zinc. "
        val remaining = target - prefix.encodeToByteArray().size
        check(remaining >= 0)
        val prompt = prefix + filler.repeat(remaining / filler.length + 1).take(remaining)
        val request = GemmaChatBudget.build(prompt, emptyList(), "fixture", profile)
        check(GemmaChatBudget.validate(request) + profile.outputTokens == profile.contextTokens.toLong())
        return prompt
    }

    /** Marks the run and any case still "running" as failed by supervision loss; idempotent. */
    private fun markSupervisionLoss(report: JSONObject) {
        // An earlier recorded failure keeps its type; the loss is always flagged.
        if (report.optString("status") != "failed") report.put("failureType", SUPERVISION_LOSS)
        report.put("status", "failed").put("supervisionLoss", true)
        val cases = report.optJSONArray("cases") ?: return
        for (index in 0 until cases.length()) {
            val entry = cases.getJSONObject(index)
            if (entry.optString("status") == "running") entry.put("status", "failed").put("failureType", SUPERVISION_LOSS)
        }
    }

    private fun unloaded(state: ModelRuntimeState) = state.status == RuntimeStatus.UNLOADED &&
        state.loadedContextTokens == null && state.loadedOutputTokens == null && state.cleanupError == null

    private fun runtime(state: ModelRuntimeState) = JSONObject().put("status", state.status.name)
        .put("backend", state.backend.name).put("loadedContextTokens", state.loadedContextTokens ?: JSONObject.NULL)
        .put("loadedOutputTokens", state.loadedOutputTokens ?: JSONObject.NULL)
        .put("error", state.error?.name ?: JSONObject.NULL).put("cleanupError", state.cleanupError?.name ?: JSONObject.NULL)
        .put("commandError", state.commandError?.name ?: JSONObject.NULL).put("telemetryFailed", state.telemetryFailed)

    private fun peak(samples: JSONArray, start: Int, field: String): Long? =
        (start until samples.length()).mapNotNull { index ->
            samples.getJSONObject(index).let { if (it.isNull(field)) null else it.getLong(field) }
        }.maxOrNull()

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())
        .joinToString("") { "%02x".format(it) }

    private fun writeJson(output: File, value: JSONObject) {
        val temporary = File(output.parentFile, "${output.name}.tmp")
        temporary.writeText(value.toString(2))
        check(temporary.renameTo(output))
    }

    private companion object {
        const val SAMPLE_MS = 500L
        const val SUPERVISION_LOSS = "SupervisionLoss"
        const val MAX_REPEAT = 5
        const val LONG_ANSWER = "long_answer"
        const val MIN_LONG_ANSWER_TOKENS = 128
        // Synthetic; about 50 short lines (roughly 250-350 tokens), below the 512-token output reservation.
        const val LONG_ANSWER_PROMPT = "Write a numbered list from 1 to 50. Each line has the number, one adjective " +
            "and one noun, for example \"1. quiet river\". Write nothing else."
    }
}
