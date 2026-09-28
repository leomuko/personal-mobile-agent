package dev.edgecompanion.app.permissions

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal const val MASCOT_CHANNEL = "mascot"

internal fun Context.notificationControlsAvailable(): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    val manager = getSystemService(NotificationManager::class.java)
    return manager.areNotificationsEnabled() && manager.getNotificationChannel(MASCOT_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

internal enum class EnableStep { OVERLAY_PERMISSION, NOTIFICATION_PERMISSION, CONFIRM_DEGRADED, START }

internal fun nextEnableStep(overlayAllowed: Boolean, notificationsAvailable: Boolean,
    canRequestNotifications: Boolean, acceptedDegraded: Boolean): EnableStep = when {
    !overlayAllowed -> EnableStep.OVERLAY_PERMISSION
    notificationsAvailable || acceptedDegraded -> EnableStep.START
    canRequestNotifications -> EnableStep.NOTIFICATION_PERMISSION
    else -> EnableStep.CONFIRM_DEGRADED
}
