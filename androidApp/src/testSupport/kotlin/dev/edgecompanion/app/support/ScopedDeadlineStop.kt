package dev.edgecompanion.app.support

/** Test-only guard: close excludes an old request's emergency signal from a later request. */
class ScopedDeadlineStop(private val stop: () -> Unit) : AutoCloseable {
    private val gate = Any()
    private var closed = false
    fun expire() = synchronized(gate) {
        if (!closed) { closed = true; stop() }
    }
    override fun close() = synchronized(gate) { closed = true }
}
