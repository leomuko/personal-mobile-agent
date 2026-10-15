package dev.edgecompanion.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.app.inference.MemoryTelemetryRecorder
import dev.edgecompanion.core.inference.ModelManager
import dev.edgecompanion.core.inference.MemoryPolicies
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.koin.android.ext.android.getKoin

class MemoryPolicyProbeTest {
    @Test fun exportsSharedPolicyAndPressureSnapshot(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val policy = JSONObject(koin.get<ModelManager>().memoryPolicy.exportFields())
        val resource = AndroidModelResources(context).telemetrySnapshot()
        val recorder = koin.get<MemoryTelemetryRecorder>()
        recorder.start()
        try {
            recorder.baseline(); recorder.sample()
            val trace = recorder.report()
            val recordedPolicy = trace.getJSONObject("policy")
            assertEquals(policy.length(), recordedPolicy.length())
            policy.keys().forEach { key -> assertEquals(policy.get(key), recordedPolicy.get(key)) }
            for (index in 0 until trace.getJSONArray("samples").length())
                assertEquals(policy.getString("id"), trace.getJSONArray("samples").getJSONObject(index).getString("policyId"))
            assertEquals(0, trace.getInt("failedSamples"))
            assertEquals(2, trace.getJSONArray("samples").length())
            assertTrue(trace.getJSONArray("samples").getJSONObject(0).getLong("appPssBytes") > 0)
        } finally { recorder.stop() }
        File(context.noBackupFilesDir, "memory-policy.json").writeText(policy.toString(2))
        File(context.noBackupFilesDir, "memory-baseline.json").writeText(JSONObject()
            .put("recordedAtMs", System.currentTimeMillis()).put("stage", "BASELINE")
            .put("policyId", policy.getString("id"))
            .put("availableMemoryBytes", resource.availableMemoryBytes ?: JSONObject.NULL)
            .put("pressureThresholdBytes", resource.pressureThresholdBytes ?: JSONObject.NULL)
            .put("lowMemory", resource.lowMemory).put("thermalStatus", resource.thermalStatus ?: JSONObject.NULL)
            .put("appPssBytes", resource.appPssBytes ?: JSONObject.NULL)
            .put("appRssBytes", resource.appRssBytes ?: JSONObject.NULL).toString(2))
        val experimental = BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "secureQa" && BuildConfig.ANDROID_PRESSURE_MEMORY_TEST
        assertEquals(if (experimental) MemoryPolicies.AndroidPressureTest.id else MemoryPolicies.Current.id, policy.getString("id"))
        assertEquals(!experimental, policy.getBoolean("enforceAvailableMemoryFloor"))
        assertTrue(checkNotNull(resource.pressureThresholdBytes) > 0)
        assertTrue(checkNotNull(resource.appPssBytes) > 0)
        assertTrue(checkNotNull(resource.appRssBytes) > 0)
    }
}
