package dev.edgecompanion.app.attachments

import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withStarted
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.edgecompanion.app.CaptureActivity
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.ui.conversation.components.AttachmentSource
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.UUID

/** The host owns result registration; the application session owns the imported draft. */
internal class AttachmentLaunchers(private val host: ComponentActivity, private val session: ConversationSession,
    private val onCameraDenied: () -> Unit) {
    private var epoch = 0L
    private val resolver = host.applicationContext.contentResolver
    private val files = host.registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { accept(it) }
    private val gallery = host.registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(AttachmentLimits.COUNT)) { accept(it) }
    private val camera = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    private val cameraPermission = CameraPermissionRequest(host.activityResultRegistry,
        hasPermission = { host.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED },
        onGranted = ::openCamera, onDenied = onCameraDenied)

    init {
        host.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { cameraPermission.cancel() }
        })
    }

    fun restore(state: Bundle?) {
        epoch = state?.getLong("attachmentEpoch") ?: session.state.value.draftEpoch
        cameraPermission.restore(state)
    }
    fun save(state: Bundle) {
        state.putLong("attachmentEpoch", epoch)
        cameraPermission.save(state)
    }
    fun launch(source: AttachmentSource) {
        cameraPermission.cancel()
        epoch = session.state.value.draftEpoch
        when (source) {
            AttachmentSource.FILES -> files.launch(arrayOf("*/*"))
            AttachmentSource.GALLERY -> gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            AttachmentSource.CAMERA -> cameraPermission.launch(epoch)
        }
    }

    private fun openCamera(targetEpoch: Long) {
        host.lifecycleScope.launch {
            host.lifecycle.withStarted {
                camera.launch(Intent(host, CaptureActivity::class.java).putExtra("draftEpoch", targetEpoch))
            }
        }
    }

    private fun accept(uris: List<Uri>) {
        val targetEpoch = epoch
        if (uris.distinct().size > AttachmentLimits.COUNT) { session.attachmentLimitReached(); return }
        // Providers may ignore picker limits. The controller also enforces the combined draft limit.
        uris.distinct().take(AttachmentLimits.COUNT).forEach { uri ->
            host.lifecycleScope.launch {
                val item = withContext(Dispatchers.IO) {
                    val name = runCatching {
                        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                            if (it.moveToFirst()) it.getString(0) else null
                        }
                    }.getOrNull() ?: "attachment"
                    Attachment(UUID.randomUUID().toString(), name, runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream")
                }
                session.importAttachment(item, targetEpoch) {
                    withContext(Dispatchers.IO) {
                        resolver.openInputStream(uri)?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                require(output.size().toLong() + count <= AttachmentLimits.ITEM_BYTES)
                                output.write(buffer, 0, count)
                            }
                            output.toByteArray()
                        } ?: error("Selected file unavailable")
                    }
                }
            }
        }
    }
}
