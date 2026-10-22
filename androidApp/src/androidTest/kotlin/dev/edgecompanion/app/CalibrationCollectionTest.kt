package dev.edgecompanion.app

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.support.DeviceSupervision
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.embedding.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

/** Review-gated bounded batch; no relevance judgments or policy fitting on Android. */
class CalibrationCollectionTest {
    @Test fun reviewedBatchCollectionWithConfirmedWorkerCleanup(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("p2Calibration") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        val manager = (context.applicationContext as CompanionApplication).getKoin().get<ModelManager>()
        val resources = AndroidModelResources(context)
        val fixtureFile = File(context.noBackupFilesDir, "qa-embedding/calibration-batch.json")
        check(fixtureFile.length() in 1..131072)
        val raw = fixtureFile.readBytes()
        val batchSha = requireNotNull(args.getString("calibrationBatchSha256"))
        check(batchSha.matches(Regex("[a-f0-9]{64}")) && sha(raw) == batchSha)
        val fixture = JSONObject(raw.toString(Charsets.UTF_8)); raw.fill(0)
        check(fixture.getInt("schema") == 2 && fixture.getString("protocolId") == "calibration-policy-qualification-v2")
        val entries = fixture.getJSONArray("entries")
        check(entries.length() in 1..32)
        for (kind in listOf("passage", "query")) {
            val selected = JSONArray()
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                check(entry.getString("kind") in listOf("passage", "query"))
                check(entry.getString("text").toByteArray().size in 1..4096)
                if (entry.getString("kind") == kind) selected.put(entry)
            }
            fixture.put(if (kind == "passage") "passages" else "queries", selected)
        }
        val model = File(context.noBackupFilesDir, "qa-embedding/$MODEL_SHA.tflite")
        val type = Class.forName("dev.edgecompanion.app.prerequisites.AndroidForegroundEncoder")
        val safe: () -> Boolean = { manager.memoryPolicy.blockedReason(resources.snapshot(), MemoryAdmission.LOADING) == null }
        val real = type.constructors.single().newInstance(context, model, safe) as ForegroundEncoder
        @Suppress("UNCHECKED_CAST")
        val evidence = type.getMethod("getEvidence").invoke(real) as StateFlow<Any>
        fun field(value: Any, getter: String): Any? = value.javaClass.getMethod(getter).invoke(value)
        val report = JSONObject().put("schema", 1).put("runId", args.getString("supervisionRunId"))
            .put("fixtureSha256", batchSha).put("candidate", fixture.getJSONObject("candidate"))
            .put("policyId", manager.memoryPolicy.id).put("startedAtMs", System.currentTimeMillis())
            .put("collectionPassed", false).put("semanticReady", false).put("gemmaLoads", 0)
        val rows = JSONArray(); val safety = JSONArray()
        report.put("rows", rows).put("safetyCases", safety)
        val output = File(context.noBackupFilesDir, "p2-calibration-gate.json")
        var active: EncodingCall? = null
        DeviceSupervision.open(requireNotNull(args.getString("supervisionRunId"))) {
            runBlocking {
                active?.cancel(); manager.releaseForHostLoss().join()
                active?.preparation?.value?.cleanup != EncoderCleanup.UNCERTAIN && manager.state.value.status == RuntimeStatus.UNLOADED
            }
        }.use {
            try {
                manager.releaseForHostLoss().join()
                check(manager.state.value.status == RuntimeStatus.UNLOADED)
                check(safe())
                for (mode in listOf("counting", "loading", "encoding")) {
                    type.getMethod("useCancellationBatch", Boolean::class.javaPrimitiveType).invoke(real, mode == "encoding")
                    val counting = CompletableDeferred<Unit>()
                    val encoder = if (mode == "counting") object : ForegroundEncoder by real {
                        override suspend fun admit(input: EncoderInput): EncoderAdmission {
                            counting.complete(Unit); awaitCancellation()
                        }
                    } else real
                    val previous = field(evidence.value, "getInstance")
                    val call = manager.encode("safety-$mode", EncoderInput("Synthetic qualification cancellation."), encoder, 1) { 1 }
                    active = call
                    val result = async { call.results.single() }
                    try {
                        if (mode == "counting") withTimeout(5_000) { counting.await() }
                        else withTimeout(5_000) {
                            evidence.first { value ->
                                @Suppress("UNCHECKED_CAST") val events = field(value, "getEvents") as List<Any>
                                field(value, "getInstance") != previous && events.any {
                                    field(it, "getPhase") == if (mode == "loading") "LOADING" else "ENCODING"
                                }
                            }
                        }
                        val stopAt = SystemClock.elapsedRealtime()
                        call.cancel()
                        val terminal = withTimeout(7_000) { result.await() }
                        assertEquals(EncodingStatus.CANCELLED, terminal.status)
                        assertEquals(EncoderCleanup.CONFIRMED, call.preparation.value.cleanup)
                        val sample = JSONObject().put("mode", mode).put("outcome", terminal.status.name)
                            .put("cleanup", call.preparation.value.cleanup.name).put("stopToTerminalMs", SystemClock.elapsedRealtime() - stopAt)
                            .put("passed", true)
                        if (mode != "counting") {
                            assertEquals(true, field(evidence.value, "getConfirmedDeath"))
                            sample.put("confirmedDeath", true).put("workerInstance", field(evidence.value, "getInstance"))
                            @Suppress("UNCHECKED_CAST") val phases = field(evidence.value, "getEvents") as List<Any>
                            sample.put("workerPhases", JSONArray(phases.map { field(it, "getPhase") }))
                        }
                        safety.put(sample)
                    } finally { call.cancel(); result.cancelAndJoin(); active = null; output.writeText(report.toString()) }
                }
                type.getMethod("useCancellationBatch", Boolean::class.javaPrimitiveType).invoke(real, false)
                for ((kind, key) in listOf("passage" to "passages", "query" to "queries")) {
                    val entries = fixture.getJSONArray(key)
                    for (index in 0 until entries.length()) {
                        check(safe())
                        val entry = entries.getJSONObject(index)
                        val text = entry.getString("text")
                        var tokenCount: Int? = null
                        val encoder = object : ForegroundEncoder by real {
                            override suspend fun admit(input: EncoderInput): EncoderAdmission = real.admit(input).also { admitted ->
                                if (admitted is EncoderAdmission.Accepted) tokenCount = admitted.receipt.responseTokens
                            }
                        }
                        val started = SystemClock.elapsedRealtime()
                        val call = manager.encode(entry.getString("id"), EncoderInput(text), encoder, 1) { 1 }
                        active = call
                        val result = withTimeout(12_000) { call.results.single() }
                        check(result.status == EncodingStatus.COMPLETED) { "Synthetic encode failed: ${result.error}" }
                        check(call.preparation.value.cleanup == EncoderCleanup.CONFIRMED)
                        check(manager.state.value.status == RuntimeStatus.UNLOADED)
                        val worker = evidence.value
                        check(field(worker, "getConfirmedDeath") == true)
                        @Suppress("UNCHECKED_CAST") val events = field(worker, "getEvents") as List<Any>
                        val pss = events.maxOf { field(it, "getPssKb") as Int }
                        val snapshot = resources.snapshot()
                        rows.put(JSONObject().put("id", entry.getString("id")).put("kind", kind)
                            .put("textSha256", sha(text.toByteArray())).put("tokenCount", requireNotNull(tokenCount))
                            .put("vector", JSONArray(requireNotNull(result.values)))
                            .put("cleanup", call.preparation.value.cleanup.name).put("confirmedDeath", true)
                            .put("workerInstance", field(worker, "getInstance")).put("workerPssKb", pss)
                            .put("lowMemory", snapshot.lowMemory).put("thermalStatus", snapshot.thermalStatus)
                            .put("totalMs", SystemClock.elapsedRealtime() - started))
                        active = null
                        output.writeText(report.toString())
                    }
                }
                report.put("collectionPassed", true)
            } finally {
                active?.cancel(); manager.releaseForHostLoss().join()
                val clean = manager.state.value.status == RuntimeStatus.UNLOADED && active?.preparation?.value?.cleanup != EncoderCleanup.UNCERTAIN
                report.put("finalCleanup", if (clean) "CONFIRMED" else "UNCERTAIN")
                if (!clean) report.put("collectionPassed", false)
                report.put("finishedAtMs", System.currentTimeMillis()); output.writeText(report.toString())
            }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    companion object {
        private const val MODEL_SHA = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"
    }
}
