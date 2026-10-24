package dev.edgecompanion.app

import dev.edgecompanion.app.support.*
import dev.edgecompanion.core.inference.verification.*
import org.junit.Assert.*
import org.junit.Test

class OutputAdherenceShapeTest {
    private val text = "QA scope: gap 7 mm."
    private val context = EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", text, 0, text.length)))
    private fun valid(raw: String) = (EvidenceOutputValidator.validate(raw, context, 40) as OutputValidation.StructurallyValid).output
    private val supported = AdherenceOracle(EvidenceState.S, listOf(AdherenceDetail("a", EvidenceState.S, setOf("p"))))
    @Test fun aValidAbstentionCannotPassASupportedControl() {
        val output = valid("""{"o":"U","d":[{"i":"a","s":"U","e":[]}]}""")
        assertFalse(OutputAdherenceShape.matches(output, supported))
        assertTrue(OutputAdherenceShape.matches(output, AdherenceOracle(EvidenceState.U, listOf(AdherenceDetail("a", EvidenceState.U, emptySet())))))
    }
    @Test fun aDifferentCitationIdentityCannotPassEvenIfStatesMatch() {
        val output = valid("""{"o":"S","d":[{"i":"a","s":"S","e":[{"p":"p","r":"1","b":14,"e":18,"q":"7 mm"}]}]}""")
        assertTrue(OutputAdherenceShape.matches(output, supported))
        assertFalse(OutputAdherenceShape.matches(output, supported.copy(details = listOf(AdherenceDetail("a", EvidenceState.S, setOf("other"))))))
        assertFalse(OutputAdherenceShape.matches(output, supported.copy(details = emptyList())))
    }
    @Test fun partialControlRequiresOrderedDetailStates() {
        val output = EvidenceOutput(EvidenceState.M, listOf(EvidenceDecision("a", EvidenceState.S, emptyList()), EvidenceDecision("b", EvidenceState.M, emptyList())))
        val oracle = AdherenceOracle(EvidenceState.M, listOf(AdherenceDetail("a", EvidenceState.S, emptySet()), AdherenceDetail("b", EvidenceState.M, emptySet())))
        assertTrue(OutputAdherenceShape.matches(output, oracle))
        assertFalse(OutputAdherenceShape.matches(output, oracle.copy(details = oracle.details.reversed())))
    }
}
