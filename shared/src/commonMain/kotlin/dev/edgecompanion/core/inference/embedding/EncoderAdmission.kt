package dev.edgecompanion.core.inference.embedding

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class AdmissionError { INPUT_TOO_LARGE, MALFORMED_INPUT, WORK_LIMIT, TOKEN_LIMIT, UNAVAILABLE, MISMATCH }
internal class CountingFailure(val error: AdmissionError) : Exception(error.name)

/** The initial SDK uses response-text formatting for both query and document calls. */
enum class EncoderFormat { USE_RESPONSE_TEXT_SDK_BYTES_V1 }
data class EncoderInput(val text: String, val format: EncoderFormat = EncoderFormat.USE_RESPONSE_TEXT_SDK_BYTES_V1)

class EncoderAdmissionReceipt internal constructor(
    internal val issuer: Any,
    val input: EncoderInput,
    val responseTokens: Int,
    val transportSha256: String = "",
) {
    val artifactSha256: String get() = UseAdmission.ARTIFACT_SHA256
    val tokenizerSha256: String get() = UseTokenizer.SHA256
    val configuredLimit: Int get() = UseAdmission.LIMIT
    val formattingIdentity: String get() = input.format.name
    val transportIdentity: String get() = "mediapipe-packet-bytes-v1"
    val runtimeIdentity: String get() = "com.google.mediapipe:tasks-text:1.1.1"
    // Empty query and context still each contain BOS/EOS. Return fresh lists, never backing arrays.
    val branchTokenCounts: List<Int> get() = listOf(2, 2, responseTokens)
    val formattedInputs: List<String> get() = listOf("", "", input.text)
}

sealed interface EncoderAdmission {
    class Accepted(val receipt: EncoderAdmissionReceipt) : EncoderAdmission
    data class Rejected(val error: AdmissionError, val actualTokens: Int? = null) : EncoderAdmission
}

/** Not registered with production DI until the preprocessing gate has independent evidence. */
internal class UseAdmission(private val tokenizer: UseTokenizer, private val transport: (String) -> ByteArray,
    private val sha256: (ByteArray) -> String) {
    private val issuer = Any()
    suspend fun admit(input: EncoderInput): EncoderAdmission {
        val context = currentCoroutineContext()
        var bytes: ByteArray? = null
        return try {
            val count = tokenizer.tokens(input.text, { context.ensureActive() }) { text -> transport(text).also { bytes = it } }.size
            if (count > LIMIT) EncoderAdmission.Rejected(AdmissionError.TOKEN_LIMIT, count)
            else EncoderAdmission.Accepted(EncoderAdmissionReceipt(issuer, input, count, sha256(requireNotNull(bytes))))
        } catch (failure: CountingFailure) { EncoderAdmission.Rejected(failure.error) }
        finally { bytes?.fill(0) }
    }
    fun matches(receipt: EncoderAdmissionReceipt, input: EncoderInput): Boolean =
        receipt.issuer === issuer && receipt.input == input && receipt.responseTokens in 2..LIMIT

    companion object {
        const val LIMIT = 192
        const val ARTIFACT_SHA256 = "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749"
    }
}
