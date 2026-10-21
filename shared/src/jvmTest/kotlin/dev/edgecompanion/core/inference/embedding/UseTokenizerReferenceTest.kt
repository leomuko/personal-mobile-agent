package dev.edgecompanion.core.inference.embedding

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*
import org.junit.Assume.assumeTrue

class UseTokenizerReferenceTest {
    private fun directory(): File {
        val path = System.getenv("P2_ENCODER_COUNTING_DIR")
        assumeTrue("Opt-in synthetic tokenizer proof requires P2_ENCODER_COUNTING_DIR", path != null)
        return File(requireNotNull(path))
    }
    private fun open(root: File): UseTokenizer = UseTokenizer.open(File(root, "tokenizer.bin").readBytes()) { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
    private fun unhex(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val digest: (ByteArray) -> String = { bytes -> MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } }

    @Test fun fullTokenIdsAndAdmissionMatchPinnedUpstream() = runTest {
        val root = directory()
        val tokenizer = open(root)
        val admission = UseAdmission(tokenizer, { it.encodeToByteArray() }, digest)
        val boundaries = mutableSetOf<Int>()
        var cases = 0
        File(root, "reference.tsv").forEachLine { line ->
            val fields = line.split('\t')
            assertEquals(3, fields.size)
            val text = unhex(fields[0]).decodeToString(throwOnInvalidSequence = true)
            val expected = fields[1].split(',').map(String::toInt)
            assertEquals(expected, tokenizer.tokens(text, {}, { unhex(fields[2]) }), "Token IDs differ at synthetic case $cases")
            boundaries.add(expected.size)
            cases++
        }
        assertTrue(cases >= 1000)
        assertTrue(boundaries.containsAll(listOf(191, 192, 193)))
        for (repeat in 185..195) {
            val input = EncoderInput(" hello".repeat(repeat))
            val count = tokenizer.tokens(input.text, {}, { it.encodeToByteArray() }).size
            when (val result = admission.admit(input)) {
                is EncoderAdmission.Accepted -> {
                    assertTrue(count <= 192)
                    assertEquals(listOf(2, 2, count), result.receipt.branchTokenCounts)
                    assertTrue(admission.matches(result.receipt, input))
                    assertFalse(admission.matches(result.receipt, EncoderInput(input.text + "x")))
                    assertFalse(UseAdmission(tokenizer, { it.encodeToByteArray() }, digest).matches(result.receipt, input))
                }
                is EncoderAdmission.Rejected -> { assertTrue(count > 192); assertEquals(AdmissionError.TOKEN_LIMIT, result.error) }
            }
        }
    }

    @Test fun malformedOversizedAndCancelledInputsCannotBeAdmitted() = runTest {
        val tokenizer = open(directory())
        val admission = UseAdmission(tokenizer, { it.encodeToByteArray() }, digest)
        for (text in listOf("\ud800", "\udc00", "x\ud800y"))
            assertEquals(EncoderAdmission.Rejected(AdmissionError.MALFORMED_INPUT), admission.admit(EncoderInput(text)))
        assertEquals(EncoderAdmission.Rejected(AdmissionError.INPUT_TOO_LARGE), admission.admit(EncoderInput("x".repeat(16_385))))
        assertEquals(EncoderAdmission.Rejected(AdmissionError.INPUT_TOO_LARGE), admission.admit(EncoderInput("中".repeat(6000))))
        var checks = 0
        assertFailsWith<CancellationException> { tokenizer.tokens("abc ".repeat(1000), { if (++checks == 3) throw CancellationException() }, { it.encodeToByteArray() }) }
        assertTrue(checks == 3)
        assertFailsWith<IllegalArgumentException> { UseTokenizer.open(ByteArray(384_344)) { "wrong" } }
    }
}
