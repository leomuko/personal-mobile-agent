package dev.edgecompanion.app

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Public synthetic corpus -> actual SDK transport bytes; no model is loaded or encoded. */
class EncoderTransportCaptureTest {
    @Test fun captureBoundedSyntheticPacketBytes() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("p2Transport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        val runId = requireNotNull(args.getString("transportRunId"))
        val root = File(context.noBackupFilesDir, "qa-embedding")
        val input = File(root, "transport-inputs.txt")
        require(input.length() in 1..4_000_000)
        val bytes = Class.forName("dev.edgecompanion.app.prerequisites.SdkPacketTransport").getMethod("bytes", String::class.java)
        var cases = 0
        val output = File(root, "transport.tsv")
        output.bufferedWriter().use { writer -> input.forEachLine { hex ->
            require(hex.length <= 32_768 && hex.length % 2 == 0)
            val text = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray().decodeToString(throwOnInvalidSequence = true)
            val actual = bytes.invoke(null, text) as ByteArray
            require(actual.size <= 16_384)
            writer.append(hex).append('\t').append(actual.joinToString("") { "%02x".format(it) }).append('\n')
            cases++
        } }
        check(cases == 1038)
        File(root, "transport.json").writeText(JSONObject().put("runId", runId).put("cases", cases)
            .put("transportSha256", MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") { "%02x".format(it) })
            .put("fingerprint", Build.FINGERPRINT).put("sdk", Build.VERSION.SDK_INT).put("abi", Build.SUPPORTED_ABIS.first())
            .put("nativeEncoderInvoked", false).put("recordedAtMs", System.currentTimeMillis()).toString(2))
    }
}
