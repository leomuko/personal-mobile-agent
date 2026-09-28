package dev.edgecompanion.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.vector.ImageVector
import dev.edgecompanion.ui.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = onClick, enabled = enabled) { Icon(icon, label) }
    }
}
