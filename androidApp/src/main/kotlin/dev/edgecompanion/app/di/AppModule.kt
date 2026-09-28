package dev.edgecompanion.app.di

import dev.edgecompanion.app.overlay.PresenceCoordinator

import dev.edgecompanion.core.di.SessionDispatcher
import dev.edgecompanion.ui.conversation.viewmodel.ConversationViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

internal fun appModule() = module {
    single { dev.edgecompanion.app.capture.audio.AudioCaptureGate() }
    single<CoroutineDispatcher>(SessionDispatcher) { Dispatchers.IO.limitedParallelism(1) }
    single { PresenceCoordinator() }
    viewModel { ConversationViewModel(get()) }
}
