package dev.edgecompanion.app.sources

import dev.edgecompanion.core.sources.model.*
import java.io.InputStream

/** Never trust provider metadata. Consume at most the allowed bytes plus a one-byte sentinel. */
internal fun readSourceTextBytes(input: InputStream): ByteArray = input.use { stream ->
    val buffer = ByteArray(SourceLimits.TEXT_BYTES + 1)
    try {
        var used = 0
        while (used < buffer.size) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val count = stream.read(buffer, used, buffer.size - used)
            if (count < 0) break
            if (count == 0) throw SourceException(SourceError.STORAGE)
            used += count
        }
        if (used > SourceLimits.TEXT_BYTES) throw SourceException(SourceError.LIMIT)
        buffer.copyOf(used)
    } finally { buffer.fill(0) }
}
