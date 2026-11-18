package dev.edgecompanion.core.sources.data

import dev.edgecompanion.core.sources.model.*

internal data class PassageSpan(val startUtf16: Int, val endUtf16: Int, val text: String)
internal data class PassageQuery(val fts: String?, val terms: List<String>, val truncated: Boolean, val contentTermCount: Int)

internal object PassageRules {
    // Deliberately small English question/function-word list, not an English stemmer.
    // Negation, quantities, units, names and non-English words remain searchable.
    // These words are lower priority, not forbidden: keep them when slots remain.
    private val boilerplate = setOf("a", "an", "the", "what", "which", "who", "where", "when", "why", "how",
        "is", "are", "was", "were", "do", "does", "did", "can", "could", "would", "should",
        "i", "me", "my", "we", "our", "you", "your", "it", "its", "of", "for", "to", "in", "on", "at",
        "and", "with", "please", "tell", "about")

    fun split(text: String): List<PassageSpan> {
        SourceTextRules.validate(text)
        val result = mutableListOf<PassageSpan>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length, start + PassageLimits.TARGET_UTF16_UNITS)
            if (end < text.length) {
                if (text[end - 1].isHighSurrogate()) end--
                val floor = start + PassageLimits.MIN_NONFINAL_UTF16_UNITS
                // Prefer a sentence end, otherwise the nearest whitespace. Keep all text.
                var whitespace = -1
                for (boundary in end downTo floor) {
                    val previous = text[boundary - 1]
                    if (previous.isWhitespace() && whitespace < 0) whitespace = boundary
                    if (previous in ".!?\n" && (boundary == text.length || text[boundary].isWhitespace())) {
                        end = boundary
                        whitespace = -1
                        break
                    }
                }
                if (whitespace >= 0) end = whitespace
            }
            result += PassageSpan(start, end, text.substring(start, end))
            start = end
        }
        check(result.size <= PassageLimits.PER_SOURCE)
        return result
    }

    /** Values are literal quoted terms, not an FTS expression supplied by the caller. */
    fun query(value: String): PassageQuery {
        if (value.length > PassageLimits.QUERY_UTF16_UNITS) throw SourceException(SourceError.LIMIT)
        // Inspect the whole bounded question before selection; leading filler must
        // not consume the eight slots before a trailing entity/attribute is reached.
        val all = Regex("[\\p{L}\\p{N}]+").findAll(value).map { it.value }
            .distinctBy { it.lowercase() }.toList()
        val content = all.filterNot { it.lowercase() in boilerplate }
        val terms = content + all.filter { it.lowercase() in boilerplate }
        val literals = terms.take(PassageLimits.QUERY_TERMS).map { "\"$it\"" }
        return PassageQuery(literals.takeIf { it.isNotEmpty() }?.joinToString(" OR "), literals,
            content.size > PassageLimits.QUERY_TERMS, minOf(content.size, PassageLimits.QUERY_TERMS))
    }

    fun utf8Size(text: String): Int = text.encodeToByteArray().let { bytes ->
        try { bytes.size } finally { bytes.fill(0) }
    }
}
