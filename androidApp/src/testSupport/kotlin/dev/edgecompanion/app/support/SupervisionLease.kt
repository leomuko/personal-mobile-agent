package dev.edgecompanion.app.support

data class SupervisionHeartbeat(val runId: String, val sequence: Long, val elapsedMs: Long)
enum class SupervisionLoss { UNAVAILABLE, WRONG_RUN, INVALID_CLOCK, REPLAYED, EXPIRED, STOP_REQUESTED }

/** Test-only lease using the phone's boot clock, never the host's wall clock. */
class SupervisionLease(private val runId: String) {
    private var previous: SupervisionHeartbeat? = null
    private var lastPoll = -1L
    private var lost: SupervisionLoss? = null

    fun observe(heartbeat: SupervisionHeartbeat?, nowMs: Long, stopRequested: Boolean = false): SupervisionLoss? {
        lost?.let { return it }
        val prior = previous
        val failure = when {
            stopRequested -> SupervisionLoss.STOP_REQUESTED
            heartbeat == null -> SupervisionLoss.UNAVAILABLE
            heartbeat.runId != runId -> SupervisionLoss.WRONG_RUN
            nowMs < 0 || nowMs < lastPoll || heartbeat.elapsedMs < 0 || heartbeat.elapsedMs > nowMs -> SupervisionLoss.INVALID_CLOCK
            heartbeat.sequence < 1 || (prior != null && (heartbeat.sequence < prior.sequence ||
                (heartbeat.sequence == prior.sequence && heartbeat.elapsedMs != prior.elapsedMs) ||
                heartbeat.elapsedMs < prior.elapsedMs)) -> SupervisionLoss.REPLAYED
            nowMs - heartbeat.elapsedMs >= LEASE_MS -> SupervisionLoss.EXPIRED
            else -> null
        }
        lost = failure
        lastPoll = nowMs
        if (failure == null) previous = heartbeat
        return failure
    }

    companion object {
        const val LEASE_MS = 20_000L
        const val POLL_MS = 500L
        const val CLEANUP_MS = 5_000L
    }
}
