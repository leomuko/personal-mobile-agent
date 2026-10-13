package dev.edgecompanion.ui.settings.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.ui.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsPanel(asset: ModelAssetState, runtime: ModelRuntimeState, device: String,
    onImport: () -> Unit, onVerify: () -> Unit, onLoad: (ModelBackend) -> Unit,
    onTest: () -> Unit, onCancelTest: () -> Unit, onCancel: () -> Unit, onRelease: () -> Unit) {
    val busyAsset = asset.status in setOf(AssetStatus.IMPORTING, AssetStatus.VERIFYING)
    val unloaded = runtime.status in setOf(RuntimeStatus.UNLOADED, RuntimeStatus.BLOCKED, RuntimeStatus.FAILED)
    val loaded = runtime.status == RuntimeStatus.LOADED
    Column(Modifier.fillMaxWidth().testTag("models-panel"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.models_internal), style = MaterialTheme.typography.titleMedium)
        Text("Gemma 4 E2B / LiteRT-LM ${ModelCatalog.RUNTIME_VERSION}", style = MaterialTheme.typography.bodyMedium)
        Text(device, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(asset.status.label()), Modifier.testTag("model-asset-state"))
        Text(stringResource(runtime.status.label()), Modifier.testTag("model-runtime-state"))
        Text(stringResource(Res.string.model_configuration, ModelCatalog.gemma4.configuredContextTokens,
            ModelCatalog.gemma4.maxOutputTokens, runtime.backend.name), style = MaterialTheme.typography.bodySmall)
        if (busyAsset) LinearProgressIndicator(Modifier.fillMaxWidth())
        (runtime.error ?: runtime.commandError ?: asset.error)?.let { Text(stringResource(it.label()), color = MaterialTheme.colorScheme.error) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport, enabled = !busyAsset && runtime.status == RuntimeStatus.UNLOADED) {
                Icon(Icons.Outlined.FileOpen, null); Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.model_import))
            }
            OutlinedButton(onClick = onVerify, enabled = !busyAsset && unloaded) {
                Icon(Icons.Outlined.Verified, null); Spacer(Modifier.width(6.dp)); Text(stringResource(Res.string.model_verify))
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModelBackend.entries.forEach { backend ->
                OutlinedButton(onClick = { onLoad(backend) }, enabled = !busyAsset && unloaded && asset.status == AssetStatus.INSTALLED) {
                    Icon(Icons.Outlined.Memory, null); Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.model_load_backend, backend.name))
                }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onTest, enabled = loaded, modifier = Modifier.testTag("model-test")) {
                Icon(Icons.Outlined.PlayArrow, null); Text(stringResource(Res.string.model_test))
            }
            OutlinedButton(onClick = onCancelTest, enabled = loaded) { Text(stringResource(Res.string.model_long_test)) }
            OutlinedButton(onClick = onCancel, enabled = runtime.status == RuntimeStatus.GENERATING || asset.status == AssetStatus.IMPORTING) {
                Icon(Icons.Outlined.Stop, null); Text(stringResource(Res.string.cancel))
            }
            OutlinedButton(onClick = onRelease, enabled = !busyAsset && runtime.status !in setOf(RuntimeStatus.UNLOADED, RuntimeStatus.LOADING, RuntimeStatus.RELEASING)) {
                Icon(Icons.Outlined.PowerSettingsNew, null); Text(stringResource(Res.string.model_unload))
            }
        }
        if (runtime.output.isNotEmpty()) Text(runtime.output, Modifier.testTag("model-output"))
        runtime.loadMs?.let { Text(stringResource(Res.string.model_load_ms, it)) }
        runtime.firstTokenMs?.let { Text(stringResource(Res.string.model_first_token_ms, it)) }
        runtime.outputTokens?.let { Text(stringResource(Res.string.model_output_tokens, it)) }
        Text(ModelCatalog.gemma4.sha256, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
    }
}

private fun AssetStatus.label(): StringResource = when (this) {
    AssetStatus.NOT_INSTALLED -> Res.string.model_asset_missing
    AssetStatus.IMPORTING -> Res.string.model_importing
    AssetStatus.VERIFYING -> Res.string.model_verifying
    AssetStatus.INSTALLED -> Res.string.model_installed
    AssetStatus.REPAIR_REQUIRED -> Res.string.model_repair
    AssetStatus.FAILED -> Res.string.model_asset_failed
}
private fun RuntimeStatus.label(): StringResource = when (this) {
    RuntimeStatus.UNLOADED -> Res.string.model_unloaded
    RuntimeStatus.LOADING -> Res.string.model_loading
    RuntimeStatus.LOADED -> Res.string.model_loaded
    RuntimeStatus.GENERATING -> Res.string.model_generating
    RuntimeStatus.CANCELLING -> Res.string.model_cancelling
    RuntimeStatus.RELEASING -> Res.string.model_releasing
    RuntimeStatus.BLOCKED -> Res.string.model_blocked
    RuntimeStatus.FAILED -> Res.string.model_runtime_failed
}
private fun ModelError.label(): StringResource = when (this) {
    ModelError.NOT_INSTALLED -> Res.string.model_asset_missing
    ModelError.BUSY -> Res.string.model_busy
    ModelError.SIZE_MISMATCH, ModelError.HASH_MISMATCH -> Res.string.model_integrity_error
    ModelError.STORAGE_FULL -> Res.string.model_storage_error
    ModelError.IMPORT_FAILED -> Res.string.model_import_error
    ModelError.UNSUPPORTED_DEVICE -> Res.string.model_device_error
    ModelError.LOW_MEMORY -> Res.string.model_memory_error
    ModelError.THERMAL -> Res.string.model_thermal_error
    ModelError.RESOURCE_UNKNOWN -> Res.string.model_resource_error
    ModelError.LOAD_FAILED -> Res.string.model_load_error
    ModelError.GENERATION_FAILED -> Res.string.model_generation_error
    ModelError.CONTEXT_LIMIT -> Res.string.model_context_error
    ModelError.CANCEL_TIMEOUT, ModelError.RELEASE_FAILED, ModelError.RESTART_REQUIRED -> Res.string.model_restart_error
}
