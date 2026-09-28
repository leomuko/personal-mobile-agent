package dev.edgecompanion.core.di

import dev.edgecompanion.core.storage.database.DatabaseDriverFactory

import dev.edgecompanion.core.time.Clock

import android.content.Context
import dev.edgecompanion.core.storage.database.EncryptedDatabaseFactory
import org.koin.dsl.module

fun androidStorageModule(context: Context) = module {
    single<dev.edgecompanion.core.attachments.data.AttachmentFiles> {
        dev.edgecompanion.core.storage.files.EncryptedAttachmentStore(context.applicationContext)
    }
    single<DatabaseDriverFactory> {
        DatabaseDriverFactory { EncryptedDatabaseFactory.open(context.applicationContext) }
    }
    single<Clock> { Clock(System::currentTimeMillis) }
}
