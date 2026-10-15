package dev.edgecompanion.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.system.OsConstants
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class MemoryExitProbeTest {
    @Test fun recordsOwnPackageExitMetadataWithoutTraces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val runId = args.getString("memoryRunId") ?: UUID.randomUUID().toString()
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val phase = args.getString("memoryExitPhase") ?: "standalone"
        require(phase in setOf("before", "after", "standalone"))
        val activity = context.getSystemService(ActivityManager::class.java)
        val supported = ActivityManager.isLowMemoryKillReportSupported()
        val entries = JSONArray()
        activity.getHistoricalProcessExitReasons(context.packageName, 0, 32).forEach { exit ->
            val classification = when {
                exit.reason == ApplicationExitInfo.REASON_LOW_MEMORY && supported -> "confirmed-low-memory"
                exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE -> "native-crash"
                exit.reason == ApplicationExitInfo.REASON_ANR -> "anr"
                exit.reason == ApplicationExitInfo.REASON_SIGNALED && exit.status == OsConstants.SIGKILL -> "sigkill-unclassified"
                else -> "other-not-attributed-to-memory"
            }
            entries.put(JSONObject().put("timestampMs", exit.timestamp).put("reason", exit.reason)
                .put("status", exit.status).put("classification", classification)
                .put("pssBytes", exit.pss * 1024).put("rssBytes", exit.rss * 1024))
        }
        File(context.noBackupFilesDir, "memory-exits.json").writeText(JSONObject()
            .put("runId", runId).put("phase", phase).put("capturedAtMs", System.currentTimeMillis())
            .put("lowMemoryKillReportSupported", supported).put("entries", entries).toString(2))
    }
}
