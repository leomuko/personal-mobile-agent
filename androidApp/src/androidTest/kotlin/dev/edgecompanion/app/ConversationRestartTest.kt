package dev.edgecompanion.app

import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.core.attachments.model.*
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.conversation.session.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

class ConversationRestartTest {
    @Test fun pendingTurnAndUnsentDraftSurviveProcessRestart(): Unit = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("chatRestartPhase")
        assumeTrue(phase == "seed" || phase == "verify")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val session = koin.get<ConversationSession>()
        withTimeout(30_000) { session.state.first { it.ready } }
        if (phase == "seed") {
            session.clear()
            withTimeout(10_000) { session.state.first { it.messages.isEmpty() } }
            val controller = koin.get<ConversationController>()
            controller.editDraft("Synthetic restart turn")
            val user = checkNotNull(controller.submit())
            assertTrue(controller.startAssistant("process-restart", user.id, controller.state.value.draftEpoch))
            controller.editDraft("Unsent restart draft")
            koin.get<AttachmentFiles>().write("restart-document", "hello".encodeToByteArray())
            assertTrue(controller.reserve(Attachment("restart-document", "Synthetic restart.txt", "text/plain"), controller.state.value.draftEpoch))
            assertTrue(controller.complete("restart-document", 5))
            assertEquals(TurnStatus.PENDING, controller.state.value.attempts.single().status)
        } else {
            val state = session.state.value
            assertEquals("Unsent restart draft", state.draft)
            assertEquals("Synthetic restart turn", state.messages.single().text)
            assertEquals(MessageRole.USER, state.messages.single().role)
            assertEquals(state.messages.single().id, state.attempts.single().userId)
            assertEquals(TurnStatus.INTERRUPTED, state.attempts.single().status)
            assertEquals("", state.assistant.text)
            assertFalse(state.assistant.active)
            assertEquals(AttachmentStatus.READY, state.attachments.single().status)
            assertNull(state.attachments.single().messageId)
            assertEquals("hello", koin.get<AttachmentFiles>().read("restart-document").decodeToString())
        }
    }
}
