package dev.edgecompanion.core.storage.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.edgecompanion.db.CompanionDatabase
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** Key loss is an error: existing personal data is never silently replaced. */
class DatabaseKeyVault(private val context: Context, private val name: String) {
    private val alias = "dev.edgecompanion.database.$name"
    private val wrapped = AtomicFile(File(context.noBackupFilesDir, "$name.key"))

    @Synchronized fun key(): ByteArray {
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (wrapped.baseFile.exists()) {
            val master = keys.getKey(alias, null) as? SecretKey ?: error("Database key unavailable")
            val envelope = wrapped.readFully()
            require(envelope.size == 61 && envelope[0] == 1.toByte()) { "Invalid key envelope" }
            return Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, master, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
                doFinal(envelope.copyOfRange(13, envelope.size))
            }
        }
        check(!context.getDatabasePath(name).exists()) { "Existing database has no key" }
        val master = (keys.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
        ).run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, master) }
        val bytes = byteArrayOf(1) + cipher.iv + cipher.doFinal(raw)
        val stream = wrapped.startWrite()
        try { stream.write(bytes); wrapped.finishWrite(stream) }
        catch (error: Exception) { wrapped.failWrite(stream); raw.fill(0); throw error }
        return raw
    }
}

object EncryptedDatabaseFactory {
    fun open(context: Context, name: String = "companion.db"): AndroidSqliteDriver {
        System.loadLibrary("sqlcipher")
        val password = DatabaseKeyVault(context, name).key()
        return AndroidSqliteDriver(
            schema = CompanionDatabase.Schema,
            context = context,
            name = name,
            factory = SupportOpenHelperFactory(password),
        )
    }
}

