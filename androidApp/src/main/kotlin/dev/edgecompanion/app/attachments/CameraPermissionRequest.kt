package dev.edgecompanion.app.attachments

import android.Manifest
import android.os.Bundle
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContracts
import java.util.UUID

/** A restored request may finish normally, but never locks out a new explicit user action. */
internal class CameraPermissionRequest(
    private val registry: ActivityResultRegistry,
    private val hasPermission: () -> Boolean,
    private val onGranted: (Long) -> Unit,
    private val onDenied: () -> Unit,
) {
    private var pending: Request? = null
    private var launcher: ActivityResultLauncher<String>? = null

    private data class Request(val key: String, val epoch: Long)

    fun restore(state: Bundle?) {
        val key = state?.getString("cameraRequestKey") ?: return
        val request = Request(key, state.getLong("cameraRequestEpoch"))
        pending = request
        register(request)
    }

    fun save(state: Bundle) {
        pending?.let {
            state.putString("cameraRequestKey", it.key)
            state.putLong("cameraRequestEpoch", it.epoch)
        }
    }

    fun launch(epoch: Long) {
        cancel()
        if (hasPermission()) {
            onGranted(epoch)
            return
        }
        val request = Request("camera-permission-${UUID.randomUUID()}", epoch)
        pending = request
        register(request)
        launcher?.launch(Manifest.permission.CAMERA)
    }

    fun cancel() {
        pending = null
        launcher?.unregister()
        launcher = null
    }

    private fun register(request: Request) {
        // Distinct registry keys prevent an old result from completing a newer request.
        val registered = registry.register(request.key, ActivityResultContracts.RequestPermission()) { granted ->
            if (pending == request) {
                cancel()
                if (granted && hasPermission()) onGranted(request.epoch) else onDenied()
            }
        }
        // register can synchronously deliver a restored result before returning its launcher.
        if (pending == request) launcher = registered else registered.unregister()
    }
}
