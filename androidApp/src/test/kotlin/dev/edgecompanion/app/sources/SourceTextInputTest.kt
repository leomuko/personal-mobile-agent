package dev.edgecompanion.app.sources

import dev.edgecompanion.core.sources.model.*
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class SourceTextInputTest {
    @Test fun inputIsBoundedAndClosedOnSuccessAndLimitFailure() {
        for (size in listOf(3, SourceLimits.TEXT_BYTES, SourceLimits.TEXT_BYTES + 100)) {
            var closed = false
            var consumed = 0
            val input = object : ByteArrayInputStream(ByteArray(size) { 65 }) {
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int = super.read(bytes, offset, count).also {
                    if (it > 0) consumed += it
                }
                override fun close() { closed = true; super.close() }
            }
            try {
                assertEquals(size, readSourceTextBytes(input).size)
                assertTrue(size <= SourceLimits.TEXT_BYTES)
            } catch (failure: SourceException) {
                assertEquals(SourceError.LIMIT, failure.error)
                assertTrue(size > SourceLimits.TEXT_BYTES)
            }
            assertTrue(closed); assertTrue(consumed <= SourceLimits.TEXT_BYTES + 1)
        }
    }
    @Test fun nonProgressingAndFailingProviderCloseWithoutReturningPartialText() {
        for (zero in listOf(true, false)) {
            var closed = false
            val input = object : InputStream() {
                override fun read(): Int = error("Not used")
                override fun read(bytes: ByteArray, offset: Int, count: Int): Int = if (zero) 0 else error("Provider failure")
                override fun close() { closed = true }
            }
            try { readSourceTextBytes(input); fail("No partial output expected") }
            catch (_: Exception) { assertTrue(closed) }
        }
    }
}
