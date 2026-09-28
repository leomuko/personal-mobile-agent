package dev.edgecompanion.app.overlay

internal data class PresenceState(
    val running: Boolean = false,
    val overlayAllowed: Boolean = false,
    val unlocked: Boolean = false,
    val surfaces: Set<Int> = emptySet(),
    val opening: Boolean = false,
) {
    val mascotVisible get() = running && overlayAllowed && unlocked && surfaces.isEmpty() && !opening
}

