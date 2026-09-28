package dev.edgecompanion.app.privacy

import dev.edgecompanion.app.BuildConfig

import android.view.Window
import android.view.WindowManager

internal fun Window.applyPrivacyPolicy() {
    if (BuildConfig.SECURE_WINDOWS) addFlags(WindowManager.LayoutParams.FLAG_SECURE)
}
