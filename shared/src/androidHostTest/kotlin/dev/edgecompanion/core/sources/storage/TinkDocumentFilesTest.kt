package dev.edgecompanion.core.sources.storage

import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext

class TinkDocumentFilesTest {
    private val binding = DocumentBinding("source", "revision")
    private fun documents(root: File) = root.listFiles()!!.filter { it.extension == "enc" }

    private fun cipher(): StreamingAead {
        StreamingAeadConfig.register()
        return KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES128_GCM_HKDF_1MB)
            .getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
    }

    private fun fixture(block: suspend (File, TinkDocumentFiles) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("protected-document-test").toFile()
        try { block(root, TinkDocumentFiles(root, cipher())) } finally { root.deleteRecursively() }
    }

    @Test fun authenticatedRoundTripAndSeekAcrossSegments() = fixture { _, store ->
        val bytes = ByteArray(3 * 1024 * 1024 + 91) { (it * 31).toByte() }
        val input = Input(bytes)
        val saved = store.write(binding, input)
        assertTrue(input.closed)
        assertEquals(bytes.size.toLong(), saved.plaintextBytes)
        assertTrue(saved.storedBytes > saved.plaintextBytes)
        val reader = store.openVerified(binding, saved.plaintextBytes)
        try {
            for (position in listOf(0, 1024 * 1024 - 33, 2 * 1024 * 1024 - 10, bytes.size - 25)) {
                reader.seek(position.toLong())
                val expected = minOf(75, bytes.size - position)
                val buffer = ByteArray(expected)
                var read = 0
                while (read < expected) read += reader.read(buffer, read, expected - read)
                assertContentEquals(bytes.copyOfRange(position, position + expected), buffer)
            }
            assertFailsWith<IllegalArgumentException> { reader.read(ByteArray(65537)) }
            assertFailsWith<IllegalArgumentException> { reader.seek(-1) }
        } finally { reader.close(); reader.close() }
    }

    @Test fun allAuthenticationFailuresHappenBeforeReaderPublication() = fixture { root, store ->
        val bytes = ByteArray(3 * 1024 * 1024 + 100) { (it * 17).toByte() }
        store.write(binding, Input(bytes))
        val file = documents(root).single()
        val original = file.readBytes()
        suspend fun rejected(content: ByteArray) {
            file.writeBytes(content)
            assertEquals(DocumentError.INTEGRITY,
                assertFailsWith<DocumentException> { store.openVerified(binding, bytes.size.toLong()) }.error)
        }
        rejected(original.copyOf().apply { this[4] = 7 })
        rejected(original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        rejected(original.copyOf(original.size - 1))
        val segment = 1024 * 1024
        rejected(original.copyOf().apply {
            original.copyInto(this, 16 + segment, 16 + 2 * segment, 16 + 3 * segment)
            original.copyInto(this, 16 + 2 * segment, 16 + segment, 16 + 2 * segment)
        })
        rejected(original.copyOf().apply {
            original.copyInto(this, 16 + 2 * segment, 16 + segment, 16 + 2 * segment)
        })
        file.writeBytes(original)
        val otherKeyStore = TinkDocumentFiles(root, cipher())
        assertEquals(DocumentError.INTEGRITY, assertFailsWith<DocumentException> {
            otherKeyStore.openVerified(binding, bytes.size.toLong())
        }.error)
        for (other in listOf(DocumentBinding("source", "other-revision"), DocumentBinding("other-source", "revision"))) {
            val before = root.listFiles()!!.toSet()
            store.write(other, Input(bytes))
            root.listFiles()!!.single { it !in before }.writeBytes(original)
            assertEquals(DocumentError.INTEGRITY, assertFailsWith<DocumentException> {
                store.openVerified(other, bytes.size.toLong())
            }.error)
        }
        store.openVerified(binding, bytes.size.toLong()).close()
    }

    @Test fun missingFilesAndFailedVerificationDoNotLeakReaderSlots() = fixture { _, store ->
        repeat(4) {
            assertEquals(DocumentError.MISSING, assertFailsWith<DocumentException> {
                store.openVerified(binding, 1)
            }.error)
        }
        store.write(binding, Input(byteArrayOf(1, 2, 3)))
        repeat(4) {
            assertEquals(DocumentError.INTEGRITY, assertFailsWith<DocumentException> {
                store.openVerified(binding, 2)
            }.error)
        }
        val first = store.openVerified(binding, 3)
        val second = store.openVerified(binding, 3)
        assertEquals(DocumentError.BUSY, assertFailsWith<DocumentException> { store.openVerified(binding, 3) }.error)
        first.close(); first.close()
        store.openVerified(binding, 3).close()
        second.close()
    }

    @Test fun bindingNamesCannotCollideAndExistingRevisionsAreImmutable() = fixture { root, store ->
        val first = DocumentBinding("a-b", "c")
        val second = DocumentBinding("a", "b-c")
        store.write(first, Input(byteArrayOf(1)))
        store.write(second, Input(byteArrayOf(2)))
        assertEquals(2, documents(root).size)
        val input = Input(byteArrayOf(3))
        assertEquals(DocumentError.BUSY, assertFailsWith<DocumentException> { store.write(first, input) }.error)
        assertTrue(input.closed)
        val reader = store.openVerified(first, 1)
        val value = ByteArray(1)
        reader.read(value); reader.close()
        assertEquals(1, value[0].toInt())
    }

    @Test fun simultaneousStoreInstancesCannotReplaceAPublishedRevision() = runBlocking {
        val root = Files.createTempDirectory("document-publish-race").toFile()
        val aead = cipher()
        val stores = listOf(TinkDocumentFiles(root, aead), TinkDocumentFiles(root, aead))
        val copying = CountDownLatch(2)
        try {
            val results = stores.mapIndexed { index, store -> async(Dispatchers.IO) {
                val input = object : DocumentInput {
                    override val declaredBytes = 1L
                    var supplied = false
                    override fun read(buffer: ByteArray): Int {
                        if (supplied) return -1
                        supplied = true
                        copying.countDown()
                        check(copying.await(5, TimeUnit.SECONDS))
                        buffer[0] = index.toByte()
                        return 1
                    }
                    override fun close() = Unit
                }
                index to runCatching { store.write(binding, input) }
            } }.map { it.await() }
            val winner = results.single { it.second.isSuccess }.first
            assertEquals(DocumentError.BUSY, (results.single { it.second.isFailure }.second.exceptionOrNull() as DocumentException).error)
            val reader = stores.first().openVerified(binding, 1)
            val value = ByteArray(1)
            reader.read(value); reader.close()
            assertEquals(winner, value[0].toInt())
            assertEquals(1, documents(root).size)
            assertTrue(root.listFiles()!!.none { it.name.startsWith("staging-") })
        } finally { root.deleteRecursively() }
    }

    @Test fun unknownAndFalseSizesAreBoundedAndStagingIsRemoved() = fixture { root, store ->
        val oversized = object : DocumentInput {
            override val declaredBytes = 1L
            var emitted = 0L
            var closed = false
            override fun read(buffer: ByteArray): Int {
                if (emitted > DocumentLimits.FILE_BYTES) return -1
                buffer.fill(1); emitted += buffer.size; return buffer.size
            }
            override fun close() { closed = true }
        }
        assertEquals(DocumentError.LIMIT, assertFailsWith<DocumentException> { store.write(binding, oversized) }.error)
        assertTrue(oversized.closed)
        assertTrue(root.listFiles()!!.isEmpty())
        store.write(binding, Input(byteArrayOf(4), null))
        store.openVerified(binding, 1).close()
    }

    @Test fun cancelledAndBrokenProvidersLeaveNoStagingFiles() = fixture { root, store ->
        for (failure in listOf(CancellationException("fixture"), java.io.IOException("fixture"))) {
            var closed = false
            val input = object : DocumentInput {
                override val declaredBytes: Long? = null
                override fun read(buffer: ByteArray): Int = throw failure
                override fun close() { closed = true }
            }
            assertFailsWith<Exception> { store.write(binding, input) }
            assertTrue(closed)
            assertTrue(root.listFiles()!!.isEmpty())
        }
    }

    @Test fun cancellationWhileWaitingForWriterStillClosesInput() = fixture { _, store ->
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val firstInput = object : DocumentInput {
            override val declaredBytes: Long? = null
            override fun read(buffer: ByteArray): Int {
                entered.countDown()
                check(unblock.await(5, TimeUnit.SECONDS))
                throw java.io.IOException("synthetic interrupted provider")
            }
            override fun close() = Unit
        }
        kotlinx.coroutines.coroutineScope {
            val first = launch {
                assertFailsWith<DocumentException> { store.write(binding, firstInput) }
            }
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                }
                val pending = Input(byteArrayOf(1))
                val second = launch(start = CoroutineStart.UNDISPATCHED) {
                    store.write(DocumentBinding("source", "second"), pending)
                }
                second.cancelAndJoin()
                assertTrue(pending.closed)
            } finally { unblock.countDown(); first.join() }
        }
    }

    @Test fun cancellationAtReaderDispatcherHandoffReturnsItsSlot() = runTest {
        val root = Files.createTempDirectory("document-handoff-test").toFile()
        val scheduled = StandardTestDispatcher(testScheduler)
        var owner: Job? = null
        var cancelHandoff = false
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                scheduled.dispatch(context, Runnable {
                    block.run()
                    // Run before the queued caller continuation, after verification publishes its reader.
                    if (cancelHandoff) owner?.cancel()
                })
            }
        }
        try {
            val store = TinkDocumentFiles(root, cipher(), io)
            store.write(binding, Input(byteArrayOf(1, 2, 3)))
            cancelHandoff = true
            owner = launch {
                store.openVerified(binding, 3)
                error("The cancelled handoff must not return a reader")
            }
            owner.join()
            assertTrue(owner.isCancelled)
            cancelHandoff = false
            val first = store.openVerified(binding, 3)
            val second = store.openVerified(binding, 3)
            first.close(); second.close()
        } finally { root.deleteRecursively() }
    }

    @Test fun subsequentSeekAuthenticatesSegmentsAgain() = fixture { root, store ->
        val bytes = ByteArray(3 * 1024 * 1024) { 8 }
        store.write(binding, Input(bytes))
        val reader = store.openVerified(binding, bytes.size.toLong())
        try {
            // Alter a different segment after the integrity pass, then force it into the reader.
            RandomAccessFile(documents(root).single(), "rw").use {
                it.seek(1024 * 1024L + 100); it.write(99)
            }
            reader.seek(1024 * 1024L + 100)
            assertEquals(DocumentError.INTEGRITY,
                assertFailsWith<DocumentException> { reader.read(ByteArray(100)) }.error)
        } finally { reader.close() }
        assertFalse(root.listFiles()!!.isEmpty())
    }

    private class Input(private val bytes: ByteArray, override val declaredBytes: Long? = bytes.size.toLong()) : DocumentInput {
        var closed = false
        private var position = 0
        override fun read(buffer: ByteArray): Int {
            if (position == bytes.size) return -1
            val count = minOf(buffer.size, bytes.size - position)
            bytes.copyInto(buffer, 0, position, position + count)
            position += count
            return count
        }
        override fun close() { closed = true }
    }
}
