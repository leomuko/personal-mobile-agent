package dev.edgecompanion.app.di

import android.content.Context
import android.os.SystemClock
import dev.edgecompanion.app.BuildConfig
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.app.inference.selectMemoryPolicy
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.assets.PrivateModelAssets
import dev.edgecompanion.core.inference.litert.LiteRtLlmEngine
import dev.edgecompanion.ui.settings.viewmodel.ModelsViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal fun modelModule(context: Context) = module {
    single<ModelAssets> { PrivateModelAssets(File(context.noBackupFilesDir, "models")) }
    single<LocalLlmEngine> { LiteRtLlmEngine(enabled = BuildConfig.DEBUG) }
    single { AndroidModelResources(context) }
    single { selectMemoryPolicy(BuildConfig.DEBUG, BuildConfig.BUILD_TYPE, BuildConfig.ANDROID_PRESSURE_MEMORY_TEST) }
    single { MemoryTelemetryRecorder(get(), get()) }
    single { ModelManager(get(), get(), Dispatchers.Default, get<AndroidModelResources>()::snapshot,
        SystemClock::elapsedRealtime, enabled = BuildConfig.DEBUG,
        memoryPolicy = get(),
        memoryTelemetry = get<MemoryTelemetryRecorder>()::recordStage) }
    single<ChatRuntime> { get<ModelManager>() }
    viewModel { ModelsViewModel(get()) }
}
