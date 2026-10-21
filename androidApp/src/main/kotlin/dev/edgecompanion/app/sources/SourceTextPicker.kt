package dev.edgecompanion.app.sources

import android.net.Uri
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.edgecompanion.core.sources.model.*
import dev.edgecompanion.ui.sources.viewmodel.SourcesViewModel
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

/** Explicit library intake, independent of unsent composer attachments. No persisted URI grant. */
internal class SourceTextPicker(private val activity: ComponentActivity, private val model: SourcesViewModel) {
    private var importing: Job? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import(uri)
    }
    fun launch() { launcher.launch(arrayOf("text/plain")) }
    private fun import(uri: Uri) {
        if (importing?.isActive == true) { model.importFailed(SourceError.BUSY); return }
        importing = activity.lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
            val pending = AtomicReference<ByteArray?>()
            try {
                model.importUtf8(activity.getString(dev.edgecompanion.app.R.string.imported_text_source)) {
                    withTimeout(10_000) { read(uri, pending) }
                }
            } catch (_: TimeoutCancellationException) { model.importFailed() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { model.importFailed((failure as? SourceException)?.error ?: SourceError.STORAGE) }
            finally { pending.getAndSet(null)?.fill(0) }
        }
    }
    private suspend fun read(uri: Uri, pending: AtomicReference<ByteArray?>): ByteArray = coroutineScope {
        val signal = CancellationSignal()
        val handle = AtomicReference<android.content.res.AssetFileDescriptor?>()
        val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() }
            finally { try { signal.cancel() } finally { runCatching { handle.getAndSet(null)?.close() } } }
        }
        try {
            runInterruptible(Dispatchers.IO) {
                val descriptor = activity.contentResolver.openAssetFileDescriptor(uri, "r", signal)
                    ?: throw SourceException(SourceError.STORAGE)
                handle.set(descriptor)
                if (signal.isCanceled) {
                    handle.getAndSet(null)?.close()
                    throw CancellationException()
                }
                descriptor.use { readSourceTextBytes(it.createInputStream()).also(pending::set) }
            }
        } finally { withContext(NonCancellable) { closer.cancelAndJoin() } }
    }
}
