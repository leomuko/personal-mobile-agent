package dev.edgecompanion.ui.memory.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.designsystem.theme.Forest
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.stringResource

@Composable internal fun MemoryScreen(modifier: Modifier) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Bookmarks, null, Modifier.size(36.dp), tint = Forest)
            Text(stringResource(Res.string.no_memories), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        }
    }
}
