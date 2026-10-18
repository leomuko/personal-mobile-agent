package dev.edgecompanion.app

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.os.Build
import android.os.ParcelFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import java.io.File
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.appearance.AndroidAppearanceStore
import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.core.appearance.AppearancePreferences
import dev.edgecompanion.core.appearance.ThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.koin.android.ext.android.getKoin

class AppearanceHostTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val koin get() = (context.applicationContext as CompanionApplication).getKoin()

    @Test fun modeChangesKeepMainDrawerAndCaptureHostsAliveAndSecure() {
        val preferences = koin.get<AppearancePreferences>()
        val previous = preferences.state.value.mode
        try {
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { checkHost(it, preferences) }
            ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use { checkHost(it, preferences) }
            ActivityScenario.launch<CaptureActivity>(Intent(context, CaptureActivity::class.java)).use { checkHost(it, preferences) }
        } finally { runBlocking { preferences.select(previous).join() } }
    }

    @Test fun savedModeSurvivesRecreationAndIsSeparateFromConversationStorage() {
        val preferences = koin.get<AppearancePreferences>()
        val previous = preferences.state.value.mode
        try {
            runBlocking { preferences.select(ThemeMode.DARK).join() }
            assertNull(preferences.state.value.error)
            assertEquals(ThemeMode.DARK, AndroidAppearanceStore(context).read())
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { scenario ->
                scenario.recreate()
                scenario.onActivity { assertEquals(Configuration.UI_MODE_NIGHT_YES, night(it)) }
            }
        } finally { runBlocking { preferences.select(previous).join() } }
    }

    @Test fun installedVariantHasDistinctIdentityAndAllHostsHandleNightMode() {
        val application = context.applicationInfo
        assertEquals(if (context.packageName.endsWith(".secureqa")) "Assistant (QA)" else "Assistant",
            context.packageManager.getApplicationLabel(application).toString())
        val iconName = context.resources.getResourceEntryName(application.icon)
        assertEquals(if (context.packageName.endsWith(".secureqa")) "ic_launcher_qa" else "ic_assistant", iconName)
        val icon = context.packageManager.getApplicationIcon(application)
        exportIcon(icon, "launcher-icon")
        if (context.packageName.endsWith(".secureqa") && Build.VERSION.SDK_INT >= 33) {
            assertTrue(icon is AdaptiveIconDrawable)
            val monochrome = (icon as AdaptiveIconDrawable).monochrome
            assertNotNull("QA identity needs a themed-icon representation", monochrome)
            exportIcon(monochrome!!, "launcher-monochrome")
        }
        listOf(MainActivity::class.java, DrawerActivity::class.java, CaptureActivity::class.java).forEach { host ->
            val info = context.packageManager.getActivityInfo(android.content.ComponentName(context, host), 0)
            assertTrue(info.configChanges and ActivityInfo.CONFIG_UI_MODE != 0)
        }
    }

    private fun <T : ComponentActivity> checkHost(scenario: ActivityScenario<T>, preferences: AppearancePreferences) {
        lateinit var original: T
        scenario.onActivity { original = it }
        for (mode in listOf(ThemeMode.DARK, ThemeMode.LIGHT, ThemeMode.SYSTEM)) {
            runBlocking { preferences.select(mode).join() }
            assertNull(preferences.state.value.error)
            val expected = when (mode) {
                ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
                ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
                ThemeMode.SYSTEM -> deviceNightMode()
            }
            val deadline = SystemClock.uptimeMillis() + 5_000
            var matched = false
            while (!matched && SystemClock.uptimeMillis() < deadline) {
                instrumentation.waitForIdleSync()
                scenario.onActivity { matched = night(it) == expected }
                if (!matched) SystemClock.sleep(50)
            }
            assertTrue("Night mode did not resolve for $mode on ${original.javaClass.simpleName}; expected=$expected", matched)
            scenario.onActivity {
                assertSame("Appearance must not recreate an active host", original, it)
                assertEquals(BuildConfig.SECURE_WINDOWS, it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                val bars = WindowCompat.getInsetsController(it.window, it.window.decorView)
                assertEquals(expected != Configuration.UI_MODE_NIGHT_YES, bars.isAppearanceLightStatusBars)
                assertEquals(expected != Configuration.UI_MODE_NIGHT_YES, bars.isAppearanceLightNavigationBars)
            }
        }
    }

    private fun night(activity: ComponentActivity) = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK

    private fun deviceNightMode(): Int {
        // Resources.getSystem() can reflect this process's package override, not global appearance.
        val descriptor = instrumentation.uiAutomation.executeShellCommand("am get-config")
        val config = ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { reader ->
            reader.lineSequence().firstOrNull { it.startsWith("config: ") }
        } ?: error("Android did not report the device configuration")
        val qualifiers = config.removePrefix("config: ").split('-')
        return when {
            "night" in qualifiers -> Configuration.UI_MODE_NIGHT_YES
            "notnight" in qualifiers -> Configuration.UI_MODE_NIGHT_NO
            else -> error("Android did not report a night-mode qualifier")
        }
    }

    private fun exportIcon(icon: Drawable, name: String) {
        if (InstrumentationRegistry.getArguments().getString("appearanceScreenshots") != "true") return
        val bitmap = Bitmap.createBitmap(324, 324, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap).apply { drawColor(Color.WHITE) }
        icon.setBounds(0, 0, bitmap.width, bitmap.height)
        icon.draw(canvas)
        val directory = File(context.getExternalFilesDir(null), "appearance-fixtures").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
