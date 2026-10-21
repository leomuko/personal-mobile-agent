package dev.edgecompanion.core.sources.storage

import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Protect the check/rename across adapter instances and cooperating app processes. */
internal object DocumentPublication {
    private class Entry(val mutex: Mutex = Mutex(), var references: Int = 0)
    private val gate = Any()
    private val active = mutableMapOf<String, Entry>()

    suspend fun <T> locked(root: File, action: () -> T): T {
        val path = root.canonicalPath
        val entry = synchronized(gate) { active.getOrPut(path) { Entry() }.also { it.references++ } }
        val owner = currentCoroutineContext()
        try {
            return entry.mutex.withLock {
                runInterruptible {
                    check(root.isDirectory || root.mkdirs())
                    RandomAccessFile(File(root, ".publication.lock"), "rw").use { file ->
                        file.channel.lock().use {
                            owner.ensureActive()
                            action()
                        }
                    }
                }
            }
        } finally {
            synchronized(gate) { if (--entry.references == 0) active.remove(path) }
        }
    }
}
