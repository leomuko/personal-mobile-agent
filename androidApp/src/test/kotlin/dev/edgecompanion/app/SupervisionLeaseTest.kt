package dev.edgecompanion.app

import dev.edgecompanion.app.support.*
import org.junit.Assert.*
import org.junit.Test

class SupervisionLeaseTest {
    private fun beat(sequence: Long = 1, at: Long = 100) = SupervisionHeartbeat("current", sequence, at)

    @Test fun pollingAnOldHeartbeatDoesNotRenewItsLease() {
        val lease = SupervisionLease("current")
        assertNull(lease.observe(beat(), 100))
        assertNull(lease.observe(beat(), 20_099))
        assertEquals(SupervisionLoss.EXPIRED, lease.observe(beat(), 20_100))
        assertEquals(SupervisionLoss.EXPIRED, lease.observe(beat(2, 20_101), 20_101))
    }

    @Test fun strictlyAdvancingHeartbeatsRenewTheLease() {
        val lease = SupervisionLease("current")
        assertNull(lease.observe(beat(), 101))
        assertNull(lease.observe(beat(2, 19_000), 19_001))
        assertNull(lease.observe(beat(2, 19_000), 38_999))
        assertEquals(SupervisionLoss.EXPIRED, lease.observe(beat(2, 19_000), 39_000))
    }

    @Test fun aRunScopedStopIsImmediateAndCannotBeUndoneByAnotherHeartbeat() {
        val lease = SupervisionLease("current")
        assertNull(lease.observe(beat(), 100))
        assertEquals(SupervisionLoss.STOP_REQUESTED, lease.observe(beat(), 101, stopRequested = true))
        assertEquals(SupervisionLoss.STOP_REQUESTED, lease.observe(beat(2, 102), 102))
    }

    @Test fun missingWrongReplayedAndFutureEvidenceFailsClosed() {
        assertEquals(SupervisionLoss.UNAVAILABLE, SupervisionLease("current").observe(null, 100))
        assertEquals(SupervisionLoss.WRONG_RUN, SupervisionLease("current").observe(beat().copy(runId = "old"), 100))
        assertEquals(SupervisionLoss.INVALID_CLOCK, SupervisionLease("current").observe(beat(at = 101), 100))
        assertEquals(SupervisionLoss.EXPIRED, SupervisionLease("current").observe(beat(), 30_000))
        assertEquals(SupervisionLoss.REPLAYED, SupervisionLease("current").observe(beat(0), 100))
        for (replay in listOf(beat(1, 200), beat(3, 50), beat(2, 201))) {
            val lease = SupervisionLease("current")
            assertNull(lease.observe(beat(2, 200), 200))
            assertEquals(SupervisionLoss.REPLAYED, lease.observe(replay, 201))
        }
        val lease = SupervisionLease("current")
        assertNull(lease.observe(beat(), 100))
        assertEquals(SupervisionLoss.INVALID_CLOCK, lease.observe(beat(), 99))
    }
}
