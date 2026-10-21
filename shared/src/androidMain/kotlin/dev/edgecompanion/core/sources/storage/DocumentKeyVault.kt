package dev.edgecompanion.core.sources.storage

import android.content.Context
import android.util.AtomicFile
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.integration.android.AndroidKeystore
import com.google.crypto.tink.streamingaead.PredefinedStreamingAeadParameters
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import java.io.File

/** No cleartext keyset fallback. Missing/unusable keys never trigger personal-data reset. */
class DocumentKeyVault(context: Context, namespace: String = "sources-v1") {
    init { require(namespace.matches(Regex("[a-zA-Z0-9-]{1,80}"))) }
    private val root = File(context.noBackupFilesDir, namespace)
    private val wrapped = AtomicFile(File(root, "keyset.enc"))
    private val alias = "dev.edgecompanion.$namespace.streaming-key-v1"
    private val binding = "dev.edgecompanion/$namespace/streaming-keyset/v1".toByteArray(Charsets.UTF_8)

    fun streamingAead(): StreamingAead = synchronized(KEYSET_LOCK) { load() }

    private fun load(): StreamingAead {
        try {
            StreamingAeadConfig.register()
            val exists = wrapped.baseFile.exists() || File(wrapped.baseFile.path + ".bak").exists()
            val handle = if (exists) {
                if (!AndroidKeystore.hasKey(alias)) throw DocumentException(DocumentError.KEY_UNAVAILABLE)
                val bytes = wrapped.openRead().use { input ->
                    val buffer = ByteArray(MAX_KEYSET_BYTES + 1)
                    var count = 0
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        if (read == 0) throw DocumentException(DocumentError.KEY_UNAVAILABLE)
                        count += read
                    }
                    if (count == 0 || count > MAX_KEYSET_BYTES) throw DocumentException(DocumentError.KEY_UNAVAILABLE)
                    buffer.copyOf(count)
                }
                TinkProtoKeysetFormat.parseEncryptedKeyset(bytes, AndroidKeystore.getAead(alias), binding)
            } else {
                val entries = if (root.exists()) root.listFiles()
                    ?: throw DocumentException(DocumentError.STORAGE) else emptyArray()
                if (entries.any { it.name.endsWith(".enc") || it.name.startsWith("staging-") })
                    throw DocumentException(DocumentError.KEY_UNAVAILABLE)
                if (!root.isDirectory && !root.mkdirs()) throw DocumentException(DocumentError.STORAGE)
                if (!AndroidKeystore.hasKey(alias)) AndroidKeystore.generateNewAes256GcmKey(alias)
                val generated = KeysetHandle.generateNew(PredefinedStreamingAeadParameters.AES128_GCM_HKDF_1MB)
                val bytes = TinkProtoKeysetFormat.serializeEncryptedKeyset(generated, AndroidKeystore.getAead(alias), binding)
                val output = wrapped.startWrite()
                try { output.write(bytes); wrapped.finishWrite(output) }
                catch (failure: Exception) { wrapped.failWrite(output); throw failure }
                generated
            }
            return handle.getPrimitive(RegistryConfiguration.get(), StreamingAead::class.java)
        } catch (failure: DocumentException) { throw failure }
        catch (failure: Exception) { throw DocumentException(DocumentError.KEY_UNAVAILABLE, failure) }
    }

    companion object {
        private const val MAX_KEYSET_BYTES = 16 * 1024
        private val KEYSET_LOCK = Any()
    }
}
