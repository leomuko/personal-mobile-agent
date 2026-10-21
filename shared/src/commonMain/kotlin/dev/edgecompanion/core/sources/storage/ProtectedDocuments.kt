package dev.edgecompanion.core.sources.storage

data class DocumentBinding(val sourceId: String, val revisionId: String, val keyVersion: Int = 1) {
    init {
        require(sourceId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(revisionId.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        require(keyVersion == 1)
    }
}

object DocumentLimits {
    const val FILE_BYTES = 20L * 1024 * 1024
    const val BUFFER_BYTES = 64 * 1024
    const val OPEN_READERS = 2
}

enum class DocumentError { LIMIT, INTEGRITY, MISSING, STORAGE, BUSY, KEY_UNAVAILABLE, UNSUPPORTED }
class DocumentException(val error: DocumentError, cause: Throwable? = null) : Exception(error.name, cause)
data class ProtectedDocument(val binding: DocumentBinding, val plaintextBytes: Long, val storedBytes: Long, val sha256: String)

/** Ownership transfers to write(); it closes the input even if cancelled before admission. */
interface DocumentInput {
    val declaredBytes: Long?
    fun read(buffer: ByteArray): Int
    fun close()
}

/** Fully authenticated before publication; cached seek segments are authenticated before exposure. */
interface VerifiedDocumentReader {
    val length: Long
    val position: Long
    fun seek(position: Long)
    fun read(buffer: ByteArray, offset: Int = 0, count: Int = buffer.size - offset): Int
    fun close()
}

interface ProtectedDocumentFiles {
    suspend fun write(binding: DocumentBinding, input: DocumentInput): ProtectedDocument
    suspend fun openVerified(binding: DocumentBinding, expectedBytes: Long): VerifiedDocumentReader
    suspend fun remove(binding: DocumentBinding)
}
