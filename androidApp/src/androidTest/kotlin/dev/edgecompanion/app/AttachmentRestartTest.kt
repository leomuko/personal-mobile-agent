package dev.edgecompanion.app

import android.graphics.Bitmap
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import dev.edgecompanion.core.storage.files.EncryptedAttachmentStore
import dev.edgecompanion.db.CompanionDatabase
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore

/** Pass restartPhase=seed/verify in separate instrumentation processes for a cold-reopen check. */
class AttachmentRestartTest {
    @Test fun encryptedDraftAndSentPreviewReferencesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".secureqa"))
        val phase = InstrumentationRegistry.getArguments().getString("restartPhase")
        val databaseName = "qa-attachment-restart.db"
        val mediaName = "qa-attachment-restart"
        val files = EncryptedAttachmentStore(context, mediaName)
        fun clean() {
            context.deleteDatabase(databaseName)
            File(context.noBackupFilesDir, "$databaseName.key").delete()
            File(context.noBackupFilesDir, mediaName).deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null); deleteEntry("dev.edgecompanion.database.$databaseName"); deleteEntry("dev.edgecompanion.$mediaName")
            }
        }
        if (phase != "verify") {
            clean()
            val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.GREEN) }
            val image = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
            bitmap.recycle()
            val driver = EncryptedDatabaseFactory.open(context, databaseName)
            try {
                val store = SqlConversationStore(CompanionDatabase(driver))
                files.write("sent", image); store.reserve(Attachment("sent", "sent.png", "image/png")); store.complete("sent", image.size.toLong())
                store.append("Sent prompt", 42)
                listOf("camera", "gallery", "file").forEach {
                    files.write(it, image)
                    store.reserve(Attachment(it, "$it.png", "image/png")); store.complete(it, image.size.toLong())
                }
                store.saveDraft("Unsent prompt from ${Process.myPid()}")
            } finally { driver.close(); image.fill(0) }
        }
        if (phase == "seed") return
        try {
            val driver = EncryptedDatabaseFactory.open(context, databaseName)
            try {
                val store = SqlConversationStore(CompanionDatabase(driver))
                assertTrue(store.draft().startsWith("Unsent prompt from "))
                if (phase == "verify") assertNotEquals("Unsent prompt from ${Process.myPid()}", store.draft())
                assertEquals("Sent prompt", store.messages().single().text)
                assertEquals(listOf("camera", "gallery", "file"), store.attachments().filter { it.messageId == null }.map { it.id })
                assertNotNull(store.attachments().first { it.id == "sent" }.messageId)
                store.attachments().forEach { item ->
                    val bytes = files.read(item.id)
                    assertEquals(item.size, bytes.size.toLong())
                    assertNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                    bytes.fill(0)
                }
            } finally { driver.close() }
        } finally { clean() }
    }
}
