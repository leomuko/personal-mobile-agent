package dev.edgecompanion.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.app.support.*
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.embedding.*
import dev.edgecompanion.core.inference.verification.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** No ordinary QA invocation enables this path. Requires a new reviewed freeze and separate device permission. */
class ConstrainedDecodingNativeTest {
    @Test fun nineOrderedGuardChecks(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("p2ConstrainedDecoding") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "secureQa" && BuildConfig.ANDROID_PRESSURE_MEMORY_TEST)
        check(args.getString("cdDeviceAuthorized") == "true")
        val freeze = requireNotNull(args.getString("cdExecutionFreezeSha256")); check(freeze.matches(Regex("[a-f0-9]{64}")))
        val planBytes = File(context.noBackupFilesDir, "qa-embedding/constrained-decoding.json").let {
            check(it.length() in 1..65536); it.readBytes()
        }
        val planSha = requireNotNull(args.getString("cdPlanSha256")); check(digest(planBytes) == planSha)
        val plan = JSONObject(planBytes.toString(Charsets.UTF_8)); planBytes.fill(0)
        check(plan.getString("protocolId") == ConstrainedDecoding.PROTOCOL && plan.getString("preparationLockSha256") == ConstrainedDecoding.LOCK)
        check(plan.getInt("requests") == 9 && plan.getInt("deadlineMs") == 30000 && plan.getBoolean("cleanupWithinDeadline"))
        check(plan.getInt("contextTokens") == 2048 && plan.getInt("reservedOutputTokens") == 512 && plan.getInt("encoderTokenLimit") == 192)
        check(plan.getString("profileId") == ConstraintAllocationGate.POLICY && !plan.getBoolean("semanticEnablement"))
        val rows = plan.getJSONArray("entries"); check(rows.length() == 9)
        val oracleFile = File(context.noBackupFilesDir, "qa-embedding/constrained-decoding-oracles.json")
        check(oracleFile.length() in 1..16384)
        val oracleBytes = oracleFile.readBytes(); check(digest(oracleBytes) == plan.getString("oraclesSha256"))
        val oracles = JSONObject(oracleBytes.toString(Charsets.UTF_8)).getJSONArray("controls"); oracleBytes.fill(0)
        check(oracles.length() == 9)
        val app = context.applicationContext as CompanionApplication
        val manager = app.getKoin().get<ModelManager>()
        check(manager.memoryPolicy.id == ConstraintAllocationGate.POLICY)
        val resources = AndroidModelResources(context)
        val telemetry = app.getKoin().get<MemoryTelemetryRecorder>()
        val type = Class.forName("dev.edgecompanion.app.prerequisites.AndroidForegroundEncoder")
        val model = File(context.noBackupFilesDir, "qa-embedding/89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749.tflite")
        val safe = { manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null }
        val runId = requireNotNull(args.getString("supervisionRunId"))
        val report = JSONObject().put("schema", 2).put("protocolId", ConstrainedDecoding.PROTOCOL).put("runId", runId)
            .put("planSha256", planSha).put("executionFreezeSha256", freeze).put("preparationLockSha256", ConstrainedDecoding.LOCK)
            .put("policyId", manager.memoryPolicy.id).put("startedAtMs", System.currentTimeMillis())
            .put("startedAtMonotonicMs", SystemClock.elapsedRealtime()).put("semanticEnablement", false)
        val samples = JSONArray(); report.put("cases", samples)
        val output = File(context.noBackupFilesDir, "p2-constrained-decoding.json")
        val workers = mutableSetOf<String>()
        var active: VerificationCall? = null
        DeviceSupervision.open(runId) {
            runBlocking { active?.cancel(); manager.releaseForHostLoss().join(); manager.state.value.status == RuntimeStatus.UNLOADED }
        }.use {
            try {
                manager.releaseForHostLoss().join(); check(manager.state.value.status == RuntimeStatus.UNLOADED)
                for (index in 0 until 9) coroutineScope {
                    if (report.has("blockingGate")) return@coroutineScope
                    val started = SystemClock.elapsedRealtime() // Before all per-request identity/pack/schema work.
                    // Separate from coroutine cancellation, which cannot interrupt a hung JNI setup.
                    // This only stops the current immutable QA run; it grants no extra accepted inference time.
                    val deadlineStop = ScopedDeadlineStop {
                        try {
                            val directory = File(context.noBackupFilesDir, "qa-supervision/$runId")
                            val message = JSONObject().put("schema", 2).put("runId", runId).put("action", "stop")
                                .put("reason", "CONSTRAINED_REQUEST_CLEANUP_WATCHDOG").put("caseId", "cd0${index + 1}")
                                .put("requestStartedAtMs", started).put("expiredAtMs", SystemClock.elapsedRealtime())
                            val evidence = File(directory, "constrained-deadline-evidence.tmp")
                            evidence.writeText(message.toString())
                            check(evidence.renameTo(File(directory, "constrained-deadline.json")))
                            val temporary = File(directory, "constrained-deadline-stop.tmp")
                            temporary.writeText(message.toString())
                            check(temporary.renameTo(File(directory, "stop.json")))
                        } catch (_: Exception) { android.os.Process.killProcess(android.os.Process.myPid()) }
                    }
                    val watchdog = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "qa-constrained-deadline").also { it.isDaemon = true } }
                    val alarm = watchdog.schedule({ deadlineStop.expire() }, 40_000, TimeUnit.MILLISECONDS)
                    try {
                    val row = rows.getJSONObject(index); val id = "cd0${index + 1}"; check(row.getString("id") == id)
                    check(safe() && manager.state.value.status == RuntimeStatus.UNLOADED)
                    val requestId = "$runId-cd-$index"
                    val constraint = ConstrainedDecoding.admit(id, requestId, row.getString("payload"), row.getString("question"),
                        row.getString("constraintSchema"), ::digest)
                    check(row.getInt("decodeTokenCap") == constraint.decodeTokenCap && row.getString("schemaSha256") == constraint.schemaSha256)
                    val bound = GemmaChatBudget.validate(ChatRequest(requestId, listOf(ChatMessage(ChatRole.USER, constraint.payload))))
                    val detailIds = row.getJSONArray("detailIds").let { a -> (0 until a.length()).map { a.getString(it) } }
                    val passages = row.getJSONArray("passages").let { a -> (0 until a.length()).map { n -> a.getJSONObject(n).let { p ->
                        DeliveredPassage(p.getString("p"), p.getString("r"), p.getString("original"), p.getInt("b"), p.getInt("e")) } } }
                    val encoder = type.constructors.single().newInstance(context, model, safe) as ForegroundEncoder
                    val sample = JSONObject().put("id", id).put("requestId", requestId).put("packSha256", digest(constraint.payload.toByteArray()))
                        .put("schemaSha256", constraint.schemaSha256).put("responseFormatOptIn", true).put("nativeConstraintType", 2)
                        .put("decodeTokenCap", constraint.decodeTokenCap).put("reservedOutputTokens", 512).put("inputBound", bound)
                        .put("tokenizerInitialState", "cold").put("nativeEncoderInitialState", "no-resident-worker")
                        .put("startedAtMonotonicMs", started).put("backend", "GPU").put("gemmaInitialState", "UNLOADED")
                    samples.put(sample); output.writeReport(report); telemetry.start()
                    val call = manager.verifyConstrained(constraint, EvidenceContext(detailIds, passages), encoder, started, 1) { 1 }
                    active = call
                    val trigger = when (id) { "cd04" -> ConstraintPhase.SETUP_ENTER; "cd05" -> ConstraintPhase.SEND_ENTER;
                        "cd06" -> ConstraintPhase.FIRST_TEXT; else -> null }
                    val cancelJob = trigger?.let { phase -> launch(Dispatchers.IO) {
                        constraint.trace.state.first { evidence -> evidence.points.any { it.phase == phase } }
                        call.cancel()
                    } }
                    var completed = false
                    try {
                        val result = withTimeout(40_000) { call.events.single() }; completed = true
                        cancelJob?.cancelAndJoin()
                        val state = manager.state.value
                        val trace = telemetry.report()
                        @Suppress("UNCHECKED_CAST") val worker = (type.getMethod("getEvidence").invoke(encoder) as StateFlow<Any>).value
                        fun field(name: String) = worker.javaClass.getMethod(name).invoke(worker)
                        @Suppress("UNCHECKED_CAST") val events = (field("getEvents") as List<Any>).map { event ->
                            fun get(name: String) = event.javaClass.getMethod(name).invoke(event)
                            V4WorkerPoint(get("getPhase") as String, (get("getMonotonicMs") as Number).toLong(),
                                (get("getPssKb") as Number).toLong(), (get("getPid") as Number).toLong())
                        }
                        val memory = trace.getJSONArray("samples").let { a -> (0 until a.length()).map { n -> a.getJSONObject(n).let { p ->
                            V4MemoryPoint(p.getString("stage"), p.getLong("elapsedMs"), p.getLong("eventElapsedMs"), p.getString("policyId"),
                                p.getString("backend"), p.getBoolean("lowMemory"), p.getInt("thermalStatus"), p.getLong("availableMemoryBytes"),
                                p.getLong("pressureThresholdBytes"), p.getLong("appPssBytes"), p.getLong("appRssBytes")) } } }
                        val loads = memory.filter { it.stage == "LOADING" }.map { trace.getLong("startedAtMonotonicMs") + it.eventElapsed }
                        val workerId = field("getInstance") as String
                        val allocation = ConstraintAllocationEvidence(started, SystemClock.elapsedRealtime(), workerId,
                            field("getConfirmedDeath") as Boolean, field("getDeathAtMs") as Long, events, loads,
                            trace.getLong("startedAtMonotonicMs"), trace.getLong("elapsedMs"), memory, trace.getInt("schema"),
                            trace.getJSONObject("policy").getString("id"), trace.getInt("droppedSamples"), trace.getInt("failedSamples"),
                            constraint.trace.state.value.points.any { it.phase == ConstraintPhase.FIRST_TEXT })
                        val validation = result.validation as? OutputValidation.StructurallyValid
                        val oracle = oracles.getJSONObject(index); check(oracle.getString("id") == id)
                        val shape = if (id == "cd01") validation?.output == EvidenceOutput(EvidenceState.S,
                            listOf(EvidenceDecision("a", EvidenceState.S, listOf(EvidenceSpan("sentinel-p", "1", 0, 1, "X")))))
                        else if (oracle.has("state") && validation != null) {
                            val details = oracle.getJSONArray("details")
                            OutputAdherenceShape.matches(validation.output, AdherenceOracle(EvidenceState.valueOf(oracle.getString("state")),
                                (0 until details.length()).map { n -> details.getJSONObject(n).let { d ->
                                    val refs = d.getJSONArray("passageIds")
                                    AdherenceDetail(d.getString("i"), EvidenceState.valueOf(d.getString("state")),
                                        (0 until refs.length()).map { refs.getString(it) }.toSet()) } }))
                        } else false
                        val allocationValid = ConstraintAllocationGate.accepted(allocation, workers)
                        val gate = ConstrainedGuard.classify(id, result, constraint.trace.state.value.points, state.outputTokens,
                            SystemClock.elapsedRealtime() - started, state.status == RuntimeStatus.UNLOADED,
                            safe() && !state.telemetryFailed && allocationValid, call.preparation.value.cleanup == EncoderCleanup.CONFIRMED, shape)
                        sample.put("outcome", result.outcome?.name).put("delivery", result.delivery.name).put("diagnosticOutput", result.diagnosticOutput)
                            .put("diagnosticSource", "NATIVE_CALLBACK_BUFFER").put("diagnosticTruncated", result.diagnosticTruncated)
                            .put("diagnosticReceivedUtf16Units", result.diagnosticReceivedUtf16Units)
                            .put("diagnosticUtf16Hex", result.diagnosticUtf16Hex)
                            .put("validation", when (val v = result.validation) { is OutputValidation.Rejected -> v.reason.name;
                                is OutputValidation.StructurallyValid -> "STRUCTURALLY_VALID"; null -> "NOT_REACHED" })
                            .put("decisionMs", result.decisionMs).put("cleanupMs", result.cleanupMs).put("runtimeCleanup", result.cleanup.name)
                            .put("encoderCleanup", call.preparation.value.cleanup.name).put("runtimeStatus", state.status.name).put("error", result.error?.name)
                            .put("sdkInputTokens", state.inputTokens).put("sdkOutputTokens", state.outputTokens).put("shapeMatched", shape)
                            .put("telemetryFailed", state.telemetryFailed).put("allocationValid", allocationValid).put("workerInstance", workerId)
                            .put("confirmedWorkerDeath", allocation.deathConfirmed).put("encoderDeathAtMs", allocation.deathAt)
                            .put("workerEvents", JSONArray(events.map { JSONObject().put("phase", it.phase).put("monotonicMs", it.at).put("pssKb", it.pssKb).put("pid", it.pid) }))
                            .put("gemmaLoads", JSONArray(loads)).put("memory", trace)
                            .put("constraintPoints", JSONArray(constraint.trace.state.value.points.map { JSONObject().put("phase", it.phase.name).put("atMs", it.atMs).put("diagnostic", it.diagnostic) }))
                            .put("guardClassification", gate.classification)
                        deadlineStop.close(); alarm.cancel(false); watchdog.shutdownNow()
                        val finished = SystemClock.elapsedRealtime()
                        val accepted = gate.passed && finished - started < 30000
                        sample.put("finishedAtMonotonicMs", finished).put("wallMs", finished - started).put("caseGateAccepted", accepted)
                        if (!accepted) report.put("blockingGate", "CONSTRAINED_DECODING")
                        output.writeReport(report); workers += workerId
                    } finally {
                        cancelJob?.cancelAndJoin()
                        if (!completed) call.cancel()
                        active = null; telemetry.stop(); output.writeReport(report)
                    }
                    } finally { deadlineStop.close(); alarm.cancel(false); watchdog.shutdownNow() }
                }
            } finally {
                active?.cancel(); telemetry.stop(); manager.releaseForHostLoss().join()
                report.put("finalCleanup", if (manager.state.value.status == RuntimeStatus.UNLOADED) "CONFIRMED" else "UNCERTAIN")
                    .put("finishedAtMs", System.currentTimeMillis()).put("finishedAtMonotonicMs", SystemClock.elapsedRealtime())
                output.writeReport(report)
            }
        }
    }
}

private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun File.writeReport(value: JSONObject) = writeText(escapeQaJsonSurrogates(value.toString(2)))
