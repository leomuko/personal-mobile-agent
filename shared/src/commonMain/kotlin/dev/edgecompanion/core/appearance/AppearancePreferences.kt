package dev.edgecompanion.core.appearance

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ThemeMode(val storedValue: String) {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        fun fromStored(value: String?): ThemeMode = entries.firstOrNull { it.storedValue == value } ?: SYSTEM
    }
}

interface AppearanceStore {
    /** Small, non-sensitive bootstrap value; no personal database access. */
    fun read(): ThemeMode
    suspend fun write(mode: ThemeMode)
}

enum class AppearanceError { LOAD_FAILED, SAVE_FAILED }
data class AppearanceState(val mode: ThemeMode = ThemeMode.SYSTEM, val saving: Boolean = false,
    val error: AppearanceError? = null, val retryMode: ThemeMode? = null)

class AppearancePreferences(private val store: AppearanceStore, dispatcher: CoroutineDispatcher) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val writes = Mutex()
    private val requests = MutableStateFlow(0L)
    private val mutable = MutableStateFlow(try { AppearanceState(mode = store.read()) }
        catch (_: Exception) { AppearanceState(error = AppearanceError.LOAD_FAILED) })
    val state = mutable.asStateFlow()

    fun select(mode: ThemeMode): Job {
        val request = requests.updateAndGet { it + 1 }
        return scope.launch {
            writes.withLock {
                if (request != requests.value) return@withLock
                if (mode == mutable.value.mode && mutable.value.error == null) return@withLock
                mutable.update { it.copy(saving = true, error = null, retryMode = null) }
                // A tiny preference transaction must publish its committed value even if its caller leaves.
                withContext(NonCancellable) {
                    try {
                        store.write(mode)
                        mutable.value = AppearanceState(mode = mode)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Reconcile a store that committed before reporting a platform/rollback failure.
                        val stored = try { store.read() } catch (_: Exception) { mutable.value.mode }
                        mutable.update { it.copy(mode = stored, error = AppearanceError.SAVE_FAILED, retryMode = mode) }
                    } finally { mutable.update { it.copy(saving = false) } }
                }
            }
        }
    }

    fun close() { scope.cancel() }
}
