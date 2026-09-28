package dev.edgecompanion.ui.sources.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.designsystem.theme.Forest
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun SourcesScreen(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(36.dp), tint = Forest)
            Text(stringResource(Res.string.no_sources), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
