package dev.edgecompanion.app.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android main-thread owned; tokens do not retain Activity instances. */
internal class PresenceCoordinator {
    private val mutable = MutableStateFlow(PresenceState())
    private val recreating = mutableSetOf<Int>()
    val state = mutable.asStateFlow()
    fun service(running: Boolean) {
        mutable.value = mutable.value.copy(running = running, opening = false)
    }
    fun environment(overlayAllowed: Boolean, unlocked: Boolean) {
        mutable.value = mutable.value.copy(overlayAllowed = overlayAllowed, unlocked = unlocked)
    }
    fun started(token: Int) {
        mutable.value = mutable.value.copy(surfaces = (mutable.value.surfaces - recreating) + token, opening = false)
        recreating.clear()
    }
    fun stopped(token: Int, changingConfiguration: Boolean = false) {
        if (changingConfiguration) recreating.add(token)
        else {
            recreating.remove(token)
            mutable.value = mutable.value.copy(surfaces = mutable.value.surfaces - token)
        }
    }
    fun opening(value: Boolean) { mutable.value = mutable.value.copy(opening = value) }
}
