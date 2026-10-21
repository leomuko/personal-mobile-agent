package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*

internal object SourceTextRules {
    fun title(value: String): String {
        if (value.length > 180) throw SourceException(SourceError.INVALID_TEXT)
        val text = value.trim()
        if (text.isEmpty() || text.length > 180 || text.any { it.code < 32 })
            throw SourceException(SourceError.INVALID_TEXT)
        validate(text)
        return text
    }

    fun validate(text: String) {
        if (text.length > SourceLimits.TEXT_BYTES) throw SourceException(SourceError.LIMIT)
        if (text.isBlank() || '\u0000' in text) throw SourceException(SourceError.INVALID_TEXT)
        val encoded = try { text.encodeToByteArray(throwOnInvalidSequence = true) }
        catch (failure: CharacterCodingException) { throw SourceException(SourceError.INVALID_TEXT, failure) }
        try { if (encoded.size > SourceLimits.TEXT_BYTES) throw SourceException(SourceError.LIMIT) }
        finally { encoded.fill(0) }
    }

    fun decode(bytes: ByteArray): String {
        if (bytes.size > SourceLimits.TEXT_BYTES) throw SourceException(SourceError.LIMIT)
        return try { bytes.decodeToString(throwOnInvalidSequence = true).removePrefix("\uFEFF").also(::validate) }
        catch (failure: CharacterCodingException) { throw SourceException(SourceError.INVALID_TEXT, failure) }
    }

    fun chunks(text: String): List<SourceText> {
        val result = mutableListOf<SourceText>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + SourceLimits.CHUNK_CHARS, text.length)
            if (end < text.length && text[end - 1].isHighSurrogate()) end--
            result += SourceText(0, start.toLong(), end.toLong(), text.substring(start, end))
            start = end
        }
        return result
    }

    /** Literal AND terms only. Never pass provider/user FTS operators to SQLite. */
    fun query(value: String): String? {
        if (value.length > 256) throw SourceException(SourceError.LIMIT)
        val terms = Regex("[\\p{L}\\p{N}]+").findAll(value).map { it.value }.take(9).toList()
        if (terms.isEmpty()) return null
        if (terms.size > 8) throw SourceException(SourceError.LIMIT)
        return terms.joinToString(" ") { "\"$it\"" }
    }
}
