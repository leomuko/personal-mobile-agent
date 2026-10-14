package dev.edgecompanion.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.edgecompanion.ui.resources.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ToolButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true,
    modifier: Modifier = Modifier) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState(), modifier = modifier) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(AssistantTopBarDefaults.ControlSize)) { Icon(icon, label) }
    }
}
