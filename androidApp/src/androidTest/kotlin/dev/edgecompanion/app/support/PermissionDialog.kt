package dev.edgecompanion.app.support

import android.app.Instrumentation
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.ComposeTestRule

/** Platform-dialog smoke coverage only; request-state tests must not depend on these selectors. */
internal class PermissionDialog(private val instrumentation: Instrumentation, private val compose: ComposeTestRule) {
    enum class Choice(val resourceName: String) {
        DENY("permission_deny_button"), WHILE_USING("permission_allow_foreground_only_button")
    }

    private val supportedControllers = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    private val device = "${Build.MANUFACTURER}/${Build.MODEL}, API ${Build.VERSION.SDK_INT}"
    private val observedPackages = mutableSetOf<String>()

    fun await(choice: Choice): AccessibilityNodeInfo {
        var button: AccessibilityNodeInfo? = null
        try {
            compose.waitUntil(10_000) {
                button = instrumentation.uiAutomation.rootInActiveWindow?.let { find(it, choice.resourceName) }
                button != null
            }
        } catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Permission choice ${choice.name} unavailable ($device, packages=$observedPackages). " +
                "Only AOSP/Google selectors are supported. Add a verified OEM adapter or perform manual QA; " +
                "this is not a pass. No app text or images captured.", failure)
        }
        return requireNotNull(button)
    }

    fun click(choice: Choice) {
        check(await(choice).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "Permission click failed ($device)" }
        instrumentation.waitForIdleSync()
    }

    private fun find(node: AccessibilityNodeInfo, id: String): AccessibilityNodeInfo? {
        val packageName = node.packageName?.toString()
        packageName?.let(observedPackages::add)
        if (packageName in supportedControllers && node.isEnabled && node.isClickable &&
            node.viewIdResourceName?.endsWith(":id/$id") == true) return node
        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { find(it, id) }?.let { return it }
        }
        return null
    }
}
