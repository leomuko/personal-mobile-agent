package dev.edgecompanion.core.di

import dev.edgecompanion.core.storage.database.SessionDatabase

import dev.edgecompanion.core.time.Clock

import dev.edgecompanion.core.conversation.data.ConversationStore
import dev.edgecompanion.core.conversation.data.SqlConversationStore
import dev.edgecompanion.core.conversation.session.ConversationController
import dev.edgecompanion.core.conversation.session.ConversationSession

import dev.edgecompanion.core.*
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose

val SessionDispatcher = named("sessionDispatcher")

fun sharedModule() = module {
    single { SessionDatabase(get()) }
    single { get<SessionDatabase>().database }
    single<ConversationStore> { SqlConversationStore(get()) }
    single { ConversationController(get(), get<Clock>()::now) }
    single {
        val storage = get<SessionDatabase>()
        ConversationSession(get<CoroutineDispatcher>(SessionDispatcher),
            controllerProvider = { get<ConversationController>() },
            closeStorage = storage::close, attachmentFiles = getOrNull())
    } onClose { it?.stop() }
}
