package dev.edgecompanion.ui.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun AttachmentSourceSheet(onDismiss: () -> Unit, onSource: (AttachmentSource) -> Unit) {
    val hostDensity = LocalDensity.current
    val hostDirection = LocalLayoutDirection.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        CompositionLocalProvider(LocalDensity provides hostDensity, LocalLayoutDirection provides hostDirection) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            val density = LocalDensity.current
            val minimum = 96.dp * density.fontScale.coerceAtLeast(1f)
            val columns = ((maxWidth + 12.dp) / (minimum + 12.dp)).toInt().coerceIn(1, 3)
            // Round down in pixels so the last card cannot wrap because of accumulated rounding.
            val width = with(density) { ((maxWidth.roundToPx() - 12.dp.roundToPx() * (columns - 1)) / columns).toDp() }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = columns) {
                listOf(AttachmentSource.GALLERY, AttachmentSource.CAMERA, AttachmentSource.FILES).forEach { source ->
                    val label = when (source) {
                        AttachmentSource.GALLERY -> Res.string.photos
                        AttachmentSource.CAMERA -> Res.string.camera
                        AttachmentSource.FILES -> Res.string.files
                    }
                    Surface(onClick = { onSource(source) }, shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.width(width)) {
                        Column(Modifier.heightIn(min = 112.dp).padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            Icon(when (source) {
                                AttachmentSource.GALLERY -> Icons.Outlined.PhotoLibrary
                                AttachmentSource.CAMERA -> Icons.Outlined.PhotoCamera
                                AttachmentSource.FILES -> Icons.AutoMirrored.Outlined.InsertDriveFile
                            }, null, Modifier.size(28.dp))
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
        }
    }
}
