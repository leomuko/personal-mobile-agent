package dev.edgecompanion.app

import dev.edgecompanion.app.di.ordinaryChatProfile
import dev.edgecompanion.core.inference.ChatContextProfile
import dev.edgecompanion.core.inference.ChatRequest
import dev.edgecompanion.core.inference.ChatMessage
import dev.edgecompanion.core.inference.ChatRole
import dev.edgecompanion.core.inference.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Runs against the BuildConfig of the variant under test (debug, release or secureQa). */
class OrdinaryChatProfileTest {
    @Test fun ordinaryChatUsesTheBakedBuildProfile() {
        val expected = if (BuildConfig.BUILD_TYPE == "secureQa") BuildConfig.CHAT_CONTEXT_TOKENS else 4096
        assertEquals(expected, BuildConfig.CHAT_CONTEXT_TOKENS)
        assertEquals(expected, ordinaryChatProfile().contextTokens)
        assertEquals(512, ordinaryChatProfile().outputTokens)
    }

    @Test fun debugAndReleaseOrdinaryChatIs4096() {
        if (BuildConfig.BUILD_TYPE == "debug" || BuildConfig.BUILD_TYPE == "release")
            assertSame(ChatContextProfile.EXTENDED_4096, ordinaryChatProfile())
    }

    @Test fun everySupportedBakedSizeResolvesToItsProfile() {
        assertSame(ChatContextProfile.STANDARD, ordinaryChatProfile(2048))
        assertSame(ChatContextProfile.EXTENDED_4096, ordinaryChatProfile(4096))
        assertSame(ChatContextProfile.EXTENDED_8192, ordinaryChatProfile(8192))
    }

    @Test fun qaProtocolAndPanelSizesStay2048And512() {
        val request = ChatRequest("qa", listOf(ChatMessage(ChatRole.USER, "x")))
        assertEquals(2048 to 512, request.contextTokens to request.outputTokens)
        assertEquals(2048 to 512, ModelCatalog.gemma4.configuredContextTokens to ModelCatalog.gemma4.maxOutputTokens)
    }
}
