package dev.edgecompanion.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.edgecompanion.app.capture.camera.CameraCaptureContent
import dev.edgecompanion.app.privacy.applyPrivacyPolicy
import dev.edgecompanion.core.attachments.model.Attachment
import dev.edgecompanion.core.conversation.session.ConversationSession
import dev.edgecompanion.ui.designsystem.theme.CompanionTheme
import org.koin.android.ext.android.inject
import java.util.UUID

/** Camera-only host. Voice recording stays inside the originating conversation. */
class CaptureActivity : ComponentActivity() {
    private val session: ConversationSession by inject()
    private var granted by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.applyPrivacyPolicy()
        granted = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val epoch = intent.getLongExtra("draftEpoch", session.state.value.draftEpoch)
        setContent {
            CompanionTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
                        Row {
                            Text(stringResource(R.string.camera), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { finish() }) { Text(stringResource(R.string.close)) }
                        }
                        if (!granted) {
                            Text(stringResource(R.string.camera_required))
                            Button(onClick = { finish() }) { Text(stringResource(R.string.return_to_chat)) }
                        } else CameraCaptureContent(this@CaptureActivity, Modifier.weight(1f)) { bytes ->
                            session.importAttachment(Attachment(UUID.randomUUID().toString(), getString(R.string.photo_attachment), "image/jpeg"), epoch) { bytes }
                            setResult(RESULT_OK)
                            finish()
                        }
                    }
                }
            }
        }
    }
}
