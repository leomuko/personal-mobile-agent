package dev.edgecompanion.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Serialization check with synthetic ownership metadata; never loads a native model. */
class MemoryTelemetryConfigurationTest {
    @Test fun additiveContextKeysPreserveNullsAndResidentSizes(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recorder = MemoryTelemetryRecorder(AndroidModelResources(context))
        recorder.start()
        try {
            recorder.baseline()
            recorder.recordStage(MemoryTracePoint(MemoryStage.LOADED_IDLE, ModelBackend.GPU,
                RuntimeStatus.LOADED, true, android.os.SystemClock.elapsedRealtime(), MemoryPolicies.Current.id, 8192, 512))
            recorder.recordStage(MemoryTracePoint(MemoryStage.POST_RELEASE, ModelBackend.GPU,
                RuntimeStatus.UNLOADED, false, android.os.SystemClock.elapsedRealtime(), MemoryPolicies.Current.id))
            val report = recorder.report()
            val samples = report.getJSONArray("samples")
            assertEquals(3, samples.length())
            for (index in 0 until samples.length()) {
                val sample = samples.getJSONObject(index)
                for (key in listOf("stage", "backend", "runtimeStatusAtEvent", "engineOwnedAtEvent", "eventElapsedMs",
                    "policyId", "availableMemoryBytes", "pressureThresholdBytes", "appPssBytes", "appRssBytes",
                    "contextTokensAtEvent", "outputTokensAtEvent")) assertTrue(key, sample.has(key))
                if (index == 1) {
                    assertEquals(8192, sample.getInt("contextTokensAtEvent"))
                    assertEquals(512, sample.getInt("outputTokensAtEvent"))
                } else {
                    assertTrue(sample.isNull("contextTokensAtEvent")); assertTrue(sample.isNull("outputTokensAtEvent"))
                }
            }
        } finally { recorder.stop() }
    }
}
