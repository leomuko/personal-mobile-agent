package dev.edgecompanion.app

import dev.edgecompanion.app.inference.selectMemoryPolicy
import dev.edgecompanion.core.inference.MemoryPolicies
import org.junit.Assert.assertSame
import org.junit.Test

class MemoryPolicySelectionTest {
    @Test fun experimentRequiresAllThreeInternalBuildConditions() {
        for (debug in listOf(false, true)) {
            for (buildType in listOf("release", "debug", "secureQa")) {
                for (enabled in listOf(false, true)) {
                    val expected = if (debug && buildType == "secureQa" && enabled)
                        MemoryPolicies.AndroidPressureTest else MemoryPolicies.Current
                    assertSame(expected, selectMemoryPolicy(debug, buildType, enabled))
                }
            }
        }
    }
}
