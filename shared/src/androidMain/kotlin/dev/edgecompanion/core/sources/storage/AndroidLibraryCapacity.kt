package dev.edgecompanion.core.sources.storage

import android.content.Context
import android.os.StatFs
import dev.edgecompanion.core.sources.model.LibraryCapacity
import dev.edgecompanion.core.sources.model.LibraryFootprint
import java.io.File

/** Count the whole personal DB and sidecars, not an imaginary per-feature page estimate. */
class AndroidLibraryCapacity(private val context: Context) : LibraryCapacity {
    override fun measure(): LibraryFootprint {
        val db = context.getDatabasePath("companion.db")
        val files = listOf(db, File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))
        val protected = File(context.noBackupFilesDir, "sources-v1")
        return LibraryFootprint(files.sumOf { size(it) } + size(protected),
            StatFs(context.noBackupFilesDir.path).availableBytes)
    }
    private fun size(file: File): Long {
        if (!file.exists()) return 0
        if (!file.isDirectory) return file.length()
        return (file.listFiles() ?: error("Library footprint unavailable")).sumOf(::size)
    }
}
