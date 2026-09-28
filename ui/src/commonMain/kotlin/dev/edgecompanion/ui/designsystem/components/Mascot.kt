package dev.edgecompanion.ui.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable fun Mascot(modifier: Modifier = Modifier) {
    Image(painterResource(Res.drawable.mascot), stringResource(Res.string.mascot_description), modifier)
}
