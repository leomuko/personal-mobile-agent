package dev.edgecompanion.app

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import androidx.test.core.app.ApplicationProvider
import androidx.test.filters.SdkSuppress
import com.google.crypto.tink.integration.android.AndroidKeystore
import dev.edgecompanion.core.sources.storage.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Feasibility fixture only: untrusted provider PDFs are not enabled by this test. */
@SdkSuppress(minSdkVersion = 35)
class ProtectedPdfAccessTest {
    private val binding = DocumentBinding("source", "revision")
    private fun fixture(block: suspend (Context, File, TinkDocumentFiles) -> Unit) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val namespace = "pdf-test-${UUID.randomUUID()}"
        val root = File(context.noBackupFilesDir, namespace)
        val vault = DocumentKeyVault(context, namespace)
        try { block(context, root, TinkDocumentFiles(root, vault.streamingAead())) }
        finally {
            root.deleteRecursively()
            AndroidKeystore.deleteKey("dev.edgecompanion.$namespace.streaming-key-v1")
        }
    }

    private fun pdf(): ByteArray = ByteArrayOutputStream().use { output ->
        val document = PdfDocument()
        try {
            for (page in 1..2) {
                val current = document.startPage(PdfDocument.PageInfo.Builder(600, 800, page).create())
                current.canvas.drawText("Synthetic page $page: pilot launch 6 October.", 40f, 80f,
                    Paint().apply { textSize = 20f })
                document.finishPage(current)
            }
            document.writeTo(output)
        } finally { document.close() }
        output.toByteArray()
    }

    private fun input(bytes: ByteArray) = object : DocumentInput {
        val stream = ByteArrayInputStream(bytes)
        override val declaredBytes = bytes.size.toLong()
        override fun read(buffer: ByteArray) = stream.read(buffer)
        override fun close() = stream.close()
    }

    @Test fun nativePdfParserExtractsPagesThroughAuthenticatedSeekableFd() = fixture { context, root, files ->
        val bytes = pdf()
        files.write(binding, input(bytes))
        ProtectedDocumentDescriptor.open(context, files, binding, bytes.size.toLong()).use { protected ->
            PdfRenderer(protected.descriptor).use { renderer ->
                assertEquals(2, renderer.pageCount)
                for (index in 0..1) renderer.openPage(index).use { page ->
                    val text = page.textContents.joinToString(" ") { it.text }
                    assertTrue("Missing synthetic page text", text.contains("Synthetic page ${index + 1}"))
                    assertTrue(text.contains("6 October"))
                }
            }
        }
        assertTrue(root.listFiles()!!.all { it.extension == "enc" || (it.name == ".publication.lock" && it.length() == 0L) })
        // Both reader slots must be available after parser/descriptor closure.
        val first = files.openVerified(binding, bytes.size.toLong())
        val second = files.openVerified(binding, bytes.size.toLong())
        first.close(); second.close()
    }

    @Test fun corruptTailIsRejectedBeforeAParserCanReceiveItsDescriptor() = fixture { context, root, files ->
        val bytes = pdf()
        files.write(binding, input(bytes))
        val file = root.listFiles()!!.single { it.extension == "enc" && it.name != "keyset.enc" }
        val damaged = file.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        try {
            ProtectedDocumentDescriptor.open(context, files, binding, bytes.size.toLong()).close()
            fail("Authentication must complete before descriptor publication")
        } catch (failure: DocumentException) { assertEquals(DocumentError.INTEGRITY, failure.error) }
    }
}
