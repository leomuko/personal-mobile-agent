package dev.edgecompanion.app

import dev.edgecompanion.app.overlay.PresenceState
import dev.edgecompanion.app.overlay.PresenceCoordinator
import dev.edgecompanion.app.permissions.EnableStep
import dev.edgecompanion.app.permissions.nextEnableStep

import org.junit.Assert.*
import org.junit.Test

class PresenceAndPermissionTest {
    private fun running() = PresenceCoordinator().apply { environment(true, true); service(true) }

    @Test fun mascotRequiresEveryVisibilityCondition() {
        val visible = PresenceState(running = true, overlayAllowed = true, unlocked = true)
        assertTrue(visible.mascotVisible)
        assertFalse(visible.copy(running = false).mascotVisible)
        assertFalse(visible.copy(overlayAllowed = false).mascotVisible)
        assertFalse(visible.copy(unlocked = false).mascotVisible)
        assertFalse(visible.copy(surfaces = setOf(1)).mascotVisible)
        assertFalse(visible.copy(opening = true).mascotVisible)
    }
    @Test fun drawerAndCaptureHandoffDoesNotRevealMascot() {
        val presence = running()
        presence.opening(true); assertFalse(presence.state.value.mascotVisible)
        presence.started(1); presence.started(2); presence.stopped(1)
        assertFalse(presence.state.value.opening)
        assertFalse(presence.state.value.mascotVisible)
        presence.started(1); presence.stopped(2); presence.stopped(1)
        assertTrue(presence.state.value.mascotVisible)
    }
    @Test fun recreationRetainsSuppressionUntilReplacementStarts() {
        val presence = running()
        presence.started(1); presence.stopped(1, changingConfiguration = true)
        assertFalse(presence.state.value.mascotVisible)
        presence.started(2)
        assertEquals(setOf(2), presence.state.value.surfaces)
        presence.stopped(2); assertTrue(presence.state.value.mascotVisible)
    }
    @Test fun unlockCannotOverrideDrawerAndStopCannotBeUndoneByClose() {
        val presence = running()
        presence.started(1); presence.environment(true, false); presence.environment(true, true)
        assertFalse(presence.state.value.mascotVisible)
        presence.service(false); presence.stopped(1)
        assertFalse(presence.state.value.mascotVisible)
    }
    @Test fun failedLaunchRecoversOnlyWhileServiceIsStillEnabled() {
        val presence = running()
        presence.opening(true); presence.opening(false)
        assertTrue(presence.state.value.mascotVisible)
        presence.opening(true); presence.service(false); presence.opening(false)
        assertFalse(presence.state.value.mascotVisible)
    }
    @Test fun permissionDecisionsDoNotTreatDeniedNotificationsAsGranted() {
        assertEquals(EnableStep.OVERLAY_PERMISSION, nextEnableStep(false, true, false, false))
        assertEquals(EnableStep.NOTIFICATION_PERMISSION, nextEnableStep(true, false, true, false))
        assertEquals(EnableStep.CONFIRM_DEGRADED, nextEnableStep(true, false, false, false))
        assertEquals(EnableStep.START, nextEnableStep(true, false, false, true))
        assertEquals(EnableStep.START, nextEnableStep(true, true, false, false))
        assertEquals(EnableStep.OVERLAY_PERMISSION, nextEnableStep(false, false, false, true))
    }
}
