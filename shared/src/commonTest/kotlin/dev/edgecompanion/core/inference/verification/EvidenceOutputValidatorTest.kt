package dev.edgecompanion.core.inference.verification

import kotlin.test.*

class EvidenceOutputValidatorTest {
    private val text = "Scope A: gap is 12 mm."
    private val context = EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", text, 0, text.length)))
    private val span = "{\"p\":\"p\",\"r\":\"1\",\"b\":16,\"e\":21,\"q\":\"12 mm\"}"
    private val valid = "{\"o\":\"S\",\"d\":[{\"i\":\"a\",\"s\":\"S\",\"e\":[$span]}]}"
    private fun parse(value: String, ctx: EvidenceContext = context, tokens: Int = 40) = EvidenceOutputValidator.validate(value, ctx, tokens)
    private fun rejected(value: String, reason: OutputRejection? = null) {
        val result = assertIs<OutputValidation.Rejected>(parse(value))
        if (reason != null) assertEquals(reason, result.reason)
    }
    @Test fun exactSpanIsStructuralValidityNotSemanticCertification() {
        val result = assertIs<OutputValidation.StructurallyValid>(parse(valid))
        assertEquals(EvidenceState.S, result.output.state)
        assertEquals("12 mm", result.output.details.single().evidence.single().q)
    }
    @Test fun jsonKeyOrderWhitespaceAndEscapedKeysDoNotChangeIdentity() {
        assertIs<OutputValidation.StructurallyValid>(parse(valid.replace("\"o\":\"S\",", "").dropLast(1) + ",\"o\":\"S\"}"))
        assertIs<OutputValidation.StructurallyValid>(parse(" \r\n" + valid.replace("\"o\"", "\"\\u006f\"") + "\t"))
    }
    @Test fun duplicateKeysIncludingDecodedEscapesNeverOverwrite() {
        rejected(valid.replace("\"o\":\"S\"", "\"o\":\"S\",\"\\u006f\":\"S\""))
        rejected(valid.replace("\"i\":\"a\"", "\"i\":\"a\",\"i\":\"a\""))
        rejected(valid.replace("\"r\":\"1\"", "\"r\":\"1\",\"r\":\"1\""))
    }
    @Test fun strictJsonRejectsLenientSyntaxAndTrailingValues() {
        for (s in listOf("```json\n$valid\n```", "$valid{}", "/*x*/$valid", valid.replace('"', '\''),
            valid.replace("\"S\"", "S"), valid.replace("16", "016"), valid.replace("16", "NaN"),
            valid.replace("16", "Infinity"), valid.replace("16", "+16"), valid.replace("16", "1."),
            valid.replace("16", "1e"), valid.replace("16", ".16"), valid.dropLast(1),
            valid.replace("\"12 mm\"", "\"12\nmm\""), valid.replace("\"p\":\"p\"", "\"p\":\"\\x70\""))) rejected(s)
    }
    @Test fun unknownMissingAndWrongTypeFieldsFailClosed() {
        for (s in listOf(valid.replace("\"o\":", "\"answer\":"), valid.replace("\"o\":\"S\",", ""),
            valid.replace("\"S\"", "null"), valid.replace("\"d\":[", "\"d\":{bad:"),
            valid.replace("\"r\":\"1\"", "\"r\":1"), valid.replace("\"b\":16", "\"b\":\"16\""),
            valid.replace("\"o\":\"S\"", "\"o\":\"S\",\"extra\":0"))) rejected(s)
    }
    @Test fun detailIdentityCompletenessAndOrderAreEnforced() {
        rejected(valid.replace("\"i\":\"a\"", "\"i\":\"b\""))
        rejected("{\"o\":\"U\",\"d\":[]}")
        val detail = "{\"i\":\"a\",\"s\":\"U\",\"e\":[]}"
        rejected("{\"o\":\"U\",\"d\":[$detail,$detail]}")
        val two = EvidenceContext(listOf("a", "b"), listOf(DeliveredPassage("p", "1", text, 0, text.length)))
        val reverse = "{\"o\":\"U\",\"d\":[{\"i\":\"b\",\"s\":\"U\",\"e\":[]},$detail]}"
        assertIs<OutputValidation.Rejected>(parse(reverse, two))
    }
    @Test fun referencesCannotEscapePackOrRevision() {
        rejected(valid.replace("\"p\":\"p\"", "\"p\":\"unknown\""), OutputRejection.REFERENCE)
        rejected(valid.replace("\"r\":\"1\"", "\"r\":\"2\""), OutputRejection.REFERENCE)
        val clipped = EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", text, 0, 10)))
        assertEquals(OutputRejection.SPAN, assertIs<OutputValidation.Rejected>(parse(valid, clipped)).reason)
        rejected(valid.replace("\"e\":21", "\"e\":210"), OutputRejection.SPAN)
        rejected(valid.replace("\"b\":16", "\"b\":-1"), OutputRejection.SPAN)
        rejected(valid.replace("\"b\":16", "\"b\":21"), OutputRejection.SPAN)
    }
    @Test fun quotationEqualityIsExactNotNormalizedOrParaphrased() {
        rejected(valid.replace("12 mm", "13 mm"), OutputRejection.QUOTATION)
        val original = "🌐 Cafe\u0301"
        val ctx = EvidenceContext(listOf("a"), listOf(DeliveredPassage("p", "1", original, 0, original.length)))
        val output = valid.replace("16", "0").replace("21", "2").replace("12 mm", "🌐")
        assertIs<OutputValidation.StructurallyValid>(parse(output, ctx))
        assertEquals(OutputRejection.UNICODE, assertIs<OutputValidation.Rejected>(parse(output.replace("\"e\":2", "\"e\":1"), ctx)).reason)
        val combining = valid.replace("16", "3").replace("21", "8").replace("12 mm", "Cafe\u0301")
        assertIs<OutputValidation.StructurallyValid>(parse(combining, ctx))
        assertIs<OutputValidation.Rejected>(parse(combining.replace("Cafe\u0301", "Café"), ctx))
    }
    @Test fun malformedUnicodeIncludingEscapedSurrogatesIsRejected() {
        rejected(valid.replace("12 mm", "\\uD800"), OutputRejection.UNICODE)
        rejected(valid.replace("12 mm", "\\uDC00"), OutputRejection.UNICODE)
        rejected(valid.replace("12 mm", "\uD800"), OutputRejection.UNICODE)
    }
    @Test fun numericCoordinatesCannotRoundFractionsIntoIntegers() {
        rejected(valid.replace("16", "16.00000000000000000001"), OutputRejection.SPAN)
        rejected(valid.replace("16", "1e999"), OutputRejection.SPAN)
        assertIs<OutputValidation.StructurallyValid>(parse(valid.replace("16", "160e-1")))
        assertIs<OutputValidation.StructurallyValid>(parse(valid.replace("21", "21.000")))
    }
    @Test fun outputBytesTokensAndNestedCollectionsStayBounded() {
        assertIs<OutputValidation.Rejected>(parse(valid, tokens = 513))
        assertIs<OutputValidation.Rejected>(parse(valid, tokens = -1))
        rejected(" ".repeat(1637), OutputRejection.SIZE)
        rejected("🌐".repeat(400), OutputRejection.SIZE)
        rejected(valid.replace("[$span]", "[$span,$span,$span]"))
        rejected("{\"o\":\"U\",\"d\":" + "[".repeat(1000))
    }
    @Test fun duplicateCitationsAndOutcomeInconsistencyAreRejected() {
        rejected(valid.replace("[$span]", "[$span,$span]"), OutputRejection.SCHEMA)
        rejected(valid.replace("\"o\":\"S\"", "\"o\":\"M\""), OutputRejection.SCHEMA)
        rejected(valid.replace("\"s\":\"S\"", "\"s\":\"M\""), OutputRejection.SCHEMA)
        rejected(valid.replace("\"s\":\"S\"", "\"s\":\"C\""), OutputRejection.SCHEMA)
    }
    @Test fun conflictNeedsTwoDistinctOrderedDeliveredRecords() {
        val ctx = EvidenceContext(listOf("a"), listOf("p", "z").map { DeliveredPassage(it, "1", text, 0, text.length) })
        val second = span.replace("\"p\":\"p\"", "\"p\":\"z\"")
        val conflict = valid.replace("\"S\"", "\"C\"").replace("[$span]", "[$span,$second]")
        assertIs<OutputValidation.StructurallyValid>(parse(conflict, ctx))
        assertIs<OutputValidation.Rejected>(parse(conflict.replace("[$span,$second]", "[$second,$span]"), ctx))
        // Both strings happen to assert the same fact: structural validity alone cannot certify conflict.
    }
    @Test fun missingOverallCanRetainACorrectlyStructuredPartialDetail() {
        val ctx = EvidenceContext(listOf("a", "b"), listOf(DeliveredPassage("p", "1", text, 0, text.length)))
        val partial = valid.replace("\"o\":\"S\"", "\"o\":\"M\"").dropLast(2) + ",{\"i\":\"b\",\"s\":\"M\",\"e\":[]}]}"
        assertIs<OutputValidation.StructurallyValid>(parse(partial, ctx))
    }
    @Test fun arbitraryBoundedGarbageCannotThrowOrObtainAValidDecision() {
        var seed = 72
        repeat(1000) {
            val value = buildString { repeat(it % 210) { seed = seed * 1664525 + 1013904223; append((32 + (seed ushr 1) % 95).toChar()) } }
            assertIs<OutputValidation.Rejected>(parse(value))
        }
    }
    @Test fun contextSnapshotsCannotBeRetargetedByChangingCallerLists() {
        val ids = mutableListOf("a"); val passages = mutableListOf(DeliveredPassage("p", "1", text, 0, text.length))
        val ctx = EvidenceContext(ids, passages); ids[0] = "z"; passages.clear()
        assertIs<OutputValidation.StructurallyValid>(parse(valid, ctx))
    }
    @Test fun aggregateDetailStatesAndOverallStateUseTheSameEvidenceSet() {
        assertIs<OutputValidation.StructurallyValid>(parse("{\"o\":\"M\",\"d\":[{\"i\":\"a\",\"s\":\"M\",\"e\":[]}]}"))
        rejected("{\"o\":\"M\",\"d\":[{\"i\":\"a\",\"s\":\"U\",\"e\":[]}]}", OutputRejection.SCHEMA)
    }
}
