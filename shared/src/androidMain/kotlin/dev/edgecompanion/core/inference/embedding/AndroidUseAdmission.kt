package dev.edgecompanion.core.inference.embedding

import java.io.File
import java.io.RandomAccessFile
import java.io.FileInputStream
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Internal tooling boundary. No DI registration, download, or release capability is added. */
class AndroidUseAdmission private constructor(private val admission: UseAdmission) {
    suspend fun admit(input: EncoderInput): EncoderAdmission = withContext(Dispatchers.Default) { admission.admit(input) }
    fun matches(receipt: EncoderAdmissionReceipt, input: EncoderInput) = admission.matches(receipt, input)

    companion object {
        suspend fun open(model: File, internalBuild: Boolean, transport: (String) -> ByteArray): AndroidUseAdmission = withContext(Dispatchers.IO) {
            FileInputStream(model).use { open(it, internalBuild, transport) }
        }
        suspend fun open(descriptor: ParcelFileDescriptor, internalBuild: Boolean, transport: (String) -> ByteArray): AndroidUseAdmission = withContext(Dispatchers.IO) {
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { open(it, internalBuild, transport) }
        }
        private suspend fun open(input: FileInputStream, internalBuild: Boolean, transport: (String) -> ByteArray): AndroidUseAdmission {
            check(internalBuild) { "Encoder admission is internal-only" }
            val context = currentCoroutineContext()
            val config = input.let { file ->
                require(file.channel.size() == 6_120_274L)
                file.channel.position(0)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65_536)
                try {
                    while (true) {
                        context.ensureActive()
                        val count = file.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                    require(hex(digest.digest()) == UseAdmission.ARTIFACT_SHA256)
                    // Offset is tied to the complete artifact hash and checked by the graph verifier.
                    file.channel.position(5_584_856L)
                    ByteArray(384_344).also { bytes ->
                        val target = ByteBuffer.wrap(bytes)
                        while (target.hasRemaining()) { context.ensureActive(); check(file.channel.read(target) > 0) }
                    }
                } finally { buffer.fill(0) }
            }
            val sha256: (ByteArray) -> String = { hex(MessageDigest.getInstance("SHA-256").digest(it)) }
            return try { AndroidUseAdmission(UseAdmission(UseTokenizer.open(config, sha256), transport, sha256)) }
            finally { config.fill(0) }
        }
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
