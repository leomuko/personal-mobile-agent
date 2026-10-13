package dev.edgecompanion.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import dev.edgecompanion.core.inference.*
import dev.edgecompanion.core.inference.assets.PrivateModelAssets
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import androidx.test.platform.app.InstrumentationRegistry

class ModelAssetsTest {
    private val bytes = "Synthetic model bytes; not a runnable model".toByteArray()
    private val manifest = ModelCatalog.gemma4.copy(bytes = bytes.size.toLong(),
        sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private fun source(content: ByteArray, reportedSize: Long? = content.size.toLong()) = ModelSource {
        val input = ByteArrayInputStream(content)
        object : ModelReader {
            override val size = reportedSize
            override fun read(buffer: ByteArray) = input.read(buffer)
            override fun close() = input.close()
        }
    }
    private fun root() = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "model-test-${UUID.randomUUID()}")

    @Test fun compiledCatalogMatchesTrustedPlanningPin() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets
            .open("gemma-4-e2b-it.json").bufferedReader().use { JSONObject(it.readText()) }
        val artifact = json.getJSONObject("artifact")
        val configuration = json.getJSONObject("initialConfiguration")
        val catalog = ModelCatalog.gemma4
        assertEquals(json.getString("modelId"), catalog.id)
        assertEquals(artifact.getString("revision"), catalog.revision)
        assertEquals(artifact.getString("file"), catalog.fileName)
        assertEquals(artifact.getLong("bytes"), catalog.bytes)
        assertEquals(artifact.getString("sha256"), catalog.sha256)
        assertEquals(artifact.getString("license"), ModelCatalog.LICENSE)
        assertEquals(artifact.getInt("artifactMaxContextTokens"), catalog.artifactContextTokens)
        assertEquals(configuration.getInt("configuredContextTokens"), catalog.configuredContextTokens)
        assertEquals(configuration.getInt("maxOutputTokens"), catalog.maxOutputTokens)
        assertEquals("com.google.ai.edge.litertlm:litertlm-android:${ModelCatalog.RUNTIME_VERSION}",
            json.getJSONObject("runtime").getString("mavenCoordinate"))
    }

    @Test fun verifiedImportSurvivesNewStoreAndOrphanCleanup() = runBlocking {
        val root = root()
        try {
            val store = PrivateModelAssets(root, manifest) { Long.MAX_VALUE }
            store.install(source(bytes))
            assertEquals(AssetStatus.INSTALLED, store.state.value.status)
            File(root, "staging-interrupted").writeText("incomplete")
            val restored = PrivateModelAssets(root, manifest)
            assertArrayEquals(bytes, File(restored.verify()!!).readBytes())
            assertFalse(File(root, "staging-interrupted").exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun oversizedUnknownLengthSourceIsRejectedWithoutPromotion() = runBlocking {
        val root = root()
        try {
            val store = PrivateModelAssets(root, manifest) { Long.MAX_VALUE }
            try { store.install(source(bytes + byteArrayOf(1), null)); fail("Expected bounded-copy rejection") }
            catch (failure: ModelException) { assertEquals(ModelError.SIZE_MISMATCH, failure.error) }
            assertNull(store.verify())
            assertTrue(root.listFiles()!!.none { it.name.startsWith("staging-") })
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptImportPreservesPreviousVerifiedArtifact() = runBlocking {
        val root = root()
        try {
            val store = PrivateModelAssets(root, manifest) { Long.MAX_VALUE }
            store.install(source(bytes))
            try { store.install(source(ByteArray(bytes.size))); fail("Expected hash failure") }
            catch (failure: ModelException) { assertEquals(ModelError.HASH_MISMATCH, failure.error) }
            assertArrayEquals(bytes, File(store.verify()!!).readBytes())
        } finally { root.deleteRecursively() }
    }
    @Test fun storagePreflightRejectsBeforeReading() = runBlocking {
        val root = root()
        try {
            val store = PrivateModelAssets(root, manifest) { manifest.bytes }
            try { store.install(source(bytes)); fail("Expected space failure") }
            catch (failure: ModelException) { assertEquals(ModelError.STORAGE_FULL, failure.error) }
            assertNull(store.verify())
        } finally { root.deleteRecursively() }
    }
    @Test fun changedInstalledBytesRequireRepair() = runBlocking {
        val root = root()
        try {
            val store = PrivateModelAssets(root, manifest) { Long.MAX_VALUE }
            store.install(source(bytes))
            File(store.verify()!!).writeBytes(ByteArray(bytes.size))
            try { store.verify(); fail("Expected corruption") }
            catch (failure: ModelException) { assertEquals(ModelError.HASH_MISMATCH, failure.error) }
            assertEquals(AssetStatus.REPAIR_REQUIRED, store.state.value.status)
            store.install(source(bytes))
            assertNotNull(store.verify())
        } finally { root.deleteRecursively() }
    }
    @Test fun cancellationClosesReaderAndRemovesStaging() = runBlocking {
        val root = root()
        var closed = false
        try {
            val store = PrivateModelAssets(root, manifest) { Long.MAX_VALUE }
            val import = launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]!!
                store.install(ModelSource {
                    object : ModelReader {
                        override val size: Long? = null
                        override fun read(buffer: ByteArray): Int {
                            buffer[0] = bytes[0]
                            owner.cancel()
                            return 1
                        }
                        override fun close() { closed = true }
                    }
                })
            }
            import.start(); import.join()
            assertTrue(closed)
            assertEquals(AssetStatus.NOT_INSTALLED, store.state.value.status)
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun diskFullAfterPreflightPreservesInstalledModel() = runBlocking {
        val root = root()
        var free = Long.MAX_VALUE
        var closed = false
        try {
            val store = PrivateModelAssets(root, manifest) { free }
            store.install(source(bytes))
            try {
                store.install(ModelSource { object : ModelReader {
                    override val size = manifest.bytes
                    override fun read(buffer: ByteArray): Int { free = 0; throw IOException("Synthetic disk full") }
                    override fun close() { closed = true }
                } })
                fail("Expected failed stream")
            } catch (failure: ModelException) { assertEquals(ModelError.STORAGE_FULL, failure.error) }
            assertTrue(closed)
            assertArrayEquals(bytes, File(store.verify()!!).readBytes())
            assertTrue(root.listFiles()!!.none { it.name.startsWith("staging-") })
        } finally { root.deleteRecursively() }
    }
}
