package dev.edgecompanion.ui.conversation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable fun AttachmentThumbnail(item: Attachment, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Icon(when {
            item.mimeType.startsWith("audio/") -> Icons.Outlined.GraphicEq
            item.mimeType.startsWith("image/") -> Icons.Outlined.Image
            else -> Icons.AutoMirrored.Outlined.InsertDriveFile
        }, null, Modifier.size(32.dp))
    }
}

@Composable internal fun AttachmentStrip(items: List<Attachment>, onRemove: ((String) -> Unit)? = null,
    onPreview: (Attachment) -> Unit, thumbnail: @Composable (Attachment, Modifier) -> Unit) {
    if (items.isEmpty()) return
    val tileHeight = 104.dp * LocalDensity.current.fontScale.coerceIn(1f, 2f)
    LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(8.dp)) {
        items(items, key = { it.id }) { item ->
            val isImage = item.mimeType.startsWith("image/")
            val status = when (item.status) {
                AttachmentStatus.IMPORTING -> stringResource(Res.string.attachment_importing)
                AttachmentStatus.FAILED -> stringResource(Res.string.attachment_failed)
                AttachmentStatus.READY -> ""
            }
            val previewLabel = stringResource(Res.string.preview_attachment)
            Box(Modifier.size(if (isImage) tileHeight else 160.dp, tileHeight)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable(enabled = item.status == AttachmentStatus.READY, onClickLabel = previewLabel) { onPreview(item) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = item.name
                        if (status.isNotEmpty()) stateDescription = status
                    }) {
                    if (isImage) Box(Modifier.fillMaxSize().clearAndSetSemantics {}) { thumbnail(item, Modifier.fillMaxSize()) }
                    else Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Icon(if (item.mimeType.startsWith("audio/")) Icons.Outlined.GraphicEq else Icons.AutoMirrored.Outlined.InsertDriveFile,
                            null, Modifier.size(24.dp))
                        Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                        Text(item.name.substringAfterLast('.', "").take(10).uppercase(),
                            maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (item.status == AttachmentStatus.IMPORTING) LinearProgressIndicator(Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                    if (item.status == AttachmentStatus.FAILED) Icon(Icons.Outlined.Error, status,
                        Modifier.align(Alignment.BottomStart).background(MaterialTheme.colorScheme.errorContainer).padding(4.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer)
                }
                if (onRemove != null) IconButton(onClick = { onRemove(item.id) },
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(Res.string.remove_named_attachment, item.name),
                        Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surface).padding(4.dp).size(16.dp))
                }
            }
        }
    }
}
