package dev.edgecompanion.core.attachments.model

enum class AttachmentStatus { IMPORTING, READY, FAILED }

data class Attachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val size: Long = 0,
    val status: AttachmentStatus = AttachmentStatus.IMPORTING,
    val messageId: Long? = null,
)

object AttachmentLimits {
    const val COUNT = 10
    const val ITEM_BYTES = 25L * 1024 * 1024
    const val DRAFT_BYTES = 100L * 1024 * 1024
}
