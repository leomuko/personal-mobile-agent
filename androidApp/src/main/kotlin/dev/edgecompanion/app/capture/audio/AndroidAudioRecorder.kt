package dev.edgecompanion.app.capture.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import dev.edgecompanion.ui.conversation.components.RecordingState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** One foreground host owns capture. The worker releases the recorder before returning audio. */
internal class AndroidAudioRecorder(private val gate: AudioCaptureGate, private val finished: (ByteArray) -> Unit, private val failed: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    private val stopping = AtomicBoolean(false)
    private val discard = AtomicBoolean(false)
    private var job: Job? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (job?.isActive == true) return
        if (!gate.acquire()) { failed(); return }
        stopping.set(false); discard.set(false)
        mutable.value = RecordingState(active = true)
        job = scope.launch(Dispatchers.IO) {
            val pcm = ByteArray(16000 * 2 * 300)
            var size = 0
            var recorder: AudioRecord? = null
            try {
                val minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(minimum > 0)
                recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minimum * 2)
                check(recorder.state == AudioRecord.STATE_INITIALIZED)
                recorder.startRecording()
                val frame = ShortArray(1024)
                while (!stopping.get() && size + frame.size * 2 <= pcm.size) {
                    ensureActive()
                    val count = recorder.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                    check(count > 0)
                    var peak = 0
                    for (index in 0 until count) {
                        val sample = frame[index].toInt()
                        peak = maxOf(peak, abs(sample))
                        pcm[size++] = sample.toByte(); pcm[size++] = (sample shr 8).toByte()
                    }
                    withContext(Dispatchers.Main) {
                        mutable.value = RecordingState(true, size / 32000, mutable.value.levels.drop(1) + (peak / 32768f))
                    }
                }
            } catch (cancelled: CancellationException) { discard.set(true); throw cancelled }
            catch (_: Exception) { discard.set(true); withContext(Dispatchers.Main) { failed() } }
            finally {
                runCatching { recorder?.stop() }; runCatching { recorder?.release() }; gate.release()
                val bytes = if (!discard.get() && size > 0) wav(pcm, size) else null
                pcm.fill(0)
                withContext(NonCancellable + Dispatchers.Main) {
                    mutable.value = RecordingState()
                    if (bytes != null) finished(bytes)
                }
            }
        }
    }
    fun stop(cancel: Boolean = false) { if (cancel) discard.set(true); stopping.set(true) }
    fun close() { stop(); job?.invokeOnCompletion { scope.cancel() } ?: scope.cancel() }

    private fun wav(pcm: ByteArray, size: Int): ByteArray = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
        putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(size); put(pcm, 0, size)
    }.array()
}
