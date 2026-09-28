package dev.edgecompanion.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.support.PermissionDialog
import dev.edgecompanion.app.support.PermissionDialog.Choice
import dev.edgecompanion.core.conversation.session.ConversationSession
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Permission revocation happens before instrumentation, against only the isolated QA package. */
class CameraPermissionFlowTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val dialog get() = PermissionDialog(instrumentation, compose)

    @Test fun cameraPermissionStaysOverAssistantUntilGranted() {
        assumeTrue(context.packageName.endsWith(".secureqa"))
        assumeTrue(InstrumentationRegistry.getArguments().getString("cameraPermissionDenied") == "true")
        assertEquals(PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.CAMERA))
        val session = GlobalContext.get().get<ConversationSession>()
        compose.waitUntil(10_000) { session.state.value.ready }
        session.clear()
        compose.waitUntil { session.state.value.draft.isEmpty() && session.state.value.attachments.isEmpty() }
        session.editDraft("Camera permission fixture")
        compose.waitUntil { session.state.value.draft == "Camera permission fixture" }
        val monitor = instrumentation.addMonitor(CaptureActivity::class.java.name, null, false)
        val automation = instrumentation.uiAutomation
        val oldFlags = automation.serviceInfo.flags
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use {
                chooseCamera()
                dialog.await(Choice.DENY)
                assertEquals("The camera Activity must not open behind the permission dialog", 0, monitor.hits)
                dialog.click(Choice.DENY)
                compose.onNodeWithText("Camera access required").assertIsDisplayed()
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithText("Camera permission fixture").assertIsDisplayed()
                assertEquals(0, monitor.hits)
                assertTrue(session.state.value.attachments.isEmpty())
            }
            ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use {
                chooseCamera()
                dialog.await(Choice.WHILE_USING)
                assertEquals(0, monitor.hits)
                dialog.click(Choice.WHILE_USING)
                compose.waitUntil(10_000) { monitor.hits == 1 }
                compose.onNodeWithText("Capture").assertIsDisplayed()
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithContentDescription("Close conversation").assertIsDisplayed()
                compose.onNodeWithText("Camera permission fixture").assertIsDisplayed()
                assertTrue(session.state.value.attachments.isEmpty())
                assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.CAMERA))
            }
        } finally {
            automation.serviceInfo = automation.serviceInfo.apply { flags = oldFlags }
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun chooseCamera() {
        compose.onNodeWithContentDescription("Add attachment").performClick()
        compose.onNodeWithText("Camera").performClick()
    }

}
