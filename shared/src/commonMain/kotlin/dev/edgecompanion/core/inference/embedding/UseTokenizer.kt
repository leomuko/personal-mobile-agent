package dev.edgecompanion.core.inference.embedding

// Optimized normalization/lattice behavior ported from MediaPipe (Apache-2.0).
// Exact upstream files and hashes: scripts/encoder-reference/upstream.json.
/** Only the hash-pinned USE optimized configuration is supported, not arbitrary FlatBuffers. */
internal class UseTokenizer private constructor(private val bytes: ByteArray) {
    private val root = word(0)
    private fun word(at: Int): Int {
        require(at >= 0 && at <= bytes.size - 4)
        return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)
    }
    private fun short(at: Int): Int {
        require(at >= 0 && at <= bytes.size - 2)
        return (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    }
    private fun field(table: Int, index: Int): Int {
        val vtable = table - word(table)
        val offset = if (4 + index * 2 < short(vtable)) short(vtable + 4 + index * 2) else 0
        return if (offset == 0) 0 else table + offset
    }
    private fun scalar(index: Int, default: Int = 0) = field(root, index).let { if (it == 0) default else word(it) }
    private fun flag(index: Int) = field(root, index).let { it != 0 && bytes[it].toInt() != 0 }
    private fun vector(table: Int, index: Int): Int {
        val pointer = field(table, index)
        require(pointer != 0)
        val start = pointer + word(pointer)
        require(word(start) >= 0)
        return start
    }
    private fun trie(index: Int): IntArray {
        val pointer = field(root, index)
        val start = vector(pointer + word(pointer), 0)
        return IntArray(word(start)) { word(start + 4 + it * 4) }
    }
    private val pieces = trie(6)
    private val normalizer = trie(11)
    private val scores = vector(root, 7).let { start -> FloatArray(word(start)) { Float.fromBits(word(start + 4 + it * 4)) } }
    private val replacements = vector(root, 12).let { bytes.copyOfRange(it + 4, it + 4 + word(it)) }
    private val unknown = scalar(3, -1)
    private val penalty = Float.fromBits(scalar(4))
    private fun offset(node: Int) = (node ushr 10) shl ((node and 0x200) ushr 6)

    private inline fun matches(trie: IntArray, input: ByteArray, start: Int, tick: () -> Unit, visit: (Int, Int) -> Unit) {
        var position = offset(trie[0])
        for (i in start until input.size) {
            tick()
            position = position xor (input[i].toInt() and 255)
            if (position !in trie.indices || (trie[position] and 0x800000ff.toInt()) != (input[i].toInt() and 255)) return
            val leaf = trie[position] and 0x100 != 0
            position = position xor offset(trie[position])
            if (position !in trie.indices) return
            if (leaf) visit(trie[position] and 0x7fffffff, i - start + 1)
        }
    }

    fun tokens(text: String, check: () -> Unit, transport: (String) -> ByteArray): List<Int> {
        check()
        var work = 0
        val tick = {
            if (++work > MAX_MATCHES) throw CountingFailure(AdmissionError.WORK_LIMIT)
            if (work % 64 == 0) check()
        }
        if (text.length > MAX_INPUT_BYTES) throw CountingFailure(AdmissionError.INPUT_TOO_LARGE)
        var i = 0
        while (i < text.length) {
            val c = text[i++]
            if (c.isHighSurrogate()) {
                if (i == text.length || !text[i++].isLowSurrogate()) throw CountingFailure(AdmissionError.MALFORMED_INPUT)
            } else if (c.isLowSurrogate()) throw CountingFailure(AdmissionError.MALFORMED_INPUT)
        }
        val raw = transport(text)
        if (raw.size > MAX_INPUT_BYTES) throw CountingFailure(AdmissionError.INPUT_TOO_LARGE)
        // This sequence deliberately follows MediaPipe's optimized normalizer, not NFKC or the
        // standard SentencePiece processor. Unknown spans and float tie-breaking also differ.
        val prefixed = if (raw.isNotEmpty() && flag(9)) byteArrayOf(32) + raw else raw
        val normalized = ArrayList<Byte>()
        i = 0
        while (i < prefixed.size) {
            if (i % 64 == 0) check()
            var replacement = -1
            var consumed = 0
            matches(normalizer, prefixed, i, tick) { id, length -> replacement = id; consumed = length }
            if (consumed == 0) { normalized.add(prefixed[i]); i++ }
            else {
                var at = replacement
                while (at < replacements.size && replacements[at].toInt() != 0) {
                    normalized.add(replacements[at++])
                    if (normalized.size > MAX_NORMALIZED_BYTES) throw CountingFailure(AdmissionError.WORK_LIMIT)
                }
                require(at < replacements.size)
                i += consumed
            }
            if (normalized.size > MAX_NORMALIZED_BYTES) throw CountingFailure(AdmissionError.WORK_LIMIT)
        }
        val compact = ArrayList<Byte>()
        i = 0
        while (i < normalized.size) {
            val c = normalized[i++]
            if (flag(8) && whitespace(c) && i < normalized.size && whitespace(normalized[i])) {
                while (i < normalized.size && whitespace(normalized[i])) i++
                compact.add(32)
            } else compact.add(c)
        }
        if (flag(8) && compact.lastOrNull()?.let(::whitespace) == true) compact.removeAt(compact.lastIndex)
        val escaped = ArrayList<Byte>()
        compact.forEach { c -> if (flag(10) && whitespace(c)) escaped.addAll(listOf(0xe2.toByte(), 0x96.toByte(), 0x81.toByte())) else escaped.add(c) }
        if (escaped.size > MAX_NORMALIZED_BYTES) throw CountingFailure(AdmissionError.WORK_LIMIT)
        val input = escaped.toByteArray()
        val best = FloatArray(input.size + 1)
        val codes = IntArray(input.size + 1) { -1 }
        val previous = IntArray(input.size + 1) { -1 }
        for (at in input.indices) {
            if (at % 64 == 0) check()
            if (at > 0 && previous[at] < 0) continue
            if (unknown >= 0) {
                val score = best[at] + penalty
                if (previous[at + 1] < 0 || best[at + 1] < score) {
                    best[at + 1] = score; codes[at + 1] = unknown
                    previous[at + 1] = if (codes[at] == unknown) previous[at] else at
                }
            }
            matches(pieces, input, at, tick) { id, length ->
                val target = at + length
                val score = best[at] + scores[id]
                if (previous[target] < 0 || best[target] < score) {
                    best[target] = score; codes[target] = id; previous[target] = at
                }
            }
        }
        val result = ArrayList<Int>()
        // All three pinned tokenizer ops set add_bos=true, add_eos=true, reverse=false.
        result.add(scalar(2))
        var at = input.size
        while (at > 0 && previous[at] >= 0) {
            result.add(if (codes[at] == unknown) unknown else codes[at] + scalar(5))
            at = previous[at]
        }
        result.add(scalar(1))
        result.reverse()
        check()
        return result
    }

    companion object {
        const val SHA256 = "ab32418a9a7f2c0c95e3db6233f2fbd8243e2929a84cd5feec9c1b1ca615c420"
        const val MAX_INPUT_BYTES = 16_384
        const val MAX_NORMALIZED_BYTES = 65_536
        const val MAX_MATCHES = 1_000_000
        fun open(config: ByteArray, sha256: (ByteArray) -> String): UseTokenizer {
            require(config.size == 384_344)
            val snapshot = config.copyOf()
            require(sha256(snapshot) == SHA256)
            return UseTokenizer(snapshot)
        }
        private fun whitespace(c: Byte) = when (c.toInt()) { 32, 9, 13, 10 -> true; else -> false }
    }
}
