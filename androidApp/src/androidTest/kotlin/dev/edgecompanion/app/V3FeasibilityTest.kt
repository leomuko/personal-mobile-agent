package dev.edgecompanion.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.app.support.DeviceSupervision
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
class V3FeasibilityTest {
    @Test fun boundedWholePipelineWithIndependentCleanup(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("p2V3") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(BuildConfig.DEBUG && context.packageName.endsWith(".secureqa"))
        val app = context.applicationContext as CompanionApplication
        val manager = app.getKoin().get<ModelManager>()
        val telemetry = app.getKoin().get<MemoryTelemetryRecorder>()
        val resources = AndroidModelResources(context)
        val planFile = File(context.noBackupFilesDir, "qa-embedding/v3-feasibility.json")
        check(planFile.length() in 1..65536)
        val planBytes = planFile.readBytes()
        val planSha = requireNotNull(args.getString("v3PlanSha256"))
        check(sha(planBytes) == planSha)
        val plan = JSONObject(planBytes.toString(Charsets.UTF_8)); planBytes.fill(0)
        check(plan.getInt("schema") == 3 && plan.getString("scope") == "v3-small-supervised-feasibility")
        check(plan.getString("freezeSha256") == FREEZE && plan.getInt("deadlineMs") == 5000)
        check(plan.getInt("contextTokens") == 2048 && plan.getInt("outputTokens") == 512)
        val entries = plan.getJSONArray("entries"); check(entries.length() == 8)
        val ids = listOf("v3k01qc", "v3k01qm", "v3k01qs", "v3k01qt")
        val states = listOf("cold-all-unloaded", "encoder-fresh-Gemma-unloaded")
        for (i in 0 until 8) {
            check(entries.getJSONObject(i).getString("id") == ids[i % 4])
            check(entries.getJSONObject(i).getString("state") == states[i / 4])
        }
        val type = Class.forName("dev.edgecompanion.app.prerequisites.AndroidForegroundEncoder")
        val model = File(context.noBackupFilesDir, "qa-embedding/$ENCODER.tflite")
        val safe: () -> Boolean = { manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null }
        fun newEncoder() = type.constructors.single().newInstance(context, model, safe) as ForegroundEncoder
        var encoder = newEncoder()
        var active: VerificationCall? = null
        val report = JSONObject().put("schema", 3).put("runId", args.getString("supervisionRunId"))
            .put("planSha256", planSha).put("freezeSha256", FREEZE).put("startedAtMs", System.currentTimeMillis())
            .put("policyId", manager.memoryPolicy.id).put("semanticEnablement", false).put("largeCampaignAllowed", false)
        val cases = JSONArray(); report.put("cases", cases)
        val output = File(context.noBackupFilesDir, "p2-v3-feasibility.json")
        DeviceSupervision.open(requireNotNull(args.getString("supervisionRunId"))) {
            runBlocking { active?.cancel(); manager.releaseForHostLoss().join(); manager.state.value.status == RuntimeStatus.UNLOADED }
        }.use {
            try {
                manager.releaseForHostLoss().join(); check(manager.state.value.status == RuntimeStatus.UNLOADED)
                for (index in 0 until entries.length()) {
                    val row = entries.getJSONObject(index)
                    check(safe())
                    check(manager.state.value.status == RuntimeStatus.UNLOADED)
                    // Cold creates a new admission adapter. Every native worker is fresh and
                    // must die before Gemma; the second state warms only admission metadata.
                    if (index < 4) encoder = newEncoder()
                    val started = SystemClock.elapsedRealtime()
                    val payload = row.getString("payload")
                    check(sha(payload.toByteArray()) == row.getString("packSha256"))
                    val dataStart = payload.indexOf("\nDATA\n") + 6
                    check(dataStart >= 6 && payload.endsWith("\nEND DATA"))
                    val data = JSONObject(payload.substring(dataStart, payload.length - 9))
                    check(data.getString("q") == row.getString("question"))
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
                        .put("packSha256", row.getString("packSha256")).put("backend", "GPU").put("inputBound", bound)
                        .put("tokenizerInitialState", if (index < 4) "cold" else "cached-admission")
                        .put("nativeEncoderInitialState", "no-resident-worker").put("gemmaInitialState", "UNLOADED")
                    cases.put(sample); output.writeText(report.toString(2))
                    telemetry.start()
                    val call = manager.verifyEvidence("v3-$index", payload, validationContext, EncoderInput(row.getString("question")),
                        encoder, started, 1) { 1 }
                    active = call
                    try {
                        // This is a cleanup watchdog, not a longer inference deadline.
                        val result = withTimeout(25_000) { call.events.single() }
                        val snapshot = resources.snapshot()
                        sample.put("outcome", result.outcome?.name).put("decisionMs", result.decisionMs)
                            .put("delivery", result.delivery.name).put("telemetryFailed", manager.state.value.telemetryFailed)
                            .put("cleanupMs", result.cleanupMs).put("wallMs", SystemClock.elapsedRealtime() - started)
                            .put("runtimeCleanup", result.cleanup.name).put("encoderCleanup", call.preparation.value.cleanup.name)
                            .put("runtimeStatus", manager.state.value.status.name).put("error", result.error?.name)
                            .put("validationMs", result.validationMs).put("firstTextMs", result.firstTextMs)
                            .put("encoderPreparationMs", call.preparation.value.retrievalMs)
                            .put("sdkInputTokens", manager.state.value.inputTokens).put("sdkOutputTokens", manager.state.value.outputTokens)
                            .put("lowMemory", snapshot.lowMemory).put("thermalStatus", snapshot.thermalStatus)
                        val validation = result.validation
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
                        check(result.cleanup == EncoderCleanup.CONFIRMED && call.preparation.value.cleanup == EncoderCleanup.CONFIRMED)
                        check(manager.state.value.status == RuntimeStatus.UNLOADED)
                        if (loads.isNotEmpty()) {
                            check(field("getConfirmedDeath") == true)
                            check(loads.all { it >= field("getDeathAtMs") as Long })
                        }
                        if (result.outcome == VerificationOutcome.STRUCTURALLY_VALID && result.delivery == VerificationDelivery.ELIGIBLE) {
                            check(!manager.state.value.telemetryFailed && loads.size == 1)
                            check(field("getConfirmedDeath") == true && workerEvents.size >= 3)
                            check(workerEvents.take(3).map { it.javaClass.getMethod("getPhase").invoke(it) } == listOf("CHECKING", "LOADING", "ENCODING"))
                        }
                        output.writeText(report.toString(2))
                        if (result.outcome != VerificationOutcome.STRUCTURALLY_VALID || result.delivery != VerificationDelivery.ELIGIBLE || requireNotNull(result.decisionMs) >= 5000) {
                            report.put("blockingGate", "V3_FEASIBILITY"); break
                        }
                    } finally {
                        call.cancel(); active = null; telemetry.stop()
                        output.writeText(report.toString(2))
                    }
                }
            } finally {
                active?.cancel(); telemetry.stop(); manager.releaseForHostLoss().join()
                report.put("finalCleanup", if (manager.state.value.status == RuntimeStatus.UNLOADED) "CONFIRMED" else "UNCERTAIN")
                    .put("finishedAtMs", System.currentTimeMillis())
                output.writeText(report.toString(2))
            }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private const val ENCODER = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"
        private const val FREEZE = "eef782b3c37ac39f21871712553e366cd5fe8a40a76ded585955bb5a38db8db9"
    }
}
