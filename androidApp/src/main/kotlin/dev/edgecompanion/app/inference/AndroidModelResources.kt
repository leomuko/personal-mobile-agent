package dev.edgecompanion.app.inference

import android.app.ActivityManager
import android.content.Context
import android.os.PowerManager
import dev.edgecompanion.core.inference.ResourceSnapshot

class AndroidModelResources(private val context: Context) {
    fun snapshot(): ResourceSnapshot {
        val memory = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val thermal = try { context.getSystemService(PowerManager::class.java).currentThermalStatus }
            catch (_: RuntimeException) { null }
        return ResourceSnapshot(memory.totalMem, memory.availMem, memory.lowMemory, thermal)
    }
}
