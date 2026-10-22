package dev.edgecompanion.core.inference.verification

enum class EvidenceState { S, C, M, U }
data class DeliveredPassage(val id: String, val revision: String, val original: String, val start: Int, val end: Int)
data class EvidenceSpan(val p: String, val r: String, val b: Int, val e: Int, val q: String)
data class EvidenceDecision(val id: String, val state: EvidenceState, val evidence: List<EvidenceSpan>)
data class EvidenceOutput(val state: EvidenceState, val details: List<EvidenceDecision>)
enum class OutputRejection { SIZE, JSON, SCHEMA, REFERENCE, SPAN, QUOTATION, UNICODE, TOKEN_LIMIT }
sealed interface OutputValidation {
    /** Structural validity is not an assertion of semantic support or model accuracy. */
    data class StructurallyValid(val output: EvidenceOutput) : OutputValidation
    data class Rejected(val reason: OutputRejection) : OutputValidation
}

class EvidenceContext(detailIds: List<String>, passages: List<DeliveredPassage>) {
    internal val details = detailIds.toList()
    internal val delivered = passages.toList()
    init {
        require(details.size in 1..2 && details.toSet().size == details.size && details.all { it.isNotBlank() })
        require(delivered.size in 1..3 && delivered.map { it.id }.toSet().size == delivered.size)
        for (p in delivered) {
            require(p.id.isNotBlank() && p.revision.isNotBlank() && p.original.length <= 65536)
            require(p.original.wellFormed() && p.start >= 0 && p.start < p.end && p.end <= p.original.length)
            require(!p.original.splitAt(p.start) && !p.original.splitAt(p.end))
            require(p.original.substring(p.start, p.end).encodeToByteArray().size <= 160)
        }
    }
}

object EvidenceOutputValidator {
    const val MAX_BYTES = 1536

    fun validate(text: String, context: EvidenceContext, generatedTokens: Int): OutputValidation {
        if (text.length > MAX_BYTES || text.encodeToByteArray().size > MAX_BYTES)
            return OutputValidation.Rejected(OutputRejection.SIZE)
        if (!text.wellFormed()) return OutputValidation.Rejected(OutputRejection.UNICODE)
        if (generatedTokens !in 1..512) return OutputValidation.Rejected(OutputRejection.TOKEN_LIMIT)
        return try {
            val result = Decoder(text).output()
            ensure(result.details.map { it.id } == context.details, OutputRejection.SCHEMA)
            var references = 0
            for (detail in result.details) {
                val spans = detail.evidence
                references += spans.size
                ensure(references <= 4, OutputRejection.SCHEMA)
                ensure(spans == spans.sortedWith(compareBy<EvidenceSpan> { it.p }.thenBy { it.b }), OutputRejection.SCHEMA)
                ensure(spans.toSet().size == spans.size, OutputRejection.SCHEMA)
                when (detail.state) {
                    EvidenceState.S -> ensure(spans.isNotEmpty(), OutputRejection.SCHEMA)
                    EvidenceState.C -> ensure(spans.map { it.p }.toSet().size == 2, OutputRejection.SCHEMA)
                    EvidenceState.M, EvidenceState.U -> ensure(spans.isEmpty(), OutputRejection.SCHEMA)
                }
                for (s in spans) {
                    val p = context.delivered.singleOrNull { it.id == s.p && it.revision == s.r }
                        ?: throw Invalid(OutputRejection.REFERENCE)
                    ensure(s.b >= p.start && s.b < s.e && s.e <= p.end, OutputRejection.SPAN)
                    ensure(!p.original.splitAt(s.b) && !p.original.splitAt(s.e), OutputRejection.UNICODE)
                    ensure(s.q.encodeToByteArray().size <= 96, OutputRejection.SIZE)
                    ensure(p.original.substring(s.b, s.e) == s.q, OutputRejection.QUOTATION)
                }
            }
            // Output detail states aggregate delivered evidence, unlike the rubric's
            // individual passage/detail matrix. Topical wrong-cycle-only evidence
            // must be expressed as aggregate M, not a passage's individual U.
            val expected = when {
                result.details.any { it.state == EvidenceState.C } -> EvidenceState.C
                result.details.all { it.state == EvidenceState.S } -> EvidenceState.S
                result.details.any { it.state != EvidenceState.U } -> EvidenceState.M
                else -> EvidenceState.U
            }
            ensure(result.state == expected, OutputRejection.SCHEMA)
            OutputValidation.StructurallyValid(result)
        } catch (failure: Invalid) { OutputValidation.Rejected(failure.reason) }
    }

    private class Invalid(val reason: OutputRejection) : Exception()
    private fun ensure(value: Boolean, reason: OutputRejection = OutputRejection.JSON) {
        if (!value) throw Invalid(reason)
    }

