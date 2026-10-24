package dev.edgecompanion.core.inference.verification

import dev.edgecompanion.core.inference.ChatMessage
import dev.edgecompanion.core.inference.ChatRequest
import dev.edgecompanion.core.inference.ChatRole
import dev.edgecompanion.core.inference.GemmaChatBudget
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.*

/** Synthetic host fixtures, not generated model responses or native feasibility evidence. */
class OutputAdherenceFixtureTest {
    private val project = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }
    private val root = File(project, "planning/fixtures/output-adherence-v1")
    private data class Sample(val name: String, val expected: String, val tokens: Int, val output: String)
    private data class Control(val id: String, val question: String, val payload: String, val details: List<String>,
        val expected: String, val states: List<String>, val passages: MutableList<DeliveredPassage> = mutableListOf(),
        val samples: MutableList<Sample> = mutableListOf()) {
        fun context() = EvidenceContext(details, passages)
        fun shape(output: EvidenceOutput) = output.state.name == expected &&
            output.details.map { it.id } == details && output.details.map { it.state.name } == states
    }
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    private fun decode(text: String) = Base64.getDecoder().decode(text).decodeToString(throwOnInvalidSequence = true)
    private fun controls(): List<Control> {
        val bytes = File(root, "validator-cases.tsv").readBytes()
        assertEquals(File(root, "validator-cases.sha256").readText().trim(), digest(bytes))
        val controls = linkedMapOf<String, Control>()
        for (line in bytes.decodeToString(throwOnInvalidSequence = true).lineSequence().filter { it.isNotEmpty() && !it.startsWith("#") }) {
            val f = line.split('\t')
            when (f[0]) {
                "Q" -> {
                    assertEquals(7, f.size)
                    assertNull(controls.put(f[1], Control(f[1], decode(f[2]), decode(f[3]), f[4].split(','), f[5], f[6].split(','))))
                }
                "P" -> {
                    assertEquals(7, f.size)
                    controls.getValue(f[1]).passages += DeliveredPassage(f[2], f[3], decode(f[6]), f[4].toInt(), f[5].toInt())
                }
                "O" -> {
                    assertEquals(6, f.size)
                    controls.getValue(f[1]).samples += Sample(f[2], f[3], f[4].toInt(), decode(f[5]))
                }
                else -> fail("Unknown fixture row")
            }
        }
        assertEquals(listOf("oa01", "oa02", "oa03", "oa04", "oa05", "oa06"), controls.keys.toList())
        return controls.values.toList()
    }

    @Test fun exactFixturePayloadsPassTheRealSharedGemmaBudget() {
        for (c in controls()) {
            val request = ChatRequest(c.id, listOf(ChatMessage(ChatRole.USER, c.payload)))
            assertEquals(128L + 96 + c.payload.encodeToByteArray().size, GemmaChatBudget.validate(request))
            assertTrue(c.payload.encodeToByteArray().size <= 1312)
            assertTrue(c.question.encodeToByteArray().size <= 180)
        }
    }

    @Test fun allSyntheticPositiveAndNegativeRowsUseTheUnchangedStrictValidator() {
        var count = 0
        for (c in controls()) for (sample in c.samples) {
            val result = EvidenceOutputValidator.validate(sample.output, c.context(), sample.tokens)
            val message = "${c.id}/${sample.name}"
            if (sample.expected == "VALID") assertIs<OutputValidation.StructurallyValid>(result, message)
            else assertEquals(sample.expected, assertIs<OutputValidation.Rejected>(result, message).reason.name, message)
            count++
        }
        assertTrue(count >= 80)
    }

    @Test fun supportedAndConflictControlsCannotPassByEmittingOnlyValidAbstentions() {
        for (c in controls()) {
            val positive = c.samples.single { it.name == "synthetic-valid" }
            val valid = assertIs<OutputValidation.StructurallyValid>(EvidenceOutputValidator.validate(positive.output, c.context(), positive.tokens))
            assertTrue(c.shape(valid.output))
            val sample = c.samples.single { it.name == "synthetic-abstain" }
            val abstain = assertIs<OutputValidation.StructurallyValid>(EvidenceOutputValidator.validate(sample.output, c.context(), sample.tokens))
            assertEquals(c.id == "oa04", c.shape(abstain.output), c.id)
        }
    }

    @Test fun partialAndUnicodeControlsRetainDetailOrderAndAlternativeExactQuotations() {
        val controls = controls()
        assertEquals(listOf("S", "M"), controls.single { it.id == "oa05" }.states)
        val c = controls.single { it.id == "oa06" }
        val sample = c.samples.single { it.name == "unicode-exact" }
        val valid = assertIs<OutputValidation.StructurallyValid>(EvidenceOutputValidator.validate(sample.output, c.context(), sample.tokens))
        assertTrue(c.shape(valid.output))
        assertEquals(c.passages.single().original, valid.output.details.single().evidence.single().q)
    }

    @Test fun strictValidatorSourceMatchesThePreservedReviewedHash() {
        val source = File(project, "shared/src/commonMain/kotlin/dev/edgecompanion/core/inference/verification/EvidenceOutputValidator.kt")
        assertEquals("10be77c8586f0ee9931c9c33b965acd3608fe379010afde75e27b1dfd5526000", digest(source.readBytes()))
    }
}
