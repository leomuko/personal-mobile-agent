package dev.edgecompanion.app

import androidx.activity.compose.BackHandler
import dev.edgecompanion.app.attachments.*
import dev.edgecompanion.app.capture.audio.AndroidAudioRecorder
import dev.edgecompanion.core.attachments.data.AttachmentFiles
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.ui.conversation.components.AttachmentSource
import java.util.UUID

import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.overlay.PresenceCoordinator
import dev.edgecompanion.app.permissions.notificationControlsAvailable
import dev.edgecompanion.app.permissions.EnableStep
import dev.edgecompanion.app.permissions.nextEnableStep
import dev.edgecompanion.app.privacy.applyPrivacyPolicy

import android.app.ActivityManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.edgecompanion.ui.app.CompanionScreen
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import dev.edgecompanion.ui.conversation.viewmodel.ConversationViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.text.NumberFormat

open class MainActivity : ComponentActivity() {
    protected open val drawer = false
    private val conversation: ConversationViewModel by viewModel()
    private val session: ConversationSession by inject()
    private val mediaFiles: AttachmentFiles by inject()
    private var cameraDenied by mutableStateOf(false)
    private val attachments by lazy { AttachmentLaunchers(this, session) { cameraDenied = true } }
    private var recordingEpoch = 0L
    private var preview by mutableStateOf<Attachment?>(null)
    private var captureError by mutableStateOf(false)
    private var confirmDiscard by mutableStateOf(false)
    private val audioGate: dev.edgecompanion.app.capture.audio.AudioCaptureGate by inject()
    private val audio by lazy { AndroidAudioRecorder(audioGate, finished = { bytes ->
        session.importAttachment(Attachment(UUID.randomUUID().toString(), getString(R.string.voice_attachment), "audio/wav"), recordingEpoch) { bytes }
    }, failed = { captureError = true }) }
    private val microphonePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) audio.start()
        else captureError = true
    }
    private val presence: PresenceCoordinator by inject()
    private var pendingEnable = false
    private var notificationDialog by mutableStateOf(false)
    private var notificationsAvailable by mutableStateOf(true)
    private var conversationRequest by mutableIntStateOf(0)
    private val permissionHistory by lazy { getSharedPreferences("permission-history", MODE_PRIVATE) }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingEnable && Settings.canDrawOverlays(this)) continueEnable() else pendingEnable = false
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingEnable) continueEnable()
    }
    private val notificationSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        notificationsAvailable = notificationControlsAvailable()
        if (pendingEnable) continueEnable()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attachments.restore(savedInstanceState)
        window.applyPrivacyPolicy()
        enableEdgeToEdge()
        pendingEnable = savedInstanceState?.getBoolean("pendingEnable") ?: false
        notificationDialog = savedInstanceState?.getBoolean("notificationDialog") ?: false
        if (drawer) onBackPressedDispatcher.addCallback(this) { closeDrawer() }
        setContent {
            CompanionTheme {
                val state by conversation.state.collectAsStateWithLifecycle()
                val recording by audio.state.collectAsStateWithLifecycle()
                BackHandler(enabled = recording.active) { confirmDiscard = true }
                val presenceState by presence.state.collectAsStateWithLifecycle()
                Box(Modifier.fillMaxSize().background(if (drawer) Color.Transparent else Color.White)
                    .statusBarsPadding().navigationBarsPadding().imePadding(), contentAlignment = Alignment.BottomCenter) {
                    Surface(modifier = Modifier.fillMaxWidth().then(if (drawer) Modifier.fillMaxHeight(0.86f) else Modifier.fillMaxHeight()),
                        shape = if (drawer) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(0.dp)) {
                        CompanionScreen(state, drawer, presenceState.running, deviceSummary(),
                            onDraft = conversation::editDraft,
                            onSend = conversation::submit,
                            onMicrophone = ::record, onCamera = { attachments.launch(AttachmentSource.CAMERA) },
                            onOverlay = ::setOverlay,
                            onExpand = { startActivity(AssistantIntents.main(this@MainActivity)); closeDrawer() },
                            onClose = ::closeDrawer,
                            onClear = conversation::clearConversation,
                            notificationsAvailable = notificationsAvailable,
                            onNotificationSettings = ::openNotificationSettings,
                            conversationRequest = conversationRequest,
                            onAttachmentSource = attachments::launch,
                            onRemoveAttachment = conversation::removeAttachment,
                            onPreviewAttachment = { preview = it },
                            thumbnail = { item, modifier -> LocalThumbnail(item, mediaFiles, modifier) },
                            recording = recording,
                            onStopRecording = { audio.stop() }, onCancelRecording = { audio.stop(cancel = true) })
                    }
                }
                preview?.let { AttachmentPreview(it, mediaFiles) { preview = null } }
                if (cameraDenied) AlertDialog(onDismissRequest = { cameraDenied = false },
                    title = { Text(stringResource(R.string.camera_required)) },
                    text = { Text(stringResource(R.string.camera_permission_explanation)) },
                    confirmButton = { TextButton(onClick = {
                        cameraDenied = false
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                    }) { Text(stringResource(R.string.app_settings)) } },
                    dismissButton = { TextButton(onClick = { cameraDenied = false }) { Text(stringResource(R.string.close)) } })
                if (captureError) AlertDialog(onDismissRequest = { captureError = false },
                    text = { Text(stringResource(R.string.microphone_unavailable)) },
                    confirmButton = { TextButton(onClick = { captureError = false }) { Text(stringResource(R.string.close)) } })
                if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false },
                    text = { Text(stringResource(R.string.discard_recording)) },
                    confirmButton = { TextButton(onClick = { audio.stop(cancel = true); confirmDiscard = false }) { Text(stringResource(R.string.discard)) } },
                    dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } })
                if (notificationDialog) AlertDialog(
                    onDismissRequest = ::cancelEnable,
                    title = { Text(stringResource(R.string.notifications_unavailable)) },
                    text = { Text(stringResource(R.string.notifications_explanation)) },
                    confirmButton = { TextButton(onClick = {
                        notificationDialog = false
                        if (pendingEnable) continueEnable(acceptedDegraded = true)
                    }) { Text(stringResource(R.string.continue_without_notifications)) } },
                    dismissButton = {
                        Column {
                            TextButton(onClick = { notificationDialog = false; openNotificationSettings() }) { Text(stringResource(R.string.notification_settings)) }
                            TextButton(onClick = ::cancelEnable) { Text(stringResource(R.string.cancel)) }
                        }
                    },
                )
            }
        }
    }
    private fun closeDrawer() { if (drawer) finishAndRemoveTask() else finish() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        conversationRequest++
    }
    private fun record() {
        recordingEpoch = session.state.value.draftEpoch
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) audio.start()
        else microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    override fun onPause() { audio.stop(); preview = null; super.onPause() }
    override fun onDestroy() { audio.close(); super.onDestroy() }
    private fun setOverlay(enabled: Boolean) {
        if (!enabled) {
            cancelEnable()
            presence.service(false)
            stopService(Intent(this, MascotService::class.java))
            return
        }
        if (pendingEnable) return
        pendingEnable = true
        continueEnable()
    }
    private fun cancelEnable() { pendingEnable = false; notificationDialog = false }
    private fun continueEnable(acceptedDegraded: Boolean = false) {
        notificationsAvailable = notificationControlsAvailable()
        when (nextEnableStep(Settings.canDrawOverlays(this), notificationsAvailable,
            Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                !permissionHistory.getBoolean("notificationRequested", false), acceptedDegraded)) {
            EnableStep.OVERLAY_PERMISSION -> overlayPermission.launch(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            EnableStep.NOTIFICATION_PERMISSION -> {
                if (Build.VERSION.SDK_INT >= 33) {
                    permissionHistory.edit { putBoolean("notificationRequested", true) }
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            EnableStep.CONFIRM_DEGRADED -> notificationDialog = true
            EnableStep.START -> { pendingEnable = false; startMascot() }
        }
    }
    private fun openNotificationSettings() {
        notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }
    override fun onResume() {
        super.onResume()
        notificationsAvailable = notificationControlsAvailable()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        attachments.save(outState)
        outState.putBoolean("pendingEnable", pendingEnable)
        outState.putBoolean("notificationDialog", notificationDialog)
        super.onSaveInstanceState(outState)
    }
    private fun startMascot() {
        try { startForegroundService(Intent(this, MascotService::class.java)) }
        catch (_: Exception) { Toast.makeText(this, R.string.mascot_start_failed, Toast.LENGTH_SHORT).show() }
    }
    private fun deviceSummary(): String {
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        val ram = NumberFormat.getNumberInstance().apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
            .format(memory.totalMem / 1073741824.0)
        return getString(R.string.device_summary, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT, ram, BuildConfig.VERSION_NAME)
    }
}
