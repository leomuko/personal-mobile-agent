package dev.edgecompanion.app.inference

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import android.os.Debug
import dev.edgecompanion.core.inference.ResourceSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidModelResources(private val context: Context) {
    fun snapshot(): ResourceSnapshot {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val thermal = try { context.getSystemService(PowerManager::class.java).currentThermalStatus }
            catch (_: RuntimeException) { null }
        return ResourceSnapshot(memory.totalMem, memory.availMem, memory.lowMemory, thermal, memory.threshold)
    }

    suspend fun telemetrySnapshot(): ResourceSnapshot = withContext(Dispatchers.IO) {
        val info = Debug.MemoryInfo()
        Debug.getMemoryInfo(info)
        val rss = File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("VmRSS:") }?.let {
                Regex("VmRSS:\\s+(\\d+)\\s+kB").find(it)?.groupValues?.get(1)?.toLongOrNull()?.times(1024)
            }
        }
        snapshot().copy(appPssBytes = info.totalPss.toLong() * 1024, appRssBytes = rss)
    }
}
