package dev.edgecompanion.ui.settings.viewmodel

import androidx.lifecycle.ViewModel
import dev.edgecompanion.core.inference.*

class ModelsViewModel(private val manager: ModelManager) : ViewModel() {
    val runtime = manager.state
    val asset = manager.assets.state
    fun verify() { manager.verify() }
    fun install(source: ModelSource) { manager.install(source) }
    fun load(backend: ModelBackend) { manager.load(backend) }
    fun test() { manager.testPrompt() }
    fun cancellationPrompt() { manager.testPrompt(ModelTestPrompts.CANCELLATION) }
    fun cancel() { manager.cancel() }
    fun release() { manager.release() }
}
