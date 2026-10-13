package dev.edgecompanion.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import dev.edgecompanion.ui.settings.screen.ModelsPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ModelsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test fun installDoesNotMeanRuntimeReadyAndCancelRemainsReachable() {
        val asset = mutableStateOf(ModelAssetState())
        val runtime = mutableStateOf(ModelRuntimeState())
        var cancelled = 0
        compose.setContent { CompanionTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelsPanel(asset.value, runtime.value, "Synthetic emulator", {}, {}, {}, {}, {}, { cancelled++ }, {})
            }
        } }
        compose.onNodeWithTag("model-test").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { asset.value = ModelAssetState(AssetStatus.INSTALLED) }
        compose.onNodeWithTag("model-test").assertIsNotEnabled()
        compose.runOnIdle { runtime.value = ModelRuntimeState(RuntimeStatus.LOADED) }
        compose.onNodeWithTag("model-test").assertIsEnabled()
        compose.runOnIdle { runtime.value = ModelRuntimeState(RuntimeStatus.GENERATING, commandError = ModelError.BUSY) }
        compose.onNodeWithTag("model-test").assertIsNotEnabled()
        compose.onNodeWithText("Another model operation is active.").assertExists()
        compose.onNodeWithText("Cancel").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, cancelled) }
        compose.runOnIdle {
            runtime.value = ModelRuntimeState()
            asset.value = ModelAssetState(AssetStatus.IMPORTING)
        }
        compose.onNodeWithText("Cancel").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(2, cancelled) }
    }
}
