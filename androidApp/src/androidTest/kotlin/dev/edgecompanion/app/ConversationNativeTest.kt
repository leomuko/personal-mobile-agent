package dev.edgecompanion.app

import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.inference.AndroidModelResources
import dev.edgecompanion.core.conversation.model.*
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.core.inference.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.koin.android.ext.android.getKoin

class ConversationNativeTest {
    @Test fun realConversationHandoffDraftCancelReloadAndReopen(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("nativeChat") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".secureqa"))
        val koin = (context.applicationContext as CompanionApplication).getKoin()
        val session = koin.get<ConversationSession>()
        val models = koin.get<ModelManager>()
        val resources = koin.get<AndroidModelResources>()
        fun checkpoint(stage: String) {
            val sample = resources.snapshot()
            InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
                putString("chatStage", stage)
                putString("runtimeStatus", models.state.value.status.name)
                putString("runtimeError", models.state.value.error?.name)
                putString("attemptStatus", session.state.value.attempts.lastOrNull()?.status?.name)
                putString("attemptError", session.state.value.attempts.lastOrNull()?.error?.name)
                putLong("availableMemoryBytes", sample.availableMemoryBytes ?: -1)
                putBoolean("lowMemory", sample.lowMemory)
                putInt("thermalStatus", sample.thermalStatus ?: -1)
            })
        }
        checkpoint("setup")
        withTimeout(30_000) { session.state.first { it.ready } }
        models.release().join()
        session.clear()
        withTimeout(30_000) { session.state.first { it.messages.isEmpty() && it.draft.isEmpty() } }
        try {
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
                session.editDraft("Name three colors. \u65e5\u672c\u8a9e \uD83D\uDE42")
                withTimeout(10_000) { session.state.first { it.draft.startsWith("Name three") } }
                session.submit()
                checkpoint("first-submission")
                withTimeout(30_000) { session.state.first { it.assistant.active || it.assistant.phase == AssistantPhase.ERROR } }
                check(session.state.value.assistant.active) { "Chat admission failed: ${session.state.value.assistant.error}" }
                session.editDraft("Next draft")
                main.recreate()
                ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use { drawer ->
                    withTimeout(10_000) { session.state.first { it.draft == "Next draft" } }
                    // Match Expand: establish the full-app host before dismissing the drawer.
                    drawer.onActivity { it.startActivity(AssistantIntents.main(it)) }
                    withTimeout(10_000) {
                        while (main.state != Lifecycle.State.RESUMED) delay(50)
                    }
                }
                val completed = withTimeout(180_000) { session.state.first {
                    it.messages.any { message -> message.role == MessageRole.ASSISTANT } ||
                        it.attempts.any { attempt -> attempt.status != TurnStatus.PENDING }
                } }
                checkpoint("first-terminal")
                assertTrue("Native chat failed: ${completed.assistant.error}", completed.messages.any { it.role == MessageRole.ASSISTANT })
                assertEquals("Next draft", completed.draft)
                assertTrue(completed.messages.last().text.isNotBlank())
                session.editDraft("List one hundred household objects, one per line.")
                withTimeout(10_000) { session.state.first { it.draft.startsWith("List one") } }
                session.submit()
                withTimeout(180_000) { session.state.first { it.assistant.phase == AssistantPhase.STREAMING || it.assistant.phase == AssistantPhase.ERROR } }
                check(session.state.value.assistant.phase == AssistantPhase.STREAMING) { "No active chat stream to cancel" }
                checkpoint("cancel-stream")
                session.cancelAssistant()
                val cancelled = withTimeout(30_000) { session.state.first { it.attempts.lastOrNull()?.status == TurnStatus.CANCELLED } }
                assertEquals(1, cancelled.messages.count { it.role == MessageRole.ASSISTANT })
                models.release().join()
                assertEquals(RuntimeStatus.UNLOADED, models.state.value.status)
                checkpoint("released")
            }
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use {
                assertEquals(1, session.state.value.messages.count { it.role == MessageRole.ASSISTANT })
                session.retryAssistant(session.state.value.messages.last { it.role == MessageRole.USER }.id)
                withTimeout(180_000) { session.state.first {
                    it.messages.count { message -> message.role == MessageRole.ASSISTANT } == 2 ||
                        (it.assistant.phase == AssistantPhase.ERROR && it.attempts.last().status == TurnStatus.FAILED)
                } }
                assertEquals(2, session.state.value.messages.count { it.role == MessageRole.USER })
                assertEquals(2, session.state.value.messages.count { it.role == MessageRole.ASSISTANT })
                checkpoint("retry-complete")
            }
        } finally { withContext(NonCancellable) { models.release().join(); checkpoint("cleanup") } }
    }
}
