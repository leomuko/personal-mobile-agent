package dev.edgecompanion.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.crypto.tink.integration.android.AndroidKeystore
import dev.edgecompanion.core.sources.storage.DocumentBinding
import dev.edgecompanion.core.sources.storage.DocumentError
import dev.edgecompanion.core.sources.storage.DocumentException
import dev.edgecompanion.core.sources.storage.DocumentInput
import dev.edgecompanion.core.sources.storage.DocumentKeyVault
import dev.edgecompanion.core.sources.storage.TinkDocumentFiles
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic namespaces only. No application key, model, database or user document is changed. */
class ProtectedDocumentKeyTest {
    @Test fun applicationDoesNotAcquireNetworkPermissionFromCandidateLibraries() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains(android.Manifest.permission.INTERNET))
        assertFalse(permissions.contains(android.Manifest.permission.ACCESS_NETWORK_STATE))
    }

    private fun fixture(block: suspend (Context, String, File, String) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "document-test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, namespace)
        val alias = "dev.edgecompanion.$namespace.streaming-key-v1"
        try { block(context, namespace, root, alias) }
        finally {
            root.deleteRecursively()
            if (AndroidKeystore.hasKey(alias)) AndroidKeystore.deleteKey(alias)
        }
    }

    private fun input(bytes: ByteArray) = object : DocumentInput {
        private val source = ByteArrayInputStream(bytes)
        override val declaredBytes = bytes.size.toLong()
        override fun read(buffer: ByteArray) = source.read(buffer)
        override fun close() = source.close()
    }

    @Test fun encryptedKeysetReopensProtectedContent() = fixture { context, namespace, root, _ ->
        val binding = DocumentBinding("source", "revision")
        val bytes = "Synthetic protected document, not personal data".toByteArray()
        val first = TinkDocumentFiles(root, DocumentKeyVault(context, namespace).streamingAead())
        first.write(binding, input(bytes))
        val second = TinkDocumentFiles(root, DocumentKeyVault(context, namespace).streamingAead())
        val reader = second.openVerified(binding, bytes.size.toLong())
        try {
            val restored = ByteArray(bytes.size)
            var count = 0
            while (count < restored.size) count += reader.read(restored, count, restored.size - count)
            assertArrayEquals(bytes, restored)
        } finally { reader.close() }
        assertTrue(File(root, "keyset.enc").length() in 1..16384)
        assertTrue(root.listFiles()!!.all { it.extension == "enc" || (it.name == ".publication.lock" && it.length() == 0L) })
    }

    @Test fun missingMasterKeyCannotReplaceEncryptedKeyset() = fixture { context, namespace, root, alias ->
        DocumentKeyVault(context, namespace).streamingAead()
        val original = File(root, "keyset.enc").readBytes()
        AndroidKeystore.deleteKey(alias)
        try { DocumentKeyVault(context, namespace).streamingAead(); fail("Must not recreate a lost key") }
        catch (failure: DocumentException) { assertEquals(DocumentError.KEY_UNAVAILABLE, failure.error) }
        assertFalse(AndroidKeystore.hasKey(alias))
        assertArrayEquals(original, File(root, "keyset.enc").readBytes())
    }

    @Test fun missingOrCorruptKeysetPreservesDocuments() = fixture { context, namespace, root, _ ->
        val binding = DocumentBinding("source", "revision")
        val store = TinkDocumentFiles(root, DocumentKeyVault(context, namespace).streamingAead())
        store.write(binding, input(byteArrayOf(1, 2, 3)))
        val document = root.listFiles()!!.single { it.extension == "enc" && it.name != "keyset.enc" }
        val original = document.readBytes()
        File(root, "keyset.enc").writeBytes(byteArrayOf(9, 8, 7))
        try { DocumentKeyVault(context, namespace).streamingAead(); fail("Corrupt keyset must fail closed") }
        catch (failure: DocumentException) { assertEquals(DocumentError.KEY_UNAVAILABLE, failure.error) }
        File(root, "keyset.enc").delete()
        try { DocumentKeyVault(context, namespace).streamingAead(); fail("Existing content must prevent keyset reset") }
        catch (failure: DocumentException) { assertEquals(DocumentError.KEY_UNAVAILABLE, failure.error) }
        assertArrayEquals(original, document.readBytes())
    }
}
