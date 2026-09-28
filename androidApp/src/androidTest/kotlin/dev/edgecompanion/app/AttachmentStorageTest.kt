package dev.edgecompanion.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.storage.files.EncryptedAttachmentStore
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.util.UUID

class AttachmentStorageTest {
    @Test fun encryptedBytesReopenAndCorruptionFailsWithoutReplacingOriginal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-attachments-${UUID.randomUUID()}"
        val folder = File(context.noBackupFilesDir, name)
        val store = EncryptedAttachmentStore(context, name)
        val payload = "SYNTHETIC PRIVATE ATTACHMENT".toByteArray()
        try {
            store.write("fixture", payload)
            val disk = File(folder, "fixture").readBytes()
            assertFalse(disk.contentEquals(payload))
            assertFalse(String(disk).contains("SYNTHETIC PRIVATE"))
            assertArrayEquals(payload, EncryptedAttachmentStore(context, name).read("fixture"))
            disk[disk.lastIndex] = (disk.last().toInt() xor 1).toByte()
            File(folder, "fixture").writeBytes(disk)
            assertTrue(runCatching { store.read("fixture") }.isFailure)
            assertTrue(store.contains("fixture"))
            assertTrue(runCatching { store.read("../outside") }.isFailure)
            store.remove("fixture"); assertFalse(store.contains("fixture"))
        } finally {
            folder.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("dev.edgecompanion.$name") }
        }
    }

    @Test fun lostKeyCannotSilentlyCreateNewStoreOverExistingAttachments() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "qa-attachments-${UUID.randomUUID()}"
        val folder = File(context.noBackupFilesDir, name)
        try {
            val store = EncryptedAttachmentStore(context, name)
            store.write("fixture", byteArrayOf(1, 2, 3))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry("dev.edgecompanion.$name") }
            assertTrue(runCatching { store.read("fixture") }.isFailure)
            assertTrue(runCatching { store.write("second", byteArrayOf(4)) }.isFailure)
            assertTrue(store.contains("fixture"))
        } finally { folder.deleteRecursively() }
    }
}
