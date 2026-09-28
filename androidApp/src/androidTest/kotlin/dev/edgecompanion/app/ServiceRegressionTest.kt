package dev.edgecompanion.app

import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.overlay.PresenceState
import dev.edgecompanion.app.permissions.notificationControlsAvailable

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.FixMethodOrder
import org.junit.runners.MethodSorters

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ServiceRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val app get() = context.applicationContext as CompanionApplication

    @Test fun blockedNotificationsStillAllowAnExplicitlyStartedStoppableService() {
        assumeTrue(context.packageName.endsWith(".secureqa") && Build.VERSION.SDK_INT >= 33)
        // The external runner revokes permission before instrumentation; revoking here kills it.
        assumeTrue(InstrumentationRegistry.getArguments().getString("notificationDenied") == "true")
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        try {
            assertFalse(context.notificationControlsAvailable())
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
                main.onActivity { it.startForegroundService(Intent(it, MascotService::class.java)) }
                await { it.running }
                assertFalse(app.presence.state.value.mascotVisible)
                main.onActivity { it.moveTaskToBack(true) }
                await { it.mascotVisible }
                context.stopService(Intent(context, MascotService::class.java))
                await { !it.running }
                assertFalse(app.presence.state.value.mascotVisible)
            }
        } finally {
            context.stopService(Intent(context, MascotService::class.java))
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
            shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        }
    }

    @Test fun grantedNotificationStopActionStopsTheService() {
        assumeTrue(context.packageName.endsWith(".secureqa") && Build.VERSION.SDK_INT >= 33)
        shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
        shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        try {
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
                main.onActivity { it.startForegroundService(Intent(it, MascotService::class.java)) }
                await { it.running }
                assertTrue(context.notificationControlsAvailable())
                val notification = context.getSystemService(NotificationManager::class.java).activeNotifications.single { it.id == 1 }.notification
                assertNotNull(notification.contentIntent)
                notification.actions.single().actionIntent.send()
                await { !it.running }
            }
        } finally {
            context.stopService(Intent(context, MascotService::class.java))
            shell("appops set ${context.packageName} SYSTEM_ALERT_WINDOW default")
        }
    }

    private fun await(predicate: (PresenceState) -> Boolean) = runBlocking {
        withTimeout(10_000) { app.presence.state.first(predicate) }
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
}
