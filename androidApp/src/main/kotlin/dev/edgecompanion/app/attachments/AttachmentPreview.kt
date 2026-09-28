package dev.edgecompanion.app.attachments

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.ui.conversation.components.AttachmentThumbnail
import dev.edgecompanion.ui.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun LocalThumbnail(item: Attachment, files: AttachmentFiles, modifier: Modifier,
    contentScale: ContentScale = ContentScale.Crop) {
    val preview by produceState<ImageBitmap?>(null, item.id, item.status) {
        if (item.status == AttachmentStatus.READY && item.mimeType.startsWith("image/")) {
            value = withContext(Dispatchers.IO) {
                try {
                    val bytes = files.read(item.id)
                    try {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        require(bounds.outWidth in 1..40000 && bounds.outHeight in 1..40000)
                        var sample = 1
                        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    } finally { bytes.fill(0) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            }
        }
    }
    if (preview != null) Image(preview!!, item.name, modifier, contentScale = contentScale)
    else AttachmentThumbnail(item, modifier)
}

@Composable internal fun AttachmentPreview(item: Attachment, files: AttachmentFiles, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text(item.name) }, text = {
        Column {
            LocalThumbnail(item, files, Modifier.fillMaxWidth().height(220.dp), ContentScale.Fit)
            Text("${item.size / 1024} KiB", style = MaterialTheme.typography.labelMedium)
            Text(stringResource(Res.string.attachment_local))
            if (item.mimeType == "audio/wav") AudioPreview(item.id, files)
        }
    }, confirmButton = { TextButton(onClick = onClose) { Text(stringResource(Res.string.close)) } })
}
