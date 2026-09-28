package dev.edgecompanion.app.capture.audio

import java.util.concurrent.atomic.AtomicBoolean

/** App-scoped lease prevents two visible chat hosts from opening separate microphones. */
internal class AudioCaptureGate {
    private val acquired = AtomicBoolean(false)
    fun acquire(): Boolean = acquired.compareAndSet(false, true)
    fun release() { acquired.set(false) }
}
