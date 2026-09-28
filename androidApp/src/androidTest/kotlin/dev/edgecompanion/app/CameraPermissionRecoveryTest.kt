package dev.edgecompanion.app

import android.os.Bundle
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.attachments.CameraPermissionRequest
import org.junit.Assert.*
import org.junit.Test

/** Controlled registry results exercise recovery independently of an OEM's permission dialog. */
class CameraPermissionRecoveryTest {
    private class Registry : ActivityResultRegistry() {
        val launches = mutableListOf<Int>()
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I,
            options: ActivityOptionsCompat?) { launches += requestCode }
        fun reply(index: Int, granted: Boolean) { dispatchResult(launches[index], granted) }
    }

    private fun onMain(test: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(test)

    @Test fun restoredMissingCallbackCanRetryAndIgnoresLateResult() = onMain {
        val registry = Registry()
        val opened = mutableListOf<Long>()
        var granted = false
        val first = CameraPermissionRequest(registry, { granted }, opened::add, { error("Unexpected denial") })
        first.launch(12)
        val saved = Bundle().also(first::save)
        first.cancel()
        val restored = CameraPermissionRequest(registry, { granted }, opened::add, { error("Unexpected denial") })
        restored.restore(saved)
        assertEquals(1, registry.launches.size)
        restored.launch(13)
        assertEquals(2, registry.launches.size)
        granted = true
        registry.reply(0, true)
        assertTrue(opened.isEmpty())
        registry.reply(1, true)
        registry.reply(1, true)
        assertEquals(listOf(13L), opened)
        restored.cancel()
    }

    @Test fun restoredResultKeepsOriginalEpochAndIsConsumedOnce() = onMain {
        val registry = Registry()
        val opened = mutableListOf<Long>()
        var granted = false
        val first = CameraPermissionRequest(registry, { granted }, opened::add, {})
        first.launch(4)
        val saved = Bundle().also(first::save)
        first.cancel()
        granted = true
        registry.reply(0, true)
        val restored = CameraPermissionRequest(registry, { granted }, opened::add, {})
        restored.restore(saved)
        registry.reply(0, true)
        assertEquals(listOf(4L), opened)
        assertNull(Bundle().also(restored::save).getString("cameraRequestKey"))
        restored.cancel()
    }

    @Test fun switchingSourceCancelsPendingCameraWithoutBlockingOtherWork() = onMain {
        val registry = Registry()
        val opened = mutableListOf<Long>()
        var granted = false
        val request = CameraPermissionRequest(registry, { granted }, opened::add, { error("Unexpected denial") })
        request.launch(1)
        request.cancel() // Files and Photos both invalidate the pending camera request.
        granted = true
        registry.reply(0, true)
        assertTrue(opened.isEmpty())
        request.launch(2)
        assertEquals(listOf(2L), opened)
        assertEquals(1, registry.launches.size)
    }

    @Test fun denialAllowsRetryAndGrantIsCheckedAgain() = onMain {
        val registry = Registry()
        val opened = mutableListOf<Long>()
        var denied = 0
        var granted = false
        val request = CameraPermissionRequest(registry, { granted }, opened::add, { denied++ })
        request.launch(1)
        registry.reply(0, false)
        request.launch(1)
        registry.reply(1, true) // A stale grant is not authority if permission is currently absent.
        assertEquals(2, denied)
        assertTrue(opened.isEmpty())
        granted = true
        request.launch(2)
        assertEquals(listOf(2L), opened)
    }

    @Test fun legacySavedPendingFlagCannotLockOutCamera() = onMain {
        val registry = Registry()
        val opened = mutableListOf<Long>()
        val request = CameraPermissionRequest(registry, { true }, opened::add, {})
        request.restore(Bundle().apply { putBoolean("cameraPermissionPending", true) })
        request.launch(7)
        assertEquals(listOf(7L), opened)
    }
}
