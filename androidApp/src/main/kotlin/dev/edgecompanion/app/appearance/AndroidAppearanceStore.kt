package dev.edgecompanion.app.appearance

import android.app.UiModeManager
import android.content.Context
import dev.edgecompanion.core.appearance.AppearanceStore
import dev.edgecompanion.core.appearance.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AndroidAppearanceStore(context: Context) : AppearanceStore {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    private val uiMode = context.getSystemService(UiModeManager::class.java)

    override fun read(): ThemeMode = storedMode().also(::applyToPlatform)

    // Finish the short disk/platform transaction even if the requesting UI disappears.
    override suspend fun write(mode: ThemeMode) = withContext(Dispatchers.IO + NonCancellable) {
        val previous = storedMode()
        try {
            check(preferences.edit().putString("theme", mode.storedValue).commit())
            applyToPlatform(mode)
        }
        catch (failure: Exception) {
            try {
                check(preferences.edit().putString("theme", previous.storedValue).commit())
                applyToPlatform(previous)
            } catch (rollback: Exception) { failure.addSuppressed(rollback) }
            throw failure
        }
    }

    private fun storedMode() = ThemeMode.fromStored(preferences.getString("theme", null))

    private fun applyToPlatform(mode: ThemeMode) {
        // AUTO clears this package's override; it does not change the device's night mode.
        uiMode.setApplicationNightMode(when (mode) {
            ThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
        })
    }
}
