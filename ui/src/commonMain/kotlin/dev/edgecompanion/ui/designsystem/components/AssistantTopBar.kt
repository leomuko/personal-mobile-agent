package dev.edgecompanion.ui.designsystem.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.resources.Res
import dev.edgecompanion.ui.resources.on_device
import org.jetbrains.compose.resources.stringResource

object AssistantTopBarDefaults {
    val MinHeight = 80.dp
    val MascotSize = 40.dp
    val ControlSize = 48.dp
}

@Composable fun AssistantTopBar(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier.fillMaxWidth().testTag("assistant-top-bar"), color = colors.surface, contentColor = colors.onSurface) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = AssistantTopBarDefaults.MinHeight)
                .testTag("assistant-top-bar-body").padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                    if (leading != null) Box(Modifier.size(AssistantTopBarDefaults.ControlSize),
                        contentAlignment = Alignment.Center) { leading() }
                    Mascot(Modifier.size(AssistantTopBarDefaults.MascotSize).testTag("assistant-top-bar-mascot"))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(title, Modifier.testTag("assistant-top-bar-title"), color = colors.onSurface,
                            style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.padding(top = 2.dp).testTag("assistant-top-bar-status"),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Shield, null, Modifier.size(14.dp), tint = colors.primary)
                            Text(stringResource(Res.string.on_device), Modifier.padding(start = 4.dp),
                                color = colors.primary, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Row(Modifier.testTag("assistant-top-bar-actions"), verticalAlignment = Alignment.CenterVertically,
                        content = actions)
                }
            }
            HorizontalDivider(Modifier.testTag("assistant-top-bar-divider"), color = colors.outlineVariant)
        }
    }
}
