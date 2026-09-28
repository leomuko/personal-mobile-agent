package dev.edgecompanion.app.attachments

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.ui.resources.*
import kotlinx.coroutines.*
import org.jetbrains.compose.resources.stringResource
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Composable internal fun AudioPreview(id: String, files: AttachmentFiles) {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var playing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    DisposableEffect(id) { onDispose { job?.cancel() } }
    if (error) Text(stringResource(Res.string.preview_unavailable))
    TextButton(onClick = {
        if (playing) { job?.cancel(); return@TextButton }
        playing = true; error = false
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = files.read(id)
                    var track: AudioTrack? = null
                    try {
                        require(bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
                        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                        require(header.getInt(16) == 16 && header.getShort(20).toInt() == 1 &&
                            header.getShort(22).toInt() == 1 && header.getInt(24) == 16000 && header.getShort(34).toInt() == 16)
                        track = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                            .setAudioFormat(AudioFormat.Builder().setSampleRate(16000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(16000).setTransferMode(AudioTrack.MODE_STREAM).build()
                        track.play()
                        var offset = 44
                        while (offset < bytes.size) {
                            ensureActive()
                            val count = track.write(bytes, offset, minOf(2048, bytes.size - offset), AudioTrack.WRITE_BLOCKING)
                            check(count > 0); offset += count
                        }
                        delay(500)
                    } finally { runCatching { track?.stop() }; track?.release(); bytes.fill(0) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { error = true }
            finally { playing = false }
        }
    }) { Text(stringResource(if (playing) Res.string.audio_stop else Res.string.audio_play)) }
}
