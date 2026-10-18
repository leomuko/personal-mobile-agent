package dev.edgecompanion.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.support.DeviceSupervision
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import android.os.SystemClock
import org.json.JSONObject

/** Opt-in watchdog proof: no engine, microphone, conversation writes or personal fixtures. */
class SupervisionProbeTest {
    @Test fun syntheticWorkIsStoppedWhenHeartbeatsDisappear() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("supervisionProbe")
        assumeTrue(mode in listOf("healthy", "recovery", "handoff", "stop-request", "loss", "hung-cleanup"))
        val id = requireNotNull(args.getString("supervisionRunId"))
        val prior = args.getString("supervisionPriorRunId")
        if (mode == "handoff") {
            DeviceSupervision.open(requireNotNull(prior)) { true }.use {
                val rejected = runCatching { DeviceSupervision.open(id) { true }.close() }.isFailure
                check(rejected) { "A second run was admitted before the old owner quiesced" }
            }
        }
        DeviceSupervision.open(id) {
            if (mode == "hung-cleanup") Thread.sleep(60_000)
            true
        }.use {
            if (mode == "healthy" || mode == "recovery") Thread.sleep(1_000)
            else if (mode == "handoff") {
                val root = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir, "qa-supervision/$prior")
                val deadline = SystemClock.elapsedRealtime() + 15_000
                while (!File(root, "stop.json").exists() || JSONObject(File(root, "heartbeat.json").readText()).getLong("sequence") < 2) {
                    check(SystemClock.elapsedRealtime() < deadline) { "No stale-owner intervention arrived" }
                    Thread.sleep(100)
                }
                Thread.sleep(1_000) // Both guard polls must observe that the new run remains healthy.
            }
            else {
                Thread.sleep(60_000)
                error("The expired watchdog did not stop this synthetic workload")
            }
        }
    }
}
