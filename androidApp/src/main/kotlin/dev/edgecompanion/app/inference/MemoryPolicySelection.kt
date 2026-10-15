package dev.edgecompanion.app.inference

import dev.edgecompanion.core.inference.MemoryPolicies
import dev.edgecompanion.core.inference.MemoryPolicy

internal fun selectMemoryPolicy(debug: Boolean, buildType: String, androidPressureTest: Boolean): MemoryPolicy {
    return if (debug && buildType == "secureQa" && androidPressureTest) MemoryPolicies.AndroidPressureTest
        else MemoryPolicies.Current
}
