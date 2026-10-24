package dev.edgecompanion.app.support

import dev.edgecompanion.core.inference.verification.EvidenceOutput
import dev.edgecompanion.core.inference.verification.EvidenceState

/** QA oracle only. Never passed to the model, and never a semantic correctness claim. */
data class AdherenceDetail(val id: String, val state: EvidenceState, val passageIds: Set<String>)
data class AdherenceOracle(val state: EvidenceState, val details: List<AdherenceDetail>)

object OutputAdherenceShape {
    fun matches(output: EvidenceOutput, oracle: AdherenceOracle): Boolean =
        output.state == oracle.state && output.details.size == oracle.details.size &&
            output.details.withIndex().all { (i, d) ->
                val expected = oracle.details[i]
                d.id == expected.id && d.state == expected.state && d.evidence.map { it.p }.toSet() == expected.passageIds
            }
}
