package dev.edgecompanion.app.capture.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dev.edgecompanion.app.R
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

@Composable internal fun CameraCaptureContent(host: ComponentActivity, modifier: Modifier, captured: (ByteArray) -> Unit) {
    var capture by remember { mutableStateOf<ImageCapture?>(null) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var disposed by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { disposed = true; provider?.unbindAll() } }
    Column(modifier) {
        AndroidView(factory = { context -> PreviewView(context).also { preview ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                if (!disposed && !host.isDestroyed) try {
                    val cameraProvider = future.get()
                    provider = cameraProvider
                    val cameraPreview = Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }
                    @Suppress("DEPRECATION")
                    val imageCapture = ImageCapture.Builder().setTargetResolution(Size(1920, 1080)).build()
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(host, CameraSelector.DEFAULT_BACK_CAMERA, cameraPreview, imageCapture)
                    capture = imageCapture
                } catch (_: Exception) { error = true }
            }, host.mainExecutor)
        } }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (error) Text(stringResource(R.string.capture_failed))
        Button(enabled = capture != null && !busy, onClick = {
            busy = true; error = false
            capture?.takePicture(host.mainExecutor, object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    host.lifecycleScope.launch {
                        try {
                            val bytes = withContext(Dispatchers.IO) {
                                try {
                                    val original = image.toBitmap()
                                    val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height,
                                        Matrix().apply { postRotate(image.imageInfo.rotationDegrees.toFloat()) }, true)
                                    try { ByteArrayOutputStream().use { out ->
                                        check(rotated.compress(Bitmap.CompressFormat.JPEG, 90, out)); out.toByteArray()
                                    } } finally { if (rotated !== original) rotated.recycle(); original.recycle() }
                                } finally { image.close() }
                            }
                            if (!disposed) captured(bytes)
                        } catch (cancelled: CancellationException) { image.close(); throw cancelled }
                        catch (_: Exception) { error = true; busy = false }
                    }
                }
                override fun onError(exception: ImageCaptureException) { error = true; busy = false }
            })
        }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.capture)) }
    }
}
