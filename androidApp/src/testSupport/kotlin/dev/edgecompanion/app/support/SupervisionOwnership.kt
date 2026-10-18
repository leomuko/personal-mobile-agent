package dev.edgecompanion.app.support

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/** Test-only OS lock. Failed/uncertain cleanup must retain it until process death. */
class SupervisionOwnership private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() = synchronized(gate) {
        if (active !== this) return@synchronized
        try { lock.release() } finally { channel.close() }
        active = null
    }

    companion object {
        private val gate = Any()
        private var active: SupervisionOwnership? = null

        fun acquire(root: File): SupervisionOwnership = synchronized(gate) {
            // Do not open/close a second descriptor: some OSes release process locks on close.
            check(active == null) { "A QA supervision owner is still active in this process" }
            check(root.isDirectory || root.mkdirs())
            val channel = RandomAccessFile(File(root, "owner.lock"), "rw").channel
            try {
                val lock = checkNotNull(channel.tryLock()) { "A QA supervision owner is still active" }
                SupervisionOwnership(channel, lock).also { active = it }
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }
    }
}
