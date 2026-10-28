package dev.edgecompanion.core.inference.litert

import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.ResponseFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pinned JVM API checks only: no Engine, native handle, decoding or model file. */
class LiteRtResponseFormatAuditTest {
    @Test fun responseFormatRequiresExplicitConversationOptIn() {
        assertFalse(ConversationConfig().enableResponseFormat)
        val enabled = ConversationConfig(automaticToolCalling = false, enableResponseFormat = true)
        assertTrue(enabled.enableResponseFormat)
        assertFalse(enabled.automaticToolCalling)
    }

    @Test fun jsonFactoryRetainsTheExactSchemaStringAndNativeType() {
        val schema = """{"type":"object","properties":{"q":{"type":"string"}},"required":["q"],"additionalProperties":false}"""
        val format = ResponseFormat.json(schema)
        assertEquals(schema, format.schemaOrPattern)
        assertEquals(ResponseFormat.Type.JSON_OBJECT, format.type)
        assertEquals(2, format.type.value)
    }

    @Test fun mapFactoryIsAvailableWithoutNativeInitialization() {
        val format = ResponseFormat.json(mapOf("type" to "object"))
        assertEquals("""{"type":"object"}""", format.schemaOrPattern)
        assertEquals(ResponseFormat.Type.JSON_OBJECT, format.type)
    }

    @Test fun jsonFactoryIsNotAJsonSchemaCompilerOrEnforcementProof() {
        assertFailsWith<IllegalArgumentException> { ResponseFormat.json("{") }
        // A JSON number is accepted here even though it is not our object schema.
        assertEquals("17", ResponseFormat.json("17").schemaOrPattern)
    }

    @Test fun regexIsASeparateConstraintTypeNotJsonSchema() {
        val format = ResponseFormat.regex("[0-9]+")
        assertEquals(ResponseFormat.Type.REGEX, format.type)
        assertEquals(1, format.type.value)
        assertEquals("[0-9]+", format.schemaOrPattern)
    }

    @Test fun synchronousFormatIsRejectedBeforeNativeWorkWithoutOptIn() {
        // Zero is a sentinel, not an allocated native handle. Never close this object:
        // the tested guard must reject before any JNI call or native allocation.
        val conversation = Conversation(0L, automaticToolCalling = false, enableResponseFormat = false)
        val failure = assertFailsWith<IllegalArgumentException> {
            conversation.sendMessage("synthetic", responseFormat = ResponseFormat.json("{}"))
        }
        assertTrue(failure.message.orEmpty().contains("enableResponseFormat"))
    }

    @Test fun callbackFormatIsRejectedBeforeNativeWorkWithoutOptIn() {
        val conversation = Conversation(0L, automaticToolCalling = false, enableResponseFormat = false)
        val callback = object : MessageCallback {
            override fun onMessage(message: Message) = error("Unexpected callback")
            override fun onDone() = error("Unexpected completion")
            override fun onError(throwable: Throwable) = error("Unexpected native error")
        }
        val failure = assertFailsWith<IllegalArgumentException> {
            conversation.sendMessageAsync("synthetic", callback, responseFormat = ResponseFormat.json("{}"))
        }
        assertTrue(failure.message.orEmpty().contains("enableResponseFormat"))
    }

    @Test fun callbackApiExposesPerRequestFormatAlongsideCancelAndClose() {
        val method = Conversation::class.java.methods.single { method ->
            method.name == "sendMessageAsync" && method.parameterTypes.firstOrNull() == String::class.java &&
                method.parameterTypes.getOrNull(1) == MessageCallback::class.java &&
                method.parameterTypes.lastOrNull() == ResponseFormat::class.java
        }
        assertEquals(Void.TYPE, method.returnType)
        assertEquals(Void.TYPE, Conversation::class.java.getMethod("cancelProcess").returnType)
        assertEquals(Void.TYPE, Conversation::class.java.getMethod("close").returnType)
    }
}
