package dev.edgecompanion.core.storage.files

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.core.attachments.model.AttachmentLimits
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Independent authenticated envelopes; neither names nor media are written in plaintext. */
class EncryptedAttachmentStore(context: Context, private val namespace: String = "attachments-v1") : AttachmentFiles {
    private val directory = File(context.noBackupFilesDir, namespace)
    private val alias = "dev.edgecompanion.$namespace"
    private fun file(id: String): File {
        require(id.matches(Regex("[a-zA-Z0-9-]{1,80}")))
        return File(directory, id)
    }

    @Synchronized private fun key(): SecretKey {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keys.getKey(alias, null) as? SecretKey)?.let { return it }
        check(directory.listFiles().isNullOrEmpty()) { "Attachment key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    override fun write(id: String, bytes: ByteArray) {
        require(bytes.isNotEmpty() && bytes.size <= AttachmentLimits.ITEM_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        check(directory.exists() || directory.mkdirs())
        val output = AtomicFile(file(id))
        val stream = output.startWrite()
        try {
            stream.write(byteArrayOf(1)); stream.write(cipher.iv); stream.write(cipher.doFinal(bytes))
            output.finishWrite(stream)
        } catch (error: Exception) { output.failWrite(stream); throw error }
    }

    override fun read(id: String): ByteArray {
        val input = file(id)
        require(input.length() in 30..(AttachmentLimits.ITEM_BYTES + 29))
        val bytes = AtomicFile(input).readFully()
        require(bytes[0] == 1.toByte())
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(id.toByteArray(Charsets.UTF_8))
            doFinal(bytes, 13, bytes.size - 13)
        }
    }

    override fun contains(id: String) = file(id).exists()
    override fun remove(id: String) = AtomicFile(file(id)).delete()
    override fun retain(ids: Set<String>) {
        directory.listFiles()?.forEach { entry ->
            if (entry.name.removeSuffix(".bak").removeSuffix(".new") !in ids) entry.delete()
        }
    }
}
