package dev.edgecompanion.core.inference.litert

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LiteRtMessageTextTest {
    @Test fun extractsOnlyTextContent() {
        val message = Message.model(Contents.of(Content.Text("Hello"),
            Content.ImageFile("/unused/image.png"), Content.Text(" world")))

        assertEquals("Hello world", extractText(message))
        assertNotEquals("Hello world", message.toString())
    }
    @Test fun ignoresNonTextOnlyAndEmptyMessages() {
        assertEquals("", extractText(Message.model(Contents.of(Content.ImageFile("/unused/image.png")))))
        assertEquals("", extractText(Message.model("")))
    }
}
