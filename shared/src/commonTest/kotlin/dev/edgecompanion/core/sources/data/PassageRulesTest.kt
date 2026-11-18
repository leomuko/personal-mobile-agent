package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*
import kotlin.test.*

class PassageRulesTest {
    private fun checkSpans(text: String) {
        val spans = PassageRules.split(text)
        assertEquals(text, spans.joinToString("") { it.text })
        assertTrue(spans.size <= PassageLimits.PER_SOURCE)
        spans.forEachIndexed { index, span ->
            assertEquals(text.substring(span.startUtf16, span.endUtf16), span.text)
            assertEquals(if (index == 0) 0 else spans[index - 1].endUtf16, span.startUtf16)
            assertTrue(span.text.length <= PassageLimits.TARGET_UTF16_UNITS)
            if (index != spans.lastIndex) assertTrue(span.text.length >= PassageLimits.MIN_NONFINAL_UTF16_UNITS)
            assertFalse(span.text.first().isLowSurrogate()); assertFalse(span.text.last().isHighSurrogate())
        }
        assertEquals(text.length, spans.last().endUtf16)
    }
    @Test fun wholeTextCoverageSurrogatesAndUnitsHoldAcrossBoundaries() {
        for (length in listOf(1, 255, 256, 383, 384, 385, 1000, 65536)) checkSpans("x".repeat(length))
        for (prefix in 250..390) checkSpans("a".repeat(prefix) + "😀".repeat(400) + " e\u0301 終わり")
    }
    @Test fun quotaArithmeticIncludesWorstCaseWhitespaceCuts() {
        val text = ("x".repeat(255) + " ").repeat(256)
        checkSpans(text)
        assertEquals(256, PassageRules.split(text).size)
        assertEquals(SourceLimits.SOURCES * PassageLimits.PER_SOURCE, PassageLimits.COUNT)
        assertEquals(0, PassageLimits.OVERLAP_UTF16_UNITS)
    }
    @Test fun sentenceAndWhitespaceBoundariesArePreferredWithoutRemovingText() {
        val text = "a".repeat(299) + ". " + "b".repeat(400)
        val first = PassageRules.split(text).first()
        assertEquals(300, first.endUtf16)
        checkSpans(text)
    }
    @Test fun queryTermsAreBoundedLiteralOrTermsAndTruncationIsExplicit() {
        val query = PassageRules.query("title:contract OR NEAR(coffee*) \"deadline\"")
        assertEquals("\"title\" OR \"contract\" OR \"OR\" OR \"NEAR\" OR \"coffee\" OR \"deadline\"", query.fts)
        assertFalse(query.truncated)
        assertEquals(listOf("\"Café\""), PassageRules.query("Café café CAFÉ").terms)
        assertNull(PassageRules.query("\"*():-😀").fts)
        val limited = PassageRules.query((1..20).joinToString(" "))
        assertEquals(8, limited.terms.size); assertTrue(limited.truncated)
        assertEquals(SourceError.LIMIT, assertFailsWith<SourceException> { PassageRules.query("x".repeat(1025)) }.error)
    }
    @Test fun wholeQuestionSelectionPrioritizesLateDetailsWithoutDroppingNegationOrUnits() {
        val q = PassageRules.query("Please tell me what is the name of the Zircon valve pressure rating in megapascals?")
        assertEquals(listOf("name", "Zircon", "valve", "pressure", "rating", "megapascals"),
            q.terms.take(6).map { it.removeSurrounding("\"") })
        assertFalse(q.truncated)
        val exact = PassageRules.query("not 12 mm before 2026 Café 終")
        assertEquals(7, exact.terms.size)
        assertFalse(exact.truncated)
        assertEquals(listOf("\"Who\"", "\"is\"", "\"the\""), PassageRules.query("Who is the Who").terms)
        assertEquals(listOf("\"name\"", "\"the\"", "\"Who\""), PassageRules.query("the Who name").terms)
    }
    @Test fun fixedHeapRetainsTheBestFiftyWithStableTieBreaks() {
        val heap = PassageCandidates()
        val values = (1L..1000L).reversed().map { PassageCandidate(it, (it % 7).toDouble()) }
        values.forEach { heap.add(it); assertTrue(heap.size <= 50) }
        assertEquals(values.sortedWith(compareByDescending<PassageCandidate> { it.score }.thenBy { it.id }).take(50), heap.best())
    }
}
