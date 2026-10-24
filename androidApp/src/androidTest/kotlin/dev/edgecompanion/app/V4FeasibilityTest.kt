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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** Opt-in, calibration-only feasibility. Stops at the first failed gate; never fits or scores. */
class V4FeasibilityTest {
    @Test fun boundedWholePipelineWithIndependentCleanup(): Unit = runBlocking {
        runSerialVerification(SerialExperiment.V4)
    }
}

internal enum class SerialExperiment { V4, OUTPUT_ADHERENCE }

/** The two explicitly authorized experiments share native ownership/telemetry, not approvals or inputs. */
internal suspend fun runSerialVerification(experiment: SerialExperiment) {
        val adherence = experiment == SerialExperiment.OUTPUT_ADHERENCE
        val prefix = if (adherence) "oa" else "v4"
        val protocolId = if (adherence) "evidence-output-adherence-v1-draft-1" else "serial-evidence-verification-v4-draft-1"
        val origin = if (adherence) OA_PREPARATION else FREEZE
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString(if (adherence) "p2OutputAdherence" else "p2V4") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && context.packageName.endsWith(".secureqa"))
        check(args.getString("${prefix}DeviceAuthorized") == "true")
        val executionFreeze = requireNotNull(args.getString("${prefix}ExecutionFreezeSha256"))
        check(executionFreeze.matches(Regex("[a-f0-9]{64}")))
        val app = context.applicationContext as CompanionApplication
        val manager = app.getKoin().get<ModelManager>()
        val expectedPolicy = if (BuildConfig.ANDROID_PRESSURE_MEMORY_TEST) "memory-android-pressure-test-v1" else "memory-baseline-v1"
        if (adherence) check(BuildConfig.ANDROID_PRESSURE_MEMORY_TEST)
        check(manager.memoryPolicy.id == expectedPolicy)
        val telemetry = app.getKoin().get<MemoryTelemetryRecorder>()
        val resources = AndroidModelResources(context)
        val planFile = File(context.noBackupFilesDir, if (adherence) "qa-embedding/output-adherence.json" else "qa-embedding/v4-feasibility.json")
        check(planFile.length() in 1..65536)
        val planBytes = planFile.readBytes()
        val planSha = requireNotNull(args.getString("${prefix}PlanSha256"))
        check(sha(planBytes) == planSha)
        val plan = JSONObject(planBytes.toString(Charsets.UTF_8)); planBytes.fill(0)
        check(plan.getInt("schema") == (if (adherence) 1 else 4) &&
            plan.getString("scope") == if (adherence) "output-adherence-six-request-feasibility" else "v4-small-supervised-feasibility")
        check(plan.getString(if (adherence) "preparationLockSha256" else "freezeSha256") == origin && plan.getInt("deadlineMs") == 30000)
        check(plan.getInt("contextTokens") == 2048 && plan.getInt("outputTokens") == 512)
        check(plan.getString("protocolId") == protocolId && plan.getBoolean("cleanupWithinDeadline"))
        check(plan.getString("policyId") == expectedPolicy && plan.getBoolean("androidPressure") == BuildConfig.ANDROID_PRESSURE_MEMORY_TEST)
        val entries = plan.getJSONArray("entries"); check(entries.length() == if (adherence) 6 else 8)
        val ids = listOf("v3k01qc", "v3k01qm", "v3k01qs", "v3k01qt")
        val states = listOf("cold-all-unloaded", "cached-admission-only")
        for (i in 0 until entries.length()) {
            check(entries.getJSONObject(i).getString("id") == if (adherence) "oa0${i + 1}" else ids[i % 4])
            check(entries.getJSONObject(i).getString("state") == if (adherence) "native-cold" else states[i / 4])
        }
        // Only the QA harness reads this oracle; it is never passed to EncoderInput or ChatRequest.
        val oracles = if (adherence) {
            check(plan.getString("strictValidatorSha256") == OA_VALIDATOR)
            val file = File(context.noBackupFilesDir, "qa-embedding/output-adherence-oracles.json")
            check(file.length() in 1..16384)
            val bytes = file.readBytes()
            check(sha(bytes) == plan.getString("oraclesSha256"))
            val value = JSONObject(bytes.toString(Charsets.UTF_8)); bytes.fill(0)
            check(value.getInt("schema") == 1 && value.getString("protocolId") == protocolId && value.getString("preparationLockSha256") == origin)
            value.getJSONArray("controls").also { check(it.length() == 6) }
        } else null
        val type = Class.forName("dev.edgecompanion.app.prerequisites.AndroidForegroundEncoder")
        val model = File(context.noBackupFilesDir, "qa-embedding/$ENCODER.tflite")
        val safe: () -> Boolean = { manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null }
        fun newEncoder() = type.constructors.single().newInstance(context, model, safe) as ForegroundEncoder
        var encoder = newEncoder()
        var active: VerificationCall? = null
        val report = JSONObject().put("schema", if (adherence) 1 else 4).put("runId", args.getString("supervisionRunId"))
            .put("startedAtMonotonicMs", SystemClock.elapsedRealtime())
            .put("planSha256", planSha).put("freezeSha256", origin).put("startedAtMs", System.currentTimeMillis())
            .put("protocolId", protocolId).put("executionFreezeSha256", executionFreeze)
            .put("policyId", manager.memoryPolicy.id).put("semanticEnablement", false).put("largeCampaignAllowed", false)
        val cases = JSONArray(); report.put("cases", cases)
        val seenWorkers = mutableSetOf<String>()
        if (adherence) report.put("strictValidatorSha256", OA_VALIDATOR).put("oraclesSha256", plan.getString("oraclesSha256"))
        val output = File(context.noBackupFilesDir, if (adherence) "p2-output-adherence.json" else "p2-v4-feasibility.json")
        DeviceSupervision.open(requireNotNull(args.getString("supervisionRunId"))) {
            runBlocking { active?.cancel(); manager.releaseForHostLoss().join(); manager.state.value.status == RuntimeStatus.UNLOADED }
        }.use {
            try {
                manager.releaseForHostLoss().join(); check(manager.state.value.status == RuntimeStatus.UNLOADED)
                for (index in 0 until entries.length()) {
                    val submitted = SystemClock.elapsedRealtime()
                    val row = entries.getJSONObject(index)
                    check(safe())
                    check(manager.state.value.status == RuntimeStatus.UNLOADED)
                    // Cold creates a new admission adapter. Every native worker is fresh and
                    // must die before Gemma; the second state warms only admission metadata.
                    val coldAdmission = index < (if (adherence) 1 else 4)
                    if (coldAdmission) encoder = newEncoder()
                    val started = if (adherence) submitted else SystemClock.elapsedRealtime()
                    val payload = if (adherence) plan.getString("instructions") + "\nDATA\n" + row.getString("dataJson") + "\nEND DATA" else row.getString("payload")
                    check(sha(payload.toByteArray()) == row.getString("packSha256"))
                    val dataStart = payload.indexOf("\nDATA\n") + 6
                    check(dataStart >= 6 && payload.endsWith("\nEND DATA"))
                    val data = JSONObject(payload.substring(dataStart, payload.length - 9))
                    check(data.getString("q") == row.getString("question"))
                    if (adherence) check(sha(row.getString("question").toByteArray()) == row.getString("encoderInputSha256"))
                    val detailIds = row.getJSONArray("detailIds").let { a -> (0 until a.length()).map { a.getString(it) } }
                    val detailData = data.getJSONArray("d")
                    check(detailIds == (0 until detailData.length()).map { detailData.getJSONObject(it).getString("i") })
                    val delivered = row.getJSONArray("passages")
                    val packed = data.getJSONArray("p"); check(delivered.length() == packed.length())
                    val passages = (0 until delivered.length()).map { i ->
                        val p = delivered.getJSONObject(i); val input = packed.getJSONObject(i)
                        val original = p.getString("original"); val begin = p.getInt("b"); val end = p.getInt("e")
                        check(p.getString("p") == input.getString("p") && p.getString("r") == input.getString("r"))
                        check(begin == input.getInt("b") && input.getString("t") == original.substring(begin, end))
                        DeliveredPassage(p.getString("p"), p.getString("r"), original, begin, end)
                    }
                    val validationContext = EvidenceContext(detailIds, passages)
                    val bound = GemmaChatBudget.validate(ChatRequest(row.getString("id"), listOf(ChatMessage(ChatRole.USER, payload))))
                    val sample = JSONObject().put("id", row.getString("id")).put("state", row.getString("state"))
                        .put("packSha256", row.getString("packSha256")).put("requestId", "${args.getString("supervisionRunId")}-$prefix-$index").put("backend", "GPU").put("inputBound", bound)
                        .put("tokenizerInitialState", if (coldAdmission) "cold" else "cached-admission")
                        .put("nativeEncoderInitialState", "no-resident-worker").put("gemmaInitialState", "UNLOADED")
                    cases.put(sample); output.writeText(report.toString(2))
                    telemetry.start()
                    val call = manager.verifyEvidenceV4("${args.getString("supervisionRunId")}-$prefix-$index", payload, validationContext, EncoderInput(row.getString("question")),
                        encoder, started, 1) { 1 }
                    active = call
                    try {
                        // This is a cleanup watchdog, not a longer inference deadline.
                        val result = withTimeout(40_000) { call.events.single() }
                        val snapshot = resources.snapshot()
                        sample.put("outcome", result.outcome?.name).put("decisionMs", result.decisionMs)
                            .put("diagnosticOutput", result.diagnosticOutput).put("delivery", result.delivery.name).put("telemetryFailed", manager.state.value.telemetryFailed)
                            .put("cleanupMs", result.cleanupMs).put("wallMs", SystemClock.elapsedRealtime() - started)
                            .put("runtimeCleanup", result.cleanup.name).put("encoderCleanup", call.preparation.value.cleanup.name)
                            .put("runtimeStatus", manager.state.value.status.name).put("error", result.error?.name)
                            .put("validationMs", result.validationMs).put("firstTextMs", result.firstTextMs)
                            .put("encoderPreparationMs", call.preparation.value.retrievalMs)
                            .put("sdkInputTokens", manager.state.value.inputTokens).put("sdkOutputTokens", manager.state.value.outputTokens)
                            .put("lowMemory", snapshot.lowMemory).put("thermalStatus", snapshot.thermalStatus)
                        val validation = result.validation
                        val oracle = oracles?.getJSONObject(index)?.let { o ->
                            check(o.getString("id") == row.getString("id"))
                            val details = o.getJSONArray("details")
                            AdherenceOracle(EvidenceState.valueOf(o.getString("state")), (0 until details.length()).map { n ->
                                val d = details.getJSONObject(n); val refs = d.getJSONArray("passageIds")
                                AdherenceDetail(d.getString("i"), EvidenceState.valueOf(d.getString("state")),
                                    (0 until refs.length()).map { refs.getString(it) }.toSet())
                            })
                        }
                        val shapeMatched = validation is OutputValidation.StructurallyValid && oracle != null && OutputAdherenceShape.matches(validation.output, oracle)
                        if (adherence) sample.put("runtimeDecisionMs", result.decisionMs).put("runtimeDelivery", result.delivery.name)
                            .put("decisionMs", SystemClock.elapsedRealtime() - started)
                            .put("controlShape", if (validation !is OutputValidation.StructurallyValid) "NOT_REACHED" else if (shapeMatched) "MATCHED" else "MISMATCH")
                        sample.put("validation", when (validation) {
                            is OutputValidation.StructurallyValid -> "STRUCTURALLY_VALID_NOT_SEMANTICALLY_JUDGED"
                            is OutputValidation.Rejected -> validation.reason.name
                            null -> "NOT_REACHED"
                        })
                        @Suppress("UNCHECKED_CAST")
                        val worker = (type.getMethod("getEvidence").invoke(encoder) as StateFlow<Any>).value
                        fun field(getter: String): Any? = worker.javaClass.getMethod(getter).invoke(worker)
                        sample.put("workerInstance", field("getInstance")).put("confirmedWorkerDeath", field("getConfirmedDeath"))
                            .put("encoderDeathAtMs", field("getDeathAtMs"))
                        @Suppress("UNCHECKED_CAST") val workerEvents = field("getEvents") as List<Any>
                        sample.put("workerEvents", JSONArray(workerEvents.map { event ->
                            fun get(name: String): Any? = event.javaClass.getMethod(name).invoke(event)
                            JSONObject().put("phase", get("getPhase")).put("monotonicMs", get("getMonotonicMs"))
                                .put("pssKb", get("getPssKb")).put("pid", get("getPid"))
                        }))
                        val trace = telemetry.report(); val samples = trace.getJSONArray("samples")
                        val loads = (0 until samples.length()).map { samples.getJSONObject(it) }
                            .filter { it.getString("stage") == "LOADING" }
                            .map { trace.getLong("startedAtMonotonicMs") + it.getLong("eventElapsedMs") }
                        sample.put("gemmaLoads", JSONArray(loads)).put("memory", trace)
                        val finished = SystemClock.elapsedRealtime()
                        sample.put("startedAtMonotonicMs", started).put("finishedAtMonotonicMs", finished).put("wallMs", finished - started)
                        val accepted = try {
                            val points = sample.getJSONArray("workerEvents")
                            V4CaseGate.accepted(V4CaseEvidence(
                                result.outcome == VerificationOutcome.STRUCTURALLY_VALID,
                                result.delivery == VerificationDelivery.ELIGIBLE, validation is OutputValidation.StructurallyValid,
                                result.cleanup == EncoderCleanup.CONFIRMED, call.preparation.value.cleanup == EncoderCleanup.CONFIRMED,
                                manager.state.value.status == RuntimeStatus.UNLOADED, manager.state.value.telemetryFailed,
                                manager.memoryPolicy.blockedReason(snapshot, MemoryAdmission.GENERATION) == null,
                                !result.diagnosticOutput.isNullOrBlank(), result.decisionMs ?: -1, result.cleanupMs ?: -1,
                                started, finished, manager.state.value.inputTokens ?: -1, manager.state.value.outputTokens ?: -1,
                                sample.getString("workerInstance"), sample.getBoolean("confirmedWorkerDeath"), sample.getLong("encoderDeathAtMs"),
                                (0 until points.length()).map { n -> points.getJSONObject(n).let { p ->
                                    V4WorkerPoint(p.getString("phase"), p.getLong("monotonicMs"), p.getLong("pssKb"), p.getLong("pid")) } },
                                loads, trace.getInt("schema"), trace.getJSONObject("policy").getString("id"),
                                trace.getLong("startedAtMonotonicMs"), trace.getLong("elapsedMs"), trace.getInt("droppedSamples"), trace.getInt("failedSamples"),
                                (0 until samples.length()).map { n -> samples.getJSONObject(n).let { p ->
                                    V4MemoryPoint(p.getString("stage"), p.getLong("elapsedMs"), p.getLong("eventElapsedMs"),
                                        p.getString("policyId"), p.getString("backend"), p.getBoolean("lowMemory"), p.getInt("thermalStatus"),
                                        p.getLong("availableMemoryBytes"), p.getLong("pressureThresholdBytes"), p.getLong("appPssBytes"), p.getLong("appRssBytes")) } }
                            ), seenWorkers, expectedPolicy)
                        } catch (_: Exception) { false }
                        val published = SystemClock.elapsedRealtime()
                        val finalAccepted = accepted && (!adherence || (shapeMatched && published - started < 30000))
                        if (adherence) sample.put("finishedAtMonotonicMs", published).put("wallMs", published - started)
                            .put("delivery", if (finalAccepted) result.delivery.name else "REVOKED")
                        sample.put("caseGateAccepted", finalAccepted)
                        output.writeText(report.toString(2))
                        if (!finalAccepted) {
                            report.put("blockingGate", if (adherence) "OUTPUT_ADHERENCE" else "V4_FEASIBILITY"); break
                        }
                        seenWorkers += sample.getString("workerInstance")
                    } finally {
                        call.cancel(); active = null; telemetry.stop()
                        output.writeText(report.toString(2))
                    }
                }
            } finally {
                active?.cancel(); telemetry.stop(); manager.releaseForHostLoss().join()
                report.put("finalCleanup", if (manager.state.value.status == RuntimeStatus.UNLOADED) "CONFIRMED" else "UNCERTAIN")
                    .put("finishedAtMonotonicMs", SystemClock.elapsedRealtime())
                    .put("finishedAtMs", System.currentTimeMillis())
                output.writeText(report.toString(2))
            }
        }
}
private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private const val ENCODER = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"
private const val FREEZE = "eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9"
private const val OA_PREPARATION = "88a985300cd4cc0c100f6541214328e68759558393fa25b86f8fea8a13b888a2"
private const val OA_VALIDATOR = "10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000"
