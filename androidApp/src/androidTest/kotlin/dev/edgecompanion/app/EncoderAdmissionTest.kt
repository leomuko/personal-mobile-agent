package dev.edgecompanion.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.inference.embedding.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class EncoderAdmissionTest {
    @Test fun pinnedAndroidCountingAndOverflowAreVerified(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(InstrumentationRegistry.getArguments().getString("p2Admission") == "true")
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".secureqa"))
        val root = context.noBackupFilesDir
        val output = File(root, "p2-encoder-admission.json")
        val report = JSONObject().put("schema", 1).put("passed", false).put("nativeEncoderInvoked", false)
            .put("runId", InstrumentationRegistry.getArguments().getString("admissionRunId") ?: UUID.randomUUID().toString())
            .put("startedAtMs", System.currentTimeMillis())
        output.writeText(report.toString(2))
        try {
        val model = File(root, "qa-embedding/89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749.tflite")
        val reference = File(root, "qa-embedding/counting-reference.tsv")
        require(reference.length() in 1..8_000_000)
        val method = Class.forName("dev.edgecompanion.app.prerequisites.SdkPacketTransport").getMethod("bytes", String::class.java)
        val transport: (String) -> ByteArray = { method.invoke(null, it) as ByteArray }
        val admission = AndroidUseAdmission.open(model, internalBuild = BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "secureQa", transport)
        var cases = 0
        var rejected = 0
        val boundaries = mutableSetOf<Int>()
        reference.bufferedReader().use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                require(line.length <= 100_000)
                val fields = line.split('\t')
                require(fields.size == 3)
                val text = fields[0].chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString(throwOnInvalidSequence = true)
                assertArrayEquals("Packaged SDK bytes differ from oracle input",
                    fields[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray(), transport(text))
                val expected = fields[1].split(',').size
                boundaries += expected
                when (val result = admission.admit(EncoderInput(text))) {
                    is EncoderAdmission.Accepted -> {
                        assertTrue(expected <= 192)
                        assertEquals(listOf(2, 2, expected), result.receipt.branchTokenCounts)
                        assertTrue(admission.matches(result.receipt, EncoderInput(text)))
                        assertFalse(admission.matches(result.receipt, EncoderInput(text + "changed")))
                    }
                    is EncoderAdmission.Rejected -> {
                        assertEquals(AdmissionError.TOKEN_LIMIT, result.error)
                        assertEquals(expected, result.actualTokens)
                        assertTrue(expected > 192)
                        rejected++
                    }
                }
                cases++
            }
        }
        assertTrue(cases >= 1000)
        assertTrue(boundaries.containsAll(listOf(191, 192, 193)))
        assertTrue(rejected > 0)
        for (text in listOf("\ud800", "\udc00"))
            assertEquals(EncoderAdmission.Rejected(AdmissionError.MALFORMED_INPUT), admission.admit(EncoderInput(text)))
        assertEquals(EncoderAdmission.Rejected(AdmissionError.INPUT_TOO_LARGE), admission.admit(EncoderInput("a".repeat(16385))))
        report.put("passed", true)
            .put("cases", cases).put("rejected", rejected).put("nativeEncoderInvoked", false)
            .put("jniTransportVerified", true).put("transport", "mediapipe-packet-bytes-v1")
            .put("scope", "Android counting and immutable receipt only; no native handoff")
            .put("recordedAtMs", System.currentTimeMillis())
        } finally { report.put("finishedAtMs", System.currentTimeMillis()); output.writeText(report.toString(2)) }
    }
}
