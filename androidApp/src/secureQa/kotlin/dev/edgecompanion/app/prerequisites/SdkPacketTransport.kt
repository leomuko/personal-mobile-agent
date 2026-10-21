package dev.edgecompanion.app.prerequisites

import com.google.mediapipe.framework.Graph
import com.google.mediapipe.framework.PacketCreator
import com.google.mediapipe.framework.PacketGetter

/** Read the exact SDK transport instead of guessing Java/ART UTF-8 conversion behavior. */
object SdkPacketTransport {
    @JvmStatic fun bytes(text: String): ByteArray {
        require(text.length <= 16_384)
        System.loadLibrary("mediapipe_tasks_jni")
        val graph = Graph()
        try {
            val packet = PacketCreator(graph).createString(text)
            try { return PacketGetter.getBytes(packet) }
            finally { packet.release() }
        } finally { graph.tearDown() }
    }
}
