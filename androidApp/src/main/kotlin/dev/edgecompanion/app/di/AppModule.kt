package dev.edgecompanion.app.di

import dev.edgecompanion.app.appearance.AndroidAppearanceStore
import dev.edgecompanion.core.appearance.AppearancePreferences
import dev.edgecompanion.core.appearance.AppearanceStore
import dev.edgecompanion.app.overlay.PresenceCoordinator

import dev.edgecompanion.core.di.SessionDispatcher
import dev.edgecompanion.ui.conversation.viewmodel.ConversationViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.android.ext.koin.androidContext

internal fun appModule() = module {
    single { AndroidAppearanceStore(androidContext()) }
    single<AppearanceStore> { get<AndroidAppearanceStore>() }
    single { AppearancePreferences(get(), Dispatchers.Default) } onClose { it?.close() }
    single { dev.edgecompanion.app.capture.audio.AudioCaptureGate() }
    single<CoroutineDispatcher>(SessionDispatcher) { Dispatchers.IO.limitedParallelism(1) }
    single { PresenceCoordinator() }
    viewModel { ConversationViewModel(get()) }
    viewModel { dev.edgecompanion.ui.sources.viewmodel.SourcesViewModel(get<dev.edgecompanion.core.conversation.session.ConversationSession>()) }
}
