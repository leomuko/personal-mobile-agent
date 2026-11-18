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
import org.junit.Rule
import org.junit.Test

/** Synthetic rendering only; never resolves the app model manager or installed assets. */
class ModelsPanelConfigurationTest {
    @get:Rule val compose = createComposeRule()

    @Test fun configurationDistinguishesResidentSizesAndExplainsExtendedPromptRefusal() {
        val runtime = mutableStateOf(ModelRuntimeState(backend = ModelBackend.GPU))
        compose.setContent { CompanionTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ModelsPanel(ModelAssetState(AssetStatus.INSTALLED), runtime.value, "Synthetic device",
                    {}, {}, {}, {}, {}, {}, {})
            }
        } }
        compose.onNodeWithTag("model-configuration")
            .assertTextEquals("Panel configuration (not loaded): Context 2048 · Output 512 · GPU")
        compose.runOnIdle {
            runtime.value = ModelRuntimeState(RuntimeStatus.FAILED, ModelBackend.GPU, error = ModelError.RESTART_REQUIRED)
        }
        compose.onNodeWithTag("model-configuration")
            .assertTextEquals("Panel configuration (load not confirmed): Context 2048 · Output 512 · GPU")
        compose.runOnIdle {
            runtime.value = ModelRuntimeState(RuntimeStatus.LOADED, ModelBackend.GPU,
                loadedContextTokens = 8192, loadedOutputTokens = 512)
        }
        compose.onNodeWithTag("model-configuration").assertTextEquals("Loaded: Context 8192 · Output 512 · GPU")
        compose.onNodeWithTag("model-test-profile-refused").assertDoesNotExist()
        compose.runOnIdle { runtime.value = runtime.value.copy(commandError = ModelError.BUSY) }
        compose.onNodeWithTag("model-test-profile-refused").assertTextEquals(
            "Test prompts require Context 2048 / Output 512. A different context is loaded. Unload it, then load from this panel.")
        compose.onNodeWithText("Another model operation is active.").assertDoesNotExist()
        compose.runOnIdle { runtime.value = runtime.value.copy(loadedContextTokens = 2048) }
        compose.onNodeWithTag("model-test-profile-refused").assertDoesNotExist()
        compose.onNodeWithText("Another model operation is active.").assertExists()
        compose.onNodeWithTag("model-configuration").assertTextEquals("Loaded: Context 2048 · Output 512 · GPU")
        compose.runOnIdle { runtime.value = ModelRuntimeState(backend = ModelBackend.GPU) }
        compose.onNodeWithTag("model-configuration")
            .assertTextEquals("Panel configuration (not loaded): Context 2048 · Output 512 · GPU")
    }
}
