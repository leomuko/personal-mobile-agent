package dev.edgecompanion.app

import dev.edgecompanion.app.di.appModule
import dev.edgecompanion.app.overlay.PresenceCoordinator

import android.app.Application
import android.app.Activity
import android.os.Bundle
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.core.di.androidStorageModule
import dev.edgecompanion.core.di.sharedModule
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin

class CompanionApplication : Application() {
    internal val presence: PresenceCoordinator by inject()
    override fun onCreate() {
        super.onCreate()
        val koin = startKoin {
            androidContext(this@CompanionApplication)
            allowOverride(false)
            modules(sharedModule(), androidStorageModule(this@CompanionApplication), appModule())
        }.koin
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { presence.started(System.identityHashCode(activity)) }
            override fun onActivityStopped(activity: Activity) { presence.stopped(System.identityHashCode(activity), activity.isChangingConfigurations) }
            override fun onActivityDestroyed(activity: Activity) {
                if (!activity.isChangingConfigurations) presence.stopped(System.identityHashCode(activity))
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        })
        koin.get<ConversationSession>().start()
    }
}
