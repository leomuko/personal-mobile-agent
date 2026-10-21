package dev.edgecompanion.app.prerequisites

import android.app.Service
import android.content.Intent
import android.os.*
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.text.textembedder.TextEmbedder
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import dev.edgecompanion.core.inference.embedding.*
import kotlinx.coroutines.runBlocking

/** QA-only candidate spike. A native call cannot outlive its isolated, request-owned process. */
class EmbeddingGateService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val admitted = AtomicBoolean()
    private val native = Executors.newSingleThreadExecutor()
    private var owner: String? = null
    private var deadline = 0L
    private var reply: Messenger? = null
    @Volatile private var peakPssKb = 0
    private var resourceUntil = 0L
    private val guard = object : Runnable {
        override fun run() {
            try { checkResources() }
            catch (failure: Exception) {
                // A diagnostic Binder send must never be in front of this independent stop.
                die()
            }
        }
        private fun checkResources() {
            peakPssKb = maxOf(peakPssKb, Debug.getPss().toInt())
            val now = SystemClock.elapsedRealtime()
            if (now >= deadline || now >= resourceUntil || peakPssKb > 384 * 1024) {
                die()
            } else main.postDelayed(this, 100)
        }
    }
    private val endpoint = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            if (message.what == 4) {
                val until = message.data.getLong("resourceUntil")
                val now = SystemClock.elapsedRealtime()
                if (message.data.getString("id") == owner && until in (now + 1)..(now + 500)) resourceUntil = until
                return
            }
            if (message.what == 2) {
                // This Binder endpoint belongs to one private isolated instance, even before Start.
                if (owner == null || message.data.getString("id") == owner) die()
                return
            }
            if (message.what != 1 || !admitted.compareAndSet(false, true)) return
            val data = message.data
            val id = data.getString("id") ?: return die()
            if (!id.matches(Regex("[a-zA-Z0-9-]{1,80}"))) return die()
            owner = id
            reply = message.replyTo ?: return die()
            val parent = data.getBinder("parent") ?: return die()
            try { parent.linkToDeath({ die() }, 0) } catch (_: Exception) { return die() }
            deadline = data.getLong("deadline")
            if (deadline <= SystemClock.elapsedRealtime() || deadline > SystemClock.elapsedRealtime() + 5000) return die()
            val descriptor = if (Build.VERSION.SDK_INT >= 33) data.getParcelable("model", ParcelFileDescriptor::class.java)
                else @Suppress("DEPRECATION") (data.getParcelable("model") as? ParcelFileDescriptor)
            if (descriptor == null) return die()
            resourceUntil = data.getLong("resourceUntil")
            if (resourceUntil > SystemClock.elapsedRealtime() + 500) return die()
            report("CHECKING")
            guard.run()
            val cancelLoop = data.getBoolean("cancelLoop")
            val text = data.getString("text") ?: "Synthetic pilot launch on 6 October. John owns the contract."
            if (text.length > 16_384) return die()
            native.execute { run(descriptor, cancelLoop, text, data.getString("inputDigest"), data.getInt("expectedTokens")) }
        }
    })

    override fun onBind(intent: Intent): IBinder = endpoint.binder
    override fun onCreate() {
        super.onCreate()
        main.postDelayed({ if (owner == null) die() }, 5_000)
    }
    override fun onUnbind(intent: Intent): Boolean = die()

    private fun report(phase: String, extras: Bundle = Bundle()) {
        extras.putString("id", owner)
        extras.putString("phase", phase)
        extras.putLong("elapsedMs", SystemClock.elapsedRealtime())
        extras.putInt("pssKb", maxOf(peakPssKb, Debug.getPss().toInt()))
        extras.putInt("pid", Process.myPid())
        try { reply?.send(Message.obtain().apply { what = 3; data = extras }) }
        catch (_: Exception) { die() }
    }

    private fun run(descriptor: ParcelFileDescriptor, cancelLoop: Boolean, text: String, expectedDigest: String?, expectedTokens: Int) {
        var encoder: TextEmbedder? = null
        try {
            check(Process.isIsolated())
            check(descriptor.statSize == 6120274L)
            val digest = MessageDigest.getInstance("SHA-256")
            ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(count > 0)
                    digest.update(buffer, 0, count)
                }
                buffer.fill(0)
            }
            check(digest.digest().joinToString("") { "%02x".format(it) } ==
                "89ad3c74175dd8caa398cc22b657296d94302d20c525c12b58b29420f7249749")
            val admitted = runBlocking {
                AndroidUseAdmission.open(descriptor, internalBuild = true, SdkPacketTransport::bytes).admit(EncoderInput(text))
            }
            if (admitted !is EncoderAdmission.Accepted) {
                report("REJECTED", Bundle().apply { putString("reason", (admitted as EncoderAdmission.Rejected).error.name) })
                return
            }
            if (expectedDigest != null && (admitted.receipt.transportSha256 != expectedDigest || admitted.receipt.responseTokens != expectedTokens)) {
                report("REJECTED", Bundle().apply { putString("reason", AdmissionError.MISMATCH.name) })
                return
            }
            android.system.Os.lseek(descriptor.fileDescriptor, 0, android.system.OsConstants.SEEK_SET)
            report("LOADING")
            val started = SystemClock.elapsedRealtime()
            encoder = TextEmbedder.createFromOptions(this, TextEmbedder.TextEmbedderOptions.builder()
                .setBaseOptions(BaseOptions.builder().setModelAssetFileDescriptor(descriptor.fd).build())
                .setL2Normalize(true).build())
            val loaded = SystemClock.elapsedRealtime()
            val timings = ArrayList<Long>()
            var dimensions = 0
            repeat(if (cancelLoop) 10000 else 2) { index ->
                val before = SystemClock.elapsedRealtime()
                val result = encoder.embed(text)
                timings.add(SystemClock.elapsedRealtime() - before)
                val heads = result.embeddingResult().embeddings()
                check(heads.isNotEmpty())
                val values = heads.first().floatEmbedding()
                check(values.isNotEmpty() && values.size <= 4096 && values.all { it.isFinite() })
                dimensions = values.size
                val norm = kotlin.math.sqrt(values.sumOf { it.toDouble() * it })
                check(kotlin.math.abs(norm - 1) < 0.01)
                if (index == 0) report("ENCODING", Bundle().apply {
                    putInt("dimensions", dimensions); putInt("heads", heads.size)
                    putFloatArray("vector", values)
                })
            }
            report("RESULT", Bundle().apply {
                putInt("dimensions", dimensions)
                putLong("loadMs", loaded - started)
                putLongArray("encodeMs", timings.toLongArray())
            })
            encoder.close(); encoder = null
            descriptor.close()
            report("CLOSED")
        } catch (failure: Throwable) {
            // Diagnostics are typed phase/metadata only, never input text or native exception payloads.
            report("FAILED", Bundle().apply { putString("failureClass", failure.javaClass.simpleName) })
        } finally {
            // Confirmed process death, not close() alone, is required before the next owner is admitted.
            die()
        }
    }

    private fun die(): Nothing { Process.killProcess(Process.myPid()); throw IllegalStateException("Isolated worker did not terminate") }
}
