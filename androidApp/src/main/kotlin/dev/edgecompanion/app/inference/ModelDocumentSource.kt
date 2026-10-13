package dev.edgecompanion.app.inference

import android.content.ContentResolver
import android.net.Uri
import dev.edgecompanion.core.inference.*

fun modelDocumentSource(resolver: ContentResolver, uri: Uri) = ModelSource {
    val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: throw ModelException(ModelError.IMPORT_FAILED)
    val stream = try { descriptor.createInputStream() } catch (failure: Exception) { descriptor.close(); throw failure }
    object : ModelReader {
        override val size = descriptor.length.takeIf { it >= 0 }
        override fun read(buffer: ByteArray) = stream.read(buffer)
        override fun close() { try { stream.close() } finally { descriptor.close() } }
    }
}
