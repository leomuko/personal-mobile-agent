package dev.edgecompanion.app

import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.overlay.MascotView
import dev.edgecompanion.app.overlay.PresenceCoordinator
import dev.edgecompanion.app.permissions.MASCOT_CHANNEL

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.Toast
import kotlinx.coroutines.*
import org.koin.android.ext.android.inject

class MascotService : Service() {
    private var mascot: MascotView? = null
    private var position: WindowManager.LayoutParams? = null
    private val windows by lazy { getSystemService(WindowManager::class.java) }
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val presence: PresenceCoordinator by inject()
    private var receiverRegistered = false
    private val launchRecovery = Runnable {
        if (presence.state.value.opening) {
            presence.opening(false)
            Toast.makeText(this, R.string.drawer_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
    private val screenEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { refreshEnvironment() }
    }
    private val permissionChanged = AppOpsManager.OnOpChangedListener { op, pkg ->
        if (op == AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW && pkg == packageName) handler.post {
            refreshEnvironment()
            if (!Settings.canDrawOverlays(this)) stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            presence.state.collect { state ->
                mascot?.visibility = if (state.mascotVisible) View.VISIBLE else View.GONE
                if (!state.opening) handler.removeCallbacks(launchRecovery)
            }
        }
    }

    override fun onBind(intent: Intent?) = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !Settings.canDrawOverlays(this)) { stopSelf(); return START_NOT_STICKY }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(MASCOT_CHANNEL, getString(R.string.mascot_channel), NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 1, AssistantIntents.main(this), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 2, Intent(this, MascotService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, MASCOT_CHANNEL).setSmallIcon(R.drawable.ic_assistant)
                .setContentTitle(getString(R.string.mascot_available)).setContentText(getString(R.string.mascot_active))
                .setContentIntent(open).setOngoing(true)
                .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(1, notification)
            if (mascot == null) attachMascot()
            refreshEnvironment()
            presence.service(true)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.mascot_start_failed, Toast.LENGTH_SHORT).show()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun refreshEnvironment() {
        val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
            getSystemService(PowerManager::class.java).isInteractive
        presence.environment(Settings.canDrawOverlays(this), unlocked)
    }

    private fun openDrawer() {
        refreshEnvironment()
        if (!presence.state.value.mascotVisible) return
        try {
            // Keep the overlay visible until Android evaluates background-launch eligibility.
            startActivity(AssistantIntents.drawer(this))
            if (presence.state.value.surfaces.isEmpty()) presence.opening(true)
            // Android may reject background launches without throwing. Recover a hidden launcher.
            handler.postDelayed(launchRecovery, 5_000)
        } catch (_: Exception) {
            presence.opening(false)
            Toast.makeText(this, R.string.drawer_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("RtlHardcoded") // rawX and WindowManager x use a physical left origin in both locales.
    private fun attachMascot() {
        val size = (88 * resources.displayMetrics.density).toInt()
        val bounds = windows.currentWindowMetrics.bounds
        position = WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = (bounds.width() - size - 16).coerceAtLeast(0)
            y = (bounds.height() * 0.6).toInt()
        }
        val view = MascotView(this).apply {
            visibility = View.GONE
            setOnClickListener { openDrawer() }
            setOnLongClickListener { stopSelf(); true }
            onDrag = { dx, dy -> position?.let { it.x += dx; it.y += dy }; clampAndUpdate() }
            onDragEnd = {
                position?.let {
                    val width = windows.currentWindowMetrics.bounds.width()
                    it.x = if (it.x + size / 2 < width / 2) 0 else width - size
                }
                clampAndUpdate()
            }
        }
        windows.addView(view, position)
        mascot = view
        clampAndUpdate()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenEvents, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenEvents, filter)
        receiverRegistered = true
        getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionChanged)
    }

    private fun clampAndUpdate() {
        val view = mascot ?: return
        val params = position ?: return
        val metrics = windows.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        params.x = params.x.coerceIn(insets.left, (metrics.bounds.width() - params.width - insets.right).coerceAtLeast(insets.left))
        params.y = params.y.coerceIn(insets.top, (metrics.bounds.height() - params.height - insets.bottom).coerceAtLeast(insets.top))
        runCatching { windows.updateViewLayout(view, params) }.onFailure { stopSelf() }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        mascot?.contentDescription = getString(R.string.mascot_open)
        clampAndUpdate()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        mascot?.let { runCatching { windows.removeView(it) } }
        mascot = null
        if (receiverRegistered) unregisterReceiver(screenEvents)
        getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionChanged)
        presence.service(false)
        super.onDestroy()
    }
}
