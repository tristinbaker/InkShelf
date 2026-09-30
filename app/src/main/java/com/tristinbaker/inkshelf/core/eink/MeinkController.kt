package com.tristinbaker.inkshelf.core.eink

import android.content.Context
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the diagnostics screen needs to explain what the e-ink layer is doing. */
data class EinkStatus(
    val serviceFound: Boolean = false,
    val activeMode: EinkMode? = null,
    val lastError: String? = null,
    val transactCount: Int = 0,
)

/**
 * Owns the connection to the Kompakt's private display-mode service.
 *
 * There is no public Mudita SDK and Mudita has said they will not ship one, so
 * this is best-effort: if anything at all goes wrong the app keeps working with
 * the panel in whatever state the system left it, and reports why.
 */
class MeinkController(private val context: Context) {

    private val _status = MutableStateFlow(EinkStatus())
    val status: StateFlow<EinkStatus> = _status.asStateFlow()

    val isAvailable: Boolean get() = _status.value.serviceFound

    private var binder: MeinkBinder? = null
    private var initAttempted = false

    /** Resolves the hidden service. Safe to call repeatedly. Never throws. */
    fun init(force: Boolean = false) {
        val existing = binder
        if (existing != null) {
            _status.value = _status.value.copy(serviceFound = true, lastError = null)
            return
        }
        // On a device without the service this can never succeed, so only try
        // once. Otherwise every call site re-reflects and re-logs.
        if (initAttempted && !force) return
        initAttempted = true
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
            val remote = getService.invoke(null, MeinkBinder.SERVICE_NAME) as? IBinder
            if (remote == null) {
                binder = null
                _status.value = _status.value.copy(
                    serviceFound = false,
                    lastError = "ServiceManager.getService(\"${MeinkBinder.SERVICE_NAME}\") returned null",
                )
                Log.i(TAG, "no ${MeinkBinder.SERVICE_NAME} service on this device; panel mode left to the system")
                return
            }
            binder = MeinkBinder(remote)
            _status.value = _status.value.copy(serviceFound = true, lastError = null)
            Log.i(TAG, "meink service bound")
        } catch (t: Throwable) {
            // Includes SecurityException on devices with a non-Mudita framework.
            binder = null
            _status.value = _status.value.copy(
                serviceFound = false,
                lastError = "${t.javaClass.simpleName}: ${t.message}",
            )
            Log.w(TAG, "meink service unavailable", t)
        }
    }

    /**
     * Applies [mode]. Returns false if the panel could not be driven, which is
     * the normal outcome on every non-Kompakt device.
     */
    fun setMode(mode: EinkMode): Boolean {
        val target = binder ?: run {
            init()
            binder
        } ?: return false

        return try {
            target.setDisplayMode(context.packageName, mode.code)
            _status.value = _status.value.copy(
                serviceFound = true,
                activeMode = mode,
                lastError = null,
                transactCount = _status.value.transactCount + 1,
            )
            true
        } catch (t: Throwable) {
            _status.value = _status.value.copy(
                activeMode = null,
                lastError = "${t.javaClass.simpleName}: ${t.message}",
                transactCount = _status.value.transactCount + 1,
            )
            Log.w(TAG, "setDisplayMode($mode) failed", t)
            false
        }
    }

    /**
     * Re-asserts the mode after the activity resumes. The panel does not always
     * hold the requested mode across app restarts or configuration changes.
     */
    fun reapply(mode: EinkMode) {
        if (_status.value.activeMode != mode) setMode(mode)
    }

    /**
     * Kicks a CLEAR pass to shed accumulated ghosting, then returns to [restore].
     * The full-clear panel mode is not a readable display mode, so it must never
     * be left applied.
     */
    fun flashFullRefresh(restore: EinkMode) {
        if (!isAvailable) return
        setMode(EinkMode.CLEAR)
        setMode(restore)
    }

    private companion object {
        const val TAG = "MeinkController"
    }
}
