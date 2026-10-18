package dev.edgecompanion.app

import dev.edgecompanion.app.support.SupervisionOwnership
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SupervisionOwnershipTest {
    @Test fun handoffIsRejectedUntilThePreviousOwnerReleasesItsLock() {
        val root = Files.createTempDirectory("supervision-owner-").toFile()
        try {
            SupervisionOwnership.acquire(root).use {
                val failure = runCatching { SupervisionOwnership.acquire(root).close() }.exceptionOrNull()
                assertNotNull("An active/retiring run must exclude a new owner", failure)
            }
            SupervisionOwnership.acquire(root).use { assertTrue(root.resolve("owner.lock").exists()) }
        } finally { root.deleteRecursively() }
    }

    @Test fun closingAnOldOwnerAgainCannotReleaseItsSuccessor() {
        val root = Files.createTempDirectory("supervision-owner-").toFile()
        try {
            val old = SupervisionOwnership.acquire(root)
            old.close()
            SupervisionOwnership.acquire(root).use {
                old.close()
                assertTrue(runCatching { SupervisionOwnership.acquire(root).close() }.isFailure)
            }
        } finally { root.deleteRecursively() }
    }
}
