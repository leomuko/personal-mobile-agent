package dev.edgecompanion.app

import dev.edgecompanion.app.support.ScopedDeadlineStop
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ScopedDeadlineStopTest {
    @Test fun closedRequestCannotStopALaterRequest() {
        var stops = 0
        val deadline = ScopedDeadlineStop { stops++ }
        deadline.close(); deadline.expire(); deadline.expire()
        assertEquals(0, stops)
    }
    @Test fun cleanupCannotPassAFiringSignalAndExpirationIsIdempotent() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); var stops = 0
        val deadline = ScopedDeadlineStop { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); stops++ }
        val expired = Thread { deadline.expire() }.also { it.start() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val closing = Thread { deadline.close() }.also { it.start() }
        release.countDown(); expired.join(5000); closing.join(5000)
        assertFalse(expired.isAlive); assertFalse(closing.isAlive)
        deadline.expire(); assertEquals(1, stops)
    }
}
