package dev.edgecompanion.app

import dev.edgecompanion.app.navigation.AssistantIntents

import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.ui.conversation.viewmodel.ConversationViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.koin.android.ext.android.getKoin
import org.koin.androidx.viewmodel.ext.android.getViewModel

class DependencyHostTest {
    @Test fun hostViewModelsShareSessionAndRecreationRetainsViewModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val session = (context.applicationContext as CompanionApplication).getKoin().get<ConversationSession>()
        runBlocking { withTimeout(10_000) { session.state.first { it.ready || it.error != null } } }
        assertTrue(session.state.value.ready)
        lateinit var first: ConversationViewModel
        ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
            main.onActivity { first = it.getViewModel(); assertSame(session.state, first.state) }
            main.recreate()
            main.onActivity { assertSame(first, it.getViewModel<ConversationViewModel>()) }
            ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use { drawer ->
                drawer.onActivity {
                    val second = it.getViewModel<ConversationViewModel>()
                    assertNotSame(first, second)
                    assertSame(first.state, second.state)
                }
            }
        }
        assertTrue(session.state.value.ready)
    }
}
