package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*
import kotlin.test.*

class SourceTextRulesTest {
    @Test fun strictUtf8RejectsBinaryMalformedAndOversizedInputs() {
        assertEquals("hello", SourceTextRules.decode(byteArrayOf(-17, -69, -65) + "hello".encodeToByteArray()))
        assertEquals(SourceError.INVALID_TEXT, assertFailsWith<SourceException> {
            SourceTextRules.decode(byteArrayOf(0xC0.toByte(), 0xAF.toByte())) }.error)
        assertFailsWith<SourceException> { SourceTextRules.validate("hello\u0000world") }
        assertFailsWith<SourceException> { SourceTextRules.validate("\uD800") }
        SourceTextRules.validate("a".repeat(SourceLimits.TEXT_BYTES))
        assertEquals(SourceError.LIMIT, assertFailsWith<SourceException> {
            SourceTextRules.validate("a".repeat(SourceLimits.TEXT_BYTES + 1)) }.error)
    }
    @Test fun chunksRetainExactUtf16SpansWithoutSplittingSurrogates() {
        val text = "a".repeat(2047) + "\uD83D\uDE00" + "b".repeat(5000)
        val chunks = SourceTextRules.chunks(text)
        assertEquals(text, chunks.joinToString("") { it.text })
        chunks.forEach {
            assertEquals(text.substring(it.start.toInt(), it.end.toInt()), it.text)
            assertFalse(it.text.first().isLowSurrogate()); assertFalse(it.text.last().isHighSurrogate())
            assertTrue(it.text.length <= SourceLimits.CHUNK_CHARS)
        }
    }
    @Test fun ftsInputIsBoundedLiteralTermsNotAnOperatorLanguage() {
        assertEquals("\"contract\" \"OR\" \"coffee\"", SourceTextRules.query("contract OR coffee*"))
        assertNull(SourceTextRules.query("\"*():"))
        assertFailsWith<SourceException> { SourceTextRules.query((1..9).joinToString(" ")) }
        assertFailsWith<SourceException> { SourceTextRules.title("\n") }
        assertFailsWith<SourceException> { SourceTextRules.title("x".repeat(181)) }
        assertFailsWith<SourceException> { SourceTextRules.title("\uD800") }
    }
}
