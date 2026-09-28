package dev.edgecompanion.core.attachments.data

/** Implementations protect bytes at rest. Call only from a worker dispatcher. */
interface AttachmentFiles {
    fun write(id: String, bytes: ByteArray)
    fun read(id: String): ByteArray
    fun remove(id: String)
    fun contains(id: String): Boolean
    fun retain(ids: Set<String>)
}
