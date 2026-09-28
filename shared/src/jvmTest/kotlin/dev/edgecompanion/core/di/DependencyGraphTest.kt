package dev.edgecompanion.core.di

import dev.edgecompanion.core.storage.database.DatabaseDriverFactory

import dev.edgecompanion.core.time.Clock

import dev.edgecompanion.core.conversation.model.Message
import dev.edgecompanion.core.conversation.model.ConversationError
import dev.edgecompanion.core.conversation.data.ConversationStore
import dev.edgecompanion.core.conversation.session.ConversationController
import dev.edgecompanion.core.conversation.session.ConversationSession

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.edgecompanion.core.di.*
import dev.edgecompanion.db.CompanionDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DependencyGraphTest {
    @Test fun lazyGraphSharesOneDatabaseAndSessionAndPreservesWrites() = runTest {
        var opens = 0
        val container = koinApplication {
            modules(sharedModule(), module {
                single<DatabaseDriverFactory> { DatabaseDriverFactory {
                    opens++
                    JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { CompanionDatabase.Schema.create(it) }
                } }
                single<Clock> { Clock { 42L } }
                single<CoroutineDispatcher>(SessionDispatcher) { StandardTestDispatcher(testScheduler) }
            })
        }
        val session = container.koin.get<ConversationSession>()
        try {
            assertEquals(0, opens)
            assertSame(session, container.koin.get<ConversationSession>())
            assertFalse(session.state.value.ready)
            session.start(); session.start()
            advanceUntilIdle()
            assertEquals(1, opens)
            assertTrue(session.state.value.ready)
            assertSame(container.koin.get<ConversationStore>(), container.koin.get<ConversationStore>())
            assertSame(container.koin.get<ConversationController>(), container.koin.get<ConversationController>())
            session.editDraft("Synthetic note"); advanceUntilIdle()
            session.submit(); advanceUntilIdle()
            session.editDraft("Unfinished"); advanceUntilIdle()
            assertEquals("Unfinished", session.state.value.draft)
            assertEquals(listOf(Message(1, "Synthetic note", 42)), session.state.value.messages)
            assertEquals("Synthetic note", container.koin.get<CompanionDatabase>().conversationQueries.search("Synthetic").executeAsOne().text)
        } finally { session.shutdown(); container.close() }
    }

    @Test fun failingFactoryPublishesTypedFailureWithoutRetryingOrResetting() = runTest {
        var attempts = 0
        val container = koinApplication {
            modules(sharedModule(), module {
                single<DatabaseDriverFactory> { DatabaseDriverFactory { attempts++; error("Synthetic key failure") } }
                single<Clock> { Clock { 1L } }
                single<CoroutineDispatcher>(SessionDispatcher) { StandardTestDispatcher(testScheduler) }
            })
        }
        val session = container.koin.get<ConversationSession>()
        try {
            session.start(); advanceUntilIdle(); session.start(); advanceUntilIdle()
            assertEquals(1, attempts)
            assertFalse(session.state.value.ready)
            assertEquals(ConversationError.ENCRYPTED_STORAGE, session.state.value.error)
        } finally { session.shutdown(); container.close() }
    }

    @Test fun overrideDoesNotOpenPlatformStorageAndContainersAreIsolated() = runTest {
        fun graph() = koinApplication {
            modules(sharedModule(), module {
                single<DatabaseDriverFactory> { DatabaseDriverFactory { error("Must not open production storage") } }
                single<ConversationStore> { FakeStore() }
                single<Clock> { Clock { 7L } }
                single<CoroutineDispatcher>(SessionDispatcher) { StandardTestDispatcher(testScheduler) }
            })
        }
        val first = graph(); val second = graph()
        val a = first.koin.get<ConversationSession>(); val b = second.koin.get<ConversationSession>()
        try {
            a.start(); b.start(); advanceUntilIdle()
            a.editDraft("Only first"); advanceUntilIdle()
            assertTrue(a.state.value.ready); assertTrue(b.state.value.ready)
            assertEquals("Only first", a.state.value.draft)
            assertEquals("", b.state.value.draft)
        } finally { a.shutdown(); b.shutdown(); first.close(); second.close() }
    }

    @Test fun shutdownClosesStorageOnceAndPreventsFurtherWrites() = runTest {
        var closed = 0
        val store = FakeStore()
        val session = ConversationSession(StandardTestDispatcher(testScheduler),
            { ConversationController(store) { 1L } }, { closed++ })
        session.start(); advanceUntilIdle()
        session.editDraft("Keep"); advanceUntilIdle()
        session.shutdown(); session.shutdown()
        session.editDraft("After shutdown"); advanceUntilIdle()
        assertEquals(1, closed); assertEquals("Keep", store.text)
    }

    private class FakeStore : ConversationStore {
        var text = ""
        override fun messages() = emptyList<Message>()
        override fun draft() = text
        override fun saveDraft(text: String) { this.text = text }
        override fun append(text: String, timestamp: Long) { this.text = "" }
        override fun clear() { text = "" }
    }
}