    // Schema-specific bounded decoder: no generic recursive trees, lenient JSON modes,
    // or object-map conversion that could silently overwrite duplicate decoded keys.
    private class Decoder(private val source: String) {
        private var at = 0
        private fun space() { while (at < source.length && source[at] in " \t\r\n") at++ }
        private fun take(c: Char): Boolean { space(); return if (at < source.length && source[at] == c) { at++; true } else false }
        private fun need(c: Char) { ensure(take(c)) }
        private fun members(allowed: Set<String>, read: (String) -> Unit) {
            need('{'); val seen = mutableSetOf<String>()
            if (!take('}')) do {
                val key = string()
                ensure(key in allowed && seen.add(key), OutputRejection.SCHEMA)
                need(':'); read(key)
                if (take('}')) break
                need(',')
            } while (true)
            ensure(seen == allowed, OutputRejection.SCHEMA)
        }
        private fun <T> array(max: Int, read: () -> T): List<T> {
            need('['); val values = mutableListOf<T>()
            if (!take(']')) do {
                ensure(values.size < max, OutputRejection.SCHEMA); values += read()
                if (take(']')) break
                need(',')
            } while (true)
            return values.toList()
        }
        fun output(): EvidenceOutput {
            var state: EvidenceState? = null; var details: List<EvidenceDecision>? = null
            members(setOf("o", "d")) { key ->
                when (key) { "o" -> state = state(); "d" -> details = array(2) { detail() } }
            }
            space(); ensure(at == source.length)
            return EvidenceOutput(requireNotNull(state), requireNotNull(details))
        }
        private fun detail(): EvidenceDecision {
            var id = ""; var state: EvidenceState? = null; var evidence = emptyList<EvidenceSpan>()
            members(setOf("i", "s", "e")) { key -> when (key) {
                "i" -> id = string(); "s" -> state = state(); "e" -> evidence = array(2) { span() }
            } }
            return EvidenceDecision(id, requireNotNull(state), evidence)
        }
        private fun span(): EvidenceSpan {
            var p = ""; var r = ""; var b = 0; var e = 0; var q = ""
            members(setOf("p", "r", "b", "e", "q")) { key -> when (key) {
                "p" -> p = string(); "r" -> r = string(); "b" -> b = integer(); "e" -> e = integer(); "q" -> q = string()
            } }
            return EvidenceSpan(p, r, b, e, q)
        }
        private fun state(): EvidenceState {
            val name = string()
            return EvidenceState.entries.firstOrNull { it.name == name } ?: throw Invalid(OutputRejection.SCHEMA)
        }

        private fun integer(): Int {
            space(); val start = at
            if (at < source.length && source[at] == '-') at++
            ensure(at < source.length)
            if (source[at] == '0') at++ else {
                ensure(source[at] in '1'..'9'); while (at < source.length && source[at] in '0'..'9') at++
            }
            if (at < source.length && source[at] == '.') { at++; digits() }
            if (at < source.length && source[at] in "eE") {
                at++; if (at < source.length && source[at] in "+-") at++; digits()
            }
            val number = source.substring(start, at)
            val parts = number.removePrefix("-").split('e', 'E')
            val mantissa = parts[0]
            var digits = mantissa.replace(".", "").trimStart('0')
            if (digits.isEmpty()) return 0
            ensure(!number.startsWith('-'), OutputRejection.SPAN)
            val exponent = if (parts.size == 1) 0 else parts[1].toIntOrNull()
                ?: throw Invalid(OutputRejection.SPAN)
            ensure(exponent in -4096..4096, OutputRejection.SPAN)
            val scale = exponent - if ('.' in mantissa) mantissa.length - mantissa.indexOf('.') - 1 else 0
            if (scale < 0) {
                ensure(-scale < digits.length && digits.takeLast(-scale).all { it == '0' }, OutputRejection.SPAN)
                digits = digits.dropLast(-scale)
            } else {
                ensure(digits.length + scale <= 10, OutputRejection.SPAN)
                digits += "0".repeat(scale)
            }
            return digits.toIntOrNull() ?: throw Invalid(OutputRejection.SPAN)
        }
        private fun digits() { val start = at; while (at < source.length && source[at] in '0'..'9') at++; ensure(at > start) }
        private fun string(): String {
            need('"'); val value = StringBuilder()
            while (at < source.length) {
                val c = source[at++]
                if (c == '"') return value.toString().also { ensure(it.wellFormed(), OutputRejection.UNICODE) }
                ensure(c >= ' ')
                if (c != '\\') { value.append(c); continue }
                ensure(at < source.length)
                when (val escaped = source[at++]) {
                    '"', '\\', '/' -> value.append(escaped)
                    'b' -> value.append('\b'); 'f' -> value.append('\u000C'); 'n' -> value.append('\n')
                    'r' -> value.append('\r'); 't' -> value.append('\t')
                    'u' -> {
                        ensure(at + 4 <= source.length)
                        val hex = source.substring(at, at + 4)
                        ensure(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                        value.append(hex.toInt(16).toChar()); at += 4
                    }
                    else -> throw Invalid(OutputRejection.JSON)
                }
            }
            throw Invalid(OutputRejection.JSON)
        }
    }
}

private fun String.splitAt(at: Int) = at > 0 && at < length && this[at - 1].isHighSurrogate() && this[at].isLowSurrogate()
private fun String.wellFormed(): Boolean {
    var i = 0
    while (i < length) {
        val c = this[i++]
        if (c.isHighSurrogate()) { if (i == length || !this[i++].isLowSurrogate()) return false }
        else if (c.isLowSurrogate()) return false
    }
    return true
}
