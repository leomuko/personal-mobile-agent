package dev.edgecompanion.core.sources.storage

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Read-only seekable FD backed by authenticated segments, never by a plaintext temporary file. */
class ProtectedDocumentDescriptor private constructor(
    val descriptor: ParcelFileDescriptor,
    private val release: () -> Unit,
) : AutoCloseable {
    override fun close() {
        try { descriptor.close() } finally { release() }
    }

    companion object {
        suspend fun open(context: Context, files: ProtectedDocumentFiles, binding: DocumentBinding,
            bytes: Long): ProtectedDocumentDescriptor {
            val reader = files.openVerified(binding, bytes)
            val thread = HandlerThread("authenticated-document")
            val released = AtomicBoolean()
            val release = {
                if (released.compareAndSet(false, true)) {
                    try { reader.close() } finally { thread.quitSafely() }
                }
                Unit
            }
            var published: ProtectedDocumentDescriptor? = null
            try {
                return withContext(Dispatchers.IO) {
                    thread.start()
                    val callback = object : ProxyFileDescriptorCallback() {
                        override fun onGetSize(): Long = reader.length
                        override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                            if (released.get()) throw ErrnoException("read", OsConstants.EBADF)
                            if (offset < 0 || size !in 0..minOf(data.size, 128 * 1024))
                                throw ErrnoException("read", OsConstants.EINVAL)
                            if (offset >= reader.length) return 0
                            try {
                                reader.seek(offset)
                                val requested = minOf(size.toLong(), reader.length - offset).toInt()
                                var copied = 0
                                while (copied < requested) {
                                    val count = reader.read(data, copied, minOf(DocumentLimits.BUFFER_BYTES, requested - copied))
                                    if (count <= 0) throw DocumentException(DocumentError.INTEGRITY)
                                    copied += count
                                }
                                return copied
                            } catch (failure: Exception) {
                                data.fill(0, 0, size)
                                throw ErrnoException("authenticated-read", OsConstants.EIO, failure)
                            }
                        }
                        override fun onRelease() = release()
                    }
                    val fd = context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_READ_ONLY, callback, Handler(thread.looper))
                    ProtectedDocumentDescriptor(fd, release).also { published = it }
                }
            } catch (failure: Throwable) {
                try { published?.close() ?: release() }
                catch (closeFailure: Exception) { failure.addSuppressed(closeFailure) }
                throw failure
            }
        }
    }
}
