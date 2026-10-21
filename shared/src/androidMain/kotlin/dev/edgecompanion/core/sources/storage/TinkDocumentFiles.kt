package dev.edgecompanion.core.sources.storage

import com.google.crypto.tink.StreamingAead
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.NonWritableChannelException
import java.nio.channels.SeekableByteChannel
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Container v1: fixed format/key/scheme metadata is AAD, never a custom cipher construction. */
class TinkDocumentFiles(private val root: File, private val aead: StreamingAead,
    private val io: CoroutineDispatcher = Dispatchers.IO) : ProtectedDocumentFiles {
    private val writes = Mutex()
    private val readers = AtomicInteger()

    private fun destination(binding: DocumentBinding) = File(root, "${binding.sourceId.length}_${binding.sourceId}_${binding.revisionId}.enc")
    private fun header(binding: DocumentBinding) = ByteBuffer.allocate(16)
        .putInt(0x45444745).putInt(1).putInt(binding.keyVersion).putInt(1).array()
    private fun aad(binding: DocumentBinding) = header(binding) +
        "${binding.sourceId}\u0000${binding.revisionId}".toByteArray(Charsets.UTF_8)

    override suspend fun write(binding: DocumentBinding, input: DocumentInput): ProtectedDocument {
        var primaryFailure: Throwable? = null
        try { return writeOwned(binding, input) }
        catch (failure: Throwable) { primaryFailure = failure; throw failure }
        finally {
            try { withContext(io + NonCancellable) { input.close() } }
            catch (failure: Exception) {
                if (primaryFailure != null) primaryFailure.addSuppressed(failure)
                else throw DocumentException(DocumentError.STORAGE, failure)
            }
        }
    }

    private suspend fun writeOwned(binding: DocumentBinding, input: DocumentInput): ProtectedDocument = writes.withLock {
        withContext(io) {
            val buffer = ByteArray(DocumentLimits.BUFFER_BYTES)
            val staged = File(root, "staging-${UUID.randomUUID()}")
            try {
                if (input.declaredBytes?.let { it < 1 || it > DocumentLimits.FILE_BYTES } == true)
                    throw DocumentException(DocumentError.LIMIT)
                check(root.isDirectory || root.mkdirs())
                val target = destination(binding)
                if (target.exists()) throw DocumentException(DocumentError.BUSY)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                FileOutputStream(staged).use { file ->
                    file.write(header(binding))
                    // Closing the cipher stream authenticates its final segment; retain the FD for fsync.
                    val output = object : java.io.FilterOutputStream(file) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = file.write(bytes, offset, length)
                        override fun close() { flush() }
                    }
                    aead.newEncryptingStream(output, aad(binding)).use { encrypted ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0 || count > buffer.size) throw DocumentException(DocumentError.STORAGE)
                            if (copied > DocumentLimits.FILE_BYTES - count) throw DocumentException(DocumentError.LIMIT)
                            encrypted.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                        }
                        if (copied == 0L) throw DocumentException(DocumentError.UNSUPPORTED)
                    }
                    file.fd.sync()
                }
                currentCoroutineContext().ensureActive()
                DocumentPublication.locked(root) {
                    if (target.exists()) throw DocumentException(DocumentError.BUSY)
                    if (!staged.renameTo(target)) throw DocumentException(DocumentError.STORAGE)
                }
                ProtectedDocument(binding, copied, target.length(), digest.digest().joinToString("") { "%02x".format(it) })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: DocumentException) { throw failure }
            catch (failure: Exception) { throw DocumentException(DocumentError.STORAGE, failure) }
            finally {
                buffer.fill(0)
                staged.delete()
            }
        }
    }

    override suspend fun openVerified(binding: DocumentBinding, expectedBytes: Long): VerifiedDocumentReader {
        var published: VerifiedDocumentReader? = null
        try {
            return withContext(io) { verify(binding, expectedBytes).also { published = it } }
        } catch (failure: Throwable) {
            // withContext may discard its result when cancellation wins the dispatcher handoff.
            try { published?.close() } catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
            throw failure
        }
    }

    private suspend fun verify(binding: DocumentBinding, expectedBytes: Long): VerifiedDocumentReader {
        if (expectedBytes !in 1..DocumentLimits.FILE_BYTES) throw DocumentException(DocumentError.LIMIT)
        if (readers.incrementAndGet() > DocumentLimits.OPEN_READERS) {
            readers.decrementAndGet()
            throw DocumentException(DocumentError.BUSY)
        }
        var channel: SeekableByteChannel? = null
        var source: FileInputStream? = null
        var transferred = false
        val buffer = ByteBuffer.allocate(DocumentLimits.BUFFER_BYTES)
        try {
            val file = destination(binding)
            if (!file.exists()) throw DocumentException(DocumentError.MISSING)
            if (file.length() > DocumentLimits.FILE_BYTES + 4096) throw DocumentException(DocumentError.LIMIT)
            source = FileInputStream(file)
            val metadata = ByteArray(16)
            java.io.DataInputStream(source).readFully(metadata)
            if (!metadata.contentEquals(header(binding)))
                throw DocumentException(DocumentError.INTEGRITY)
            channel = aead.newSeekableDecryptingChannel(OffsetChannel(source.channel, 16), aad(binding))
            var verified = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) throw DocumentException(DocumentError.INTEGRITY)
                verified += count
                if (verified > expectedBytes) throw DocumentException(DocumentError.INTEGRITY)
                buffer.array().fill(0)
            }
            if (verified != expectedBytes || channel.size() != expectedBytes) throw DocumentException(DocumentError.INTEGRITY)
            channel.position(0)
            val owned = checkNotNull(channel)
            val reader = Reader(owned, expectedBytes) { readers.decrementAndGet() }
            transferred = true
            channel = null
            source = null
            return reader
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: DocumentException) { throw failure }
        catch (failure: Exception) { throw DocumentException(DocumentError.INTEGRITY, failure) }
        finally {
            buffer.array().fill(0)
            if (!transferred) {
                try { channel?.close() } finally {
                    try { source?.close() } finally { readers.decrementAndGet() }
                }
            }
        }
    }

    override suspend fun remove(binding: DocumentBinding) = writes.withLock {
        withContext(io) {
            DocumentPublication.locked(root) {
                val file = destination(binding)
                if (file.exists() && !file.delete()) throw DocumentException(DocumentError.STORAGE)
            }
            Unit
        }
    }

    private class Reader(private val channel: SeekableByteChannel, override val length: Long,
        private val released: () -> Unit) : VerifiedDocumentReader {
        private var closed = false
        override val position: Long @Synchronized get() = channel.position()
        @Synchronized override fun seek(position: Long) {
            check(!closed)
            require(position in 0..length)
            channel.position(position)
        }
        @Synchronized override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            check(!closed)
            require(count in 0..DocumentLimits.BUFFER_BYTES && offset >= 0 && offset <= buffer.size - count)
            return try { channel.read(ByteBuffer.wrap(buffer, offset, count)) }
            catch (failure: Exception) {
                buffer.fill(0, offset, offset + count)
                throw DocumentException(DocumentError.INTEGRITY, failure)
            }
        }
        @Synchronized override fun close() {
            if (closed) return
            closed = true
            try { channel.close() } finally { released() }
        }
    }

    private class OffsetChannel(private val source: SeekableByteChannel, private val start: Long) : SeekableByteChannel {
        init { source.position(start) }
        override fun read(destination: ByteBuffer): Int = source.read(destination)
        override fun position(): Long = source.position() - start
        override fun position(position: Long): SeekableByteChannel {
            require(position >= 0)
            source.position(Math.addExact(start, position))
            return this
        }
        override fun size(): Long = source.size() - start
        override fun isOpen(): Boolean = source.isOpen
        override fun close() = source.close()
        override fun write(source: ByteBuffer): Int = throw NonWritableChannelException()
        override fun truncate(size: Long): SeekableByteChannel = throw NonWritableChannelException()
    }
}
