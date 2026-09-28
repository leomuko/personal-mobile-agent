package dev.edgecompanion.app

import dev.edgecompanion.app.navigation.AssistantIntents
import dev.edgecompanion.app.overlay.MascotView

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostRegressionTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun mainAndDrawerHonorSecurePolicyAfterRecreation() {
        ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
            main.onActivity { assertSecure(it.window.attributes.flags) }
            main.recreate()
            main.onActivity { assertSecure(it.window.attributes.flags) }
        }
        ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use { drawer ->
            drawer.onActivity { assertSecure(it.window.attributes.flags) }
            drawer.recreate()
            drawer.onActivity { assertSecure(it.window.attributes.flags) }
        }
    }

    @Test fun cameraHostHonorsSecurePolicyAfterRecreation() {
            ActivityScenario.launch<CaptureActivity>(Intent(context, CaptureActivity::class.java)).use { capture ->
                capture.onActivity { assertSecure(it.window.attributes.flags) }
                capture.recreate()
                capture.onActivity { assertSecure(it.window.attributes.flags) }
            }
    }

    @Test fun drawerUsesAnIsolatedPrivateTaskAndExpandReusesMain() {
        ActivityScenario.launch<MainActivity>(AssistantIntents.main(context)).use { main ->
            var mainTask = -1
            main.onActivity { mainTask = it.taskId; it.moveTaskToBack(true) }
            ActivityScenario.launch<DrawerActivity>(AssistantIntents.drawer(context)).use { drawer ->
                drawer.onActivity {
                    assertNotEquals(mainTask, it.taskId)
                    val info = context.packageManager.getActivityInfo(it.componentName, 0)
                    assertEquals("${context.packageName}.drawer", info.taskAffinity)
                    assertFalse(info.exported)
                    assertTrue(info.flags and ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
                    it.startActivity(AssistantIntents.main(it))
                    it.finishAndRemoveTask()
                }
                instrumentation.waitForIdleSync()
                val tasks = context.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { it.taskInfo }
                val mainTasks = tasks.filter { it.baseActivity?.className == MainActivity::class.java.name }
                assertEquals(1, mainTasks.size)
                assertEquals(mainTask, mainTasks.single().taskId)
                assertFalse(tasks.any { it.baseActivity?.className == DrawerActivity::class.java.name })
            }
        }
    }

    @Test fun accessibleOpenAndStopUseTheNormalClickHandlers() {
        instrumentation.runOnMainSync {
            val view = MascotView(context)
            var opened = 0; var stopped = 0
            view.setOnClickListener { opened++ }
            view.setOnLongClickListener { stopped++; true }
            assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
            assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null))
            assertEquals(1, opened); assertEquals(1, stopped)
        }
    }

    @Test fun dragAndCancelNeverInvokeClickInEitherLayoutDirection() {
        instrumentation.runOnMainSync {
            for (direction in listOf(View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL)) {
                val view = MascotView(context).apply { layoutDirection = direction }
                var clicks = 0; var distance = 0
                view.setOnClickListener { clicks++ }
                view.onDrag = { x, _ -> distance += x }
                fun event(action: Int, x: Float) {
                    MotionEvent.obtain(0, 1, action, x, 10f, 0).also { view.onTouchEvent(it); it.recycle() }
                }
                event(MotionEvent.ACTION_DOWN, 10f)
                event(MotionEvent.ACTION_MOVE, 210f)
                event(MotionEvent.ACTION_UP, 210f)
                assertEquals(200, distance); assertEquals(0, clicks)
                event(MotionEvent.ACTION_DOWN, 10f); event(MotionEvent.ACTION_CANCEL, 10f)
                event(MotionEvent.ACTION_UP, 10f); assertEquals(0, clicks)
                event(MotionEvent.ACTION_DOWN, 10f); event(MotionEvent.ACTION_UP, 10f)
                assertEquals(1, clicks)
            }
        }
    }

    private fun assertSecure(flags: Int) {
        assertEquals(BuildConfig.SECURE_WINDOWS, flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
    }
}
