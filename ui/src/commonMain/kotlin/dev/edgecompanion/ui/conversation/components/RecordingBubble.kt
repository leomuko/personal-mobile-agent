package dev.edgecompanion.ui.conversation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.designsystem.theme.*
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

data class RecordingState(val active: Boolean = false, val seconds: Int = 0, val levels: List<Float> = List(32) { 0f })

@Composable internal fun RecordingBubble(state: RecordingState, onStop: () -> Unit, onCancel: () -> Unit) {
    Surface(color = Mint, shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row {
                Text(stringResource(Res.string.recording_inline), Modifier.weight(1f))
                Text("${(state.seconds / 60).toString().padStart(2, '0')}:${(state.seconds % 60).toString().padStart(2, '0')}")
            }
            Canvas(Modifier.fillMaxWidth().height(48.dp)) {
                state.levels.forEachIndexed { index, level ->
                    val x = size.width * (index + 0.5f) / state.levels.size
                    val height = (level.coerceIn(0f, 1f) * size.height).coerceAtLeast(3f)
                    drawLine(Forest, Offset(x, (size.height - height) / 2), Offset(x, (size.height + height) / 2), 2.5.dp.toPx(), StrokeCap.Round)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(onClick = onCancel, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White, contentColor = Forest)) {
                    Icon(Icons.Outlined.Close, stringResource(Res.string.cancel_recording))
                }
                FilledIconButton(onClick = onStop) { Icon(Icons.Filled.Stop, stringResource(Res.string.stop_recording)) }
            }
        }
    }
}
