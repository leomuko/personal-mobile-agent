package dev.edgecompanion.core.inference

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class V4FreezeProfileTest {
    @Test fun exportExplicitSharedPolicyForHostFreeze() {
        val pressure = when (System.getenv("P2_V4_PROFILE") ?: "baseline") {
            "baseline" -> false
            "android-pressure" -> true
            else -> error("Unknown V4 profile")
        }
        val profile = if (pressure) MemoryPolicies.AndroidPressureTest else MemoryPolicies.Current
        assertEquals(if (pressure) "memory-android-pressure-test-v1" else "memory-baseline-v1", profile.id)
        assertEquals(!pressure, profile.enforceAvailableMemoryFloor)
        val json = profile.exportFields().entries.joinToString(",", "{", "}\n") { (key, value) ->
            "\"$key\":" + if (value is String) "\"$value\"" else value.toString()
        }
        System.getenv("P2_V4_PROFILE_OUTPUT")?.let { File(it).writeText(json) }
    }
}
