package dev.edgecompanion.core.inference.assets

import android.system.Os
import android.system.OsConstants
import dev.edgecompanion.core.inference.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PrivateModelAssets(
    private val root: File,
    private val manifest: ModelManifest = ModelCatalog.gemma4,
    private val freeBytes: () -> Long = { root.usableSpace },
) : ModelAssets {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(ModelAssetState())
    override val state = mutable.asStateFlow()
    private val destination get() = File(File(root, manifest.sha256), manifest.fileName)

    override suspend fun verify(): String? = lock.withLock {
        withContext(Dispatchers.IO) {
            root.mkdirs()
            // Single app-scoped installer owns this directory; no in-progress import holds the lock.
            root.listFiles()?.filter { it.name.startsWith("staging-") }?.forEach { it.delete() }
            if (!destination.exists()) {
                mutable.value = ModelAssetState()
                return@withContext null
            }
            mutable.value = ModelAssetState(AssetStatus.VERIFYING)
            try {
                verifyFile(destination)
                mutable.value = ModelAssetState(AssetStatus.INSTALLED, manifest.bytes)
                destination.absolutePath
            } catch (cancelled: CancellationException) {
                mutable.value = ModelAssetState(AssetStatus.REPAIR_REQUIRED)
                throw cancelled
            } catch (failure: Exception) {
                val error = (failure as? ModelException)?.error ?: ModelError.IMPORT_FAILED
                mutable.value = ModelAssetState(AssetStatus.REPAIR_REQUIRED, error = error)
                throw ModelException(error)
            }
        }
    }

    override suspend fun install(source: ModelSource) = lock.withLock {
        withContext(Dispatchers.IO) {
            root.mkdirs()
            val staged = File(root, "staging-${UUID.randomUUID()}")
            val previous = state.value
            try {
                val reader = source.open()
                try {
                    if (reader.size != null && reader.size != manifest.bytes) throw ModelException(ModelError.SIZE_MISMATCH)
                    if (freeBytes() < manifest.requiredFreeBytes) throw ModelException(ModelError.STORAGE_FULL)
                    mutable.value = ModelAssetState(AssetStatus.IMPORTING)
                    val digest = MessageDigest.getInstance("SHA-256")
                    var copied = 0L
                    FileOutputStream(staged).use { output ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = reader.read(buffer)
                            if (count < 0) break
                            if (count == 0) throw IOException("No import progress")
                            if (count > buffer.size || copied > manifest.bytes - count) throw ModelException(ModelError.SIZE_MISMATCH)
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            copied += count
                            mutable.value = ModelAssetState(AssetStatus.IMPORTING, copied)
                        }
                        if (copied != manifest.bytes) throw ModelException(ModelError.SIZE_MISMATCH)
                        mutable.value = ModelAssetState(AssetStatus.VERIFYING, copied)
                        if (digest.digest().hex() != manifest.sha256) throw ModelException(ModelError.HASH_MISMATCH)
                        output.fd.sync()
                    }
                } finally { reader.close() }
                currentCoroutineContext().ensureActive()
                destination.parentFile!!.mkdirs()
                if (destination.exists()) {
                    // Never replace verified bytes, even on a repeated import.
                    try { verifyFile(destination) }
                    catch (failure: ModelException) {
                        if (failure.error !in setOf(ModelError.HASH_MISMATCH, ModelError.SIZE_MISMATCH)) throw failure
                        // Caller only admits repair when the runtime has been released.
                        if (!destination.delete()) throw IOException("Cannot remove corrupt artifact")
                    }
                }
                if (!destination.exists() && !staged.renameTo(destination)) throw IOException("Cannot promote artifact")
                syncDirectory(destination.parentFile!!)
                syncDirectory(root)
                // A single pinned artifact needs no separate mutable installed pointer: reverify this immutable path at startup.
                mutable.value = ModelAssetState(AssetStatus.INSTALLED, manifest.bytes)
            } catch (cancelled: CancellationException) {
                mutable.value = previous
                throw cancelled
            } catch (failure: Exception) {
                val error = (failure as? ModelException)?.error
                    ?: if (freeBytes() < ModelManifest.STORAGE_RESERVE_BYTES) ModelError.STORAGE_FULL else ModelError.IMPORT_FAILED
                mutable.value = previous.copy(error = error, status = if (previous.status == AssetStatus.INSTALLED)
                    AssetStatus.INSTALLED else AssetStatus.FAILED)
                throw ModelException(error)
            } finally { staged.delete() }
        }
    }

    private suspend fun verifyFile(file: File) {
        if (file.length() != manifest.bytes) throw ModelException(ModelError.SIZE_MISMATCH)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        if (digest.digest().hex() != manifest.sha256) throw ModelException(ModelError.HASH_MISMATCH)
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun syncDirectory(directory: File) {
        val descriptor = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(descriptor) } finally { Os.close(descriptor) }
    }
}
