package dev.edgecompanion.app.inference

import android.app.Activity
import android.app.Application
import android.os.Bundle
import dev.edgecompanion.core.inference.ModelManager
import dev.edgecompanion.app.MainActivity
import kotlinx.coroutines.*

class ModelRuntimeLifecycle(private val manager: ModelManager) : Application.ActivityLifecycleCallbacks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val started = mutableSetOf<Activity>()
    private var background: Job? = null
    override fun onActivityStarted(activity: Activity) {
        if (activity !is MainActivity) return
        started += activity
        background?.cancel()
    }
    override fun onActivityStopped(activity: Activity) {
        if (activity !is MainActivity) return
        started -= activity
        background?.cancel()
        if (started.isEmpty()) background = scope.launch {
            // Allow configuration recreation and main/drawer handoff to establish the next host.
            delay(if (activity.isChangingConfigurations) 2_000 else 750)
            if (started.isEmpty()) manager.release()
        }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityDestroyed(activity: Activity) { started -= activity }
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
}
