package dev.edgecompanion.app

import kotlinx.coroutines.runBlocking
import org.junit.Test

/** Requires separate reviewed freeze/device permission; never part of ordinary secure-QA model execution. */
class OutputAdherenceNativeTest {
    @Test fun sixControlsWithStrictOutputAndConfirmedCleanup(): Unit = runBlocking {
        runSerialVerification(SerialExperiment.OUTPUT_ADHERENCE)
    }
}
