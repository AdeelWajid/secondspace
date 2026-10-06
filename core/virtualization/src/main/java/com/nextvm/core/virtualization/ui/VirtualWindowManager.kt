package com.nextvm.core.virtualization.ui

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.util.DisplayMetrics
import android.util.Rational
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Toast
import com.nextvm.core.common.AndroidCompat
import com.nextvm.core.common.findField
import com.nextvm.core.common.runSafe
import timber.log.Timber
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Virtual Window Manager — comprehensive window/display system for guest apps.
 *
 * Responsibilities:
 * - Window lifecycle management (application, dialog, popup, toast, overlay, PiP)
 * - System UI interaction (status bar, navigation bar, cutout, insets)
 * - Display metrics spoofing (per-app resolution, density, screen size override)
 * - Multi-window / split-screen / freeform handling
 * - Picture-in-Picture (PiP) support with aspect ratio control
 * - System overlay (TYPE_APPLICATION_OVERLAY) with per-app limits
 * - Toast queue management with spam protection
 * - Orientation locking / forced rotation
 * - WindowManager.LayoutParams sanitization for security
 * - Soft keyboard visibility tracking
 * - Decor view manipulation (system bars, immersive mode)
 *
 * Integration points:
 * - WindowManagerProxy intercepts addView/removeView/updateViewLayout
 * - ActivityThreadHook calls setStatusBarColor/setFullscreen after Activity.attach()
 * - VirtualConfigurationManager dispatches config changes here
 * - SystemServiceProxyManager routes IWindowManager calls here
 */
@Singleton
class VirtualWindowManager @Inject constructor() {

    companion object {
        private const val TAG = "VirtualWindowMgr"

        // Window type categories
        const val WINDOW_TYPE_APPLICATION = 1
        const val WINDOW_TYPE_DIALOG = 2
        const val WINDOW_TYPE_POPUP = 3
        const val WINDOW_TYPE_TOAST = 4
        const val WINDOW_TYPE_OVERLAY = 5
        const val WINDOW_TYPE_PIP = 6
        const val WINDOW_TYPE_INPUT_METHOD = 7

        // Limits
        private const val MAX_OVERLAYS_PER_APP = 10
        private const val MAX_TOAST_QUEUE = 5
        private const val MAX_WINDOWS_PER_APP = 50
        private const val TOAST_COOLDOWN_MS = 500L
    }

    private var initialized = false
    private lateinit var appContext: Context
    private var hostWindowManager: WindowManager? = null

    // Per-instance window states
    private val windowStates = ConcurrentHashMap<String, AppWindowState>()

    // Active overlays across all apps
    private val activeOverlays = ConcurrentHashMap<String, MutableList<VirtualWindowRecord>>()

    // Display metrics overrides per instance: instanceId -> DisplayMetricsOverride
    private val displayOverrides = ConcurrentHashMap<String, DisplayMetricsOverride>()

    // Keyboard visibility tracking per instance
    private val keyboardVisible = ConcurrentHashMap<String, Boolean>()

    // Window lifecycle listeners
    private val windowListeners = CopyOnWriteArrayList<WindowLifecycleListener>()

    /**
     * Initialize the window manager.
     */
    fun initialize(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        hostWindowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        initialized = true
        Timber.tag(TAG).i("VirtualWindowManager initialized — Window management ready")
    }

    // ==================== Data Classes ====================

    /**
     * Tracks a virtual window created by a guest app.
     */
    data class VirtualWindowRecord(
        val instanceId: String,
        val windowType: Int,
        val view: WeakReference<View>,
        val params: WindowManager.LayoutParams,
        val windowId: Int,
        val createdAt: Long = System.currentTimeMillis(),
        var isVisible: Boolean = true,
        var zOrder: Int = 0
    )

    /**
     * Per-app window + display state tracking.
     */
    data class AppWindowState(
        val instanceId: String,
        var statusBarColor: Int = 0,
        var navigationBarColor: Int = 0,
        var isFullscreen: Boolean = false,
        var isInMultiWindow: Boolean = false,
        var isInPictureInPicture: Boolean = false,
        var requestedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
        var isImmersiveMode: Boolean = false,
        var isKeyboardVisible: Boolean = false,
        var currentDecorFitsSystemWindows: Boolean = true,
        var systemBarsVisible: Boolean = true,
        val windows: MutableList<VirtualWindowRecord> = mutableListOf(),
        val toastQueue: MutableList<ToastRecord> = mutableListOf(),
        var lastToastTime: Long = 0,
        var nextWindowId: Int = 1
    )

    data class ToastRecord(
        val text: CharSequence,
        val duration: Int,
        val instanceId: String,
        val createdAt: Long = System.currentTimeMillis()
    )

    /**
     * Display metrics override for per-app display spoofing.
     */
    data class DisplayMetricsOverride(
        val widthPixels: Int? = null,
        val heightPixels: Int? = null,
        val densityDpi: Int? = null,
        val scaledDensity: Float? = null,
        val xdpi: Float? = null,
        val ydpi: Float? = null,
        val widthDp: Int? = null,
        val heightDp: Int? = null
    )

    // ==================== Window State Access ====================

    /**
     * Get or create window state for an app instance.
     */
    fun getWindowState(instanceId: String): AppWindowState {
        return windowStates.getOrPut(instanceId) { AppWindowState(instanceId) }
    }

    // ==================== Window Lifecycle Management ====================

    /**
     * Handle guest app requesting to add a view/window.
     * Called from WindowManagerProxy intercept.
     *
     * @return true if the window was added successfully, false if denied
     */
    fun addWindow(
        instanceId: String,
        view: View,
        params: WindowManager.LayoutParams,
        context: Context
    ): Boolean {
        val state = getWindowState(instanceId)

        // Total window limit per app
        if (state.windows.size >= MAX_WINDOWS_PER_APP) {
            Timber.tag(TAG).w("Window limit reached for $instanceId (${state.windows.size}/$MAX_WINDOWS_PER_APP)")
            return false
        }

        val windowType = classifyWindowType(params)

        // Check overlay limits
        if (windowType == WINDOW_TYPE_OVERLAY) {
            val overlayCount = state.windows.count { it.windowType == WINDOW_TYPE_OVERLAY }
            if (overlayCount >= MAX_OVERLAYS_PER_APP) {
                Timber.tag(TAG).w("Overlay limit reached for $instanceId ($overlayCount/$MAX_OVERLAYS_PER_APP)")
                return false
            }

            // Android 8+ requires TYPE_APPLICATION_OVERLAY for system alerts
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
        }

        // Sanitize window params for security
        sanitizeWindowParams(params, windowType)

        val windowId = state.nextWindowId++
        val record = VirtualWindowRecord(
            instanceId = instanceId,
            windowType = windowType,
            view = WeakReference(view),
            params = params,
            windowId = windowId,
            zOrder = state.windows.size
        )
        state.windows.add(record)

        if (windowType == WINDOW_TYPE_OVERLAY) {
            activeOverlays.getOrPut(instanceId) { mutableListOf() }.add(record)
        }

        // Notify listeners
        windowListeners.forEach {
            runSafe(TAG) { it.onWindowAdded(instanceId, windowId, windowType) }
        }

        Timber.tag(TAG).d("Window added for $instanceId: id=$windowId, type=$windowType")
        return true
    }

    /**
     * Handle guest app updating a window's layout params.
     */
    fun updateWindowLayout(
        instanceId: String,
        view: View,
        params: WindowManager.LayoutParams
    ) {
        val state = getWindowState(instanceId)
        val record = state.windows.find { it.view.get() === view }
        if (record != null) {
            sanitizeWindowParams(params, record.windowType)
            Timber.tag(TAG).d("Window layout updated for $instanceId: id=${record.windowId}")
        }
    }

    /**
     * Handle guest app removing a window.
     */
    fun removeWindow(instanceId: String, view: View) {
        val state = getWindowState(instanceId)
        val removed = state.windows.find { it.view.get() === view }
        state.windows.removeAll { it.view.get() === view || it.view.get() == null }
        activeOverlays[instanceId]?.removeAll { it.view.get() === view || it.view.get() == null }

        if (removed != null) {
            windowListeners.forEach {
                runSafe(TAG) { it.onWindowRemoved(instanceId, removed.windowId, removed.windowType) }
            }
        }

        Timber.tag(TAG).d("Window removed for $instanceId")
    }

    /**
     * Remove all windows for an app (on stop/uninstall).
     */
    fun removeAllWindows(instanceId: String, windowManager: WindowManager?) {
        val state = windowStates.remove(instanceId)
        state?.windows?.forEach { record ->
            try {
                record.view.get()?.let { view ->
                    windowManager?.removeViewImmediate(view)
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to remove window for $instanceId")
            }
        }
        activeOverlays.remove(instanceId)
        Timber.tag(TAG).d("All windows removed for $instanceId")
    }

    // ==================== System UI (Status Bar, Nav Bar) ====================

    /**
     * Handle guest app setting status bar color.
     */
    fun setStatusBarColor(instanceId: String, activity: Activity, color: Int) {
        val state = getWindowState(instanceId)
        state.statusBarColor = color
        runSafe(TAG) { activity.window.statusBarColor = color }
    }

    /**
     * Handle guest app setting navigation bar color.
     */
    fun setNavigationBarColor(instanceId: String, activity: Activity, color: Int) {
        val state = getWindowState(instanceId)
        state.navigationBarColor = color
        runSafe(TAG) { activity.window.navigationBarColor = color }
    }

    /**
     * Handle guest app setting status bar light appearance (dark icons on light bg).
     */
    fun setStatusBarLightAppearance(activity: Activity, lightStatusBar: Boolean) {
        runSafe(TAG) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                activity.window.insetsController?.setSystemBarsAppearance(
                    if (lightStatusBar) {
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    } else {
                        0
                    },
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                val flags = activity.window.decorView.systemUiVisibility
                activity.window.decorView.systemUiVisibility = if (lightStatusBar) {
                    flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else {
                    flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                }
            }
        }
    }

    /**
     * Handle guest app requesting fullscreen mode.
     */
    @Suppress("DEPRECATION")
    fun setFullscreen(instanceId: String, activity: Activity, fullscreen: Boolean) {
        val state = getWindowState(instanceId)
        state.isFullscreen = fullscreen

        runSafe(TAG) {
            if (fullscreen) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.let {
                        it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                        it.systemBarsBehavior =
                            android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    activity.window.decorView.systemUiVisibility =
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                }
                state.isImmersiveMode = true
                state.systemBarsVisible = false
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    activity.window.insetsController?.show(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                } else {
                    activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
                }
                state.isImmersiveMode = false
                state.systemBarsVisible = true
            }
        }
    }

    /**
     * Handle guest app setting decorFitsSystemWindows.
     * Android 11+ edge-to-edge rendering.
     */
    fun setDecorFitsSystemWindows(instanceId: String, activity: Activity, decorFits: Boolean) {
        val state = getWindowState(instanceId)
        state.currentDecorFitsSystemWindows = decorFits
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runSafe(TAG) { activity.window.setDecorFitsSystemWindows(decorFits) }
        }
    }

    // ==================== Multi-Window ====================

    /**
     * Handle multi-window mode entry/exit.
     */
    fun onMultiWindowModeChanged(instanceId: String, isInMultiWindowMode: Boolean) {
        val state = getWindowState(instanceId)
        state.isInMultiWindow = isInMultiWindowMode
        Timber.tag(TAG).d("Multi-window mode changed for $instanceId: $isInMultiWindowMode")
    }

    /**
     * Handle Picture-in-Picture mode.
     */
    fun onPictureInPictureModeChanged(instanceId: String, isInPipMode: Boolean) {
        val state = getWindowState(instanceId)
        state.isInPictureInPicture = isInPipMode
        Timber.tag(TAG).d("PiP mode changed for $instanceId: $isInPipMode")
    }

    /**
     * Check if the device supports PiP (API 26+).
     */
    fun supportsPictureInPicture(activity: Activity): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
               activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    /**
     * Enter PiP mode for guest app.
     */
    fun enterPictureInPicture(
        instanceId: String,
        activity: Activity,
        aspectRatio: Rational? = null,
        autoEnterEnabled: Boolean = false,
        seamlessResizeEnabled: Boolean = true
    ): Boolean {
        if (!supportsPictureInPicture(activity)) return false

        return runSafe(TAG) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val builder = PictureInPictureParams.Builder()
                aspectRatio?.let { builder.setAspectRatio(it) }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(autoEnterEnabled)
                    builder.setSeamlessResizeEnabled(seamlessResizeEnabled)
                }

                activity.enterPictureInPictureMode(builder.build())
                getWindowState(instanceId).isInPictureInPicture = true
                true
            } else false
        } ?: false
    }

    /**
     * Update PiP params (e.g., change aspect ratio while in PiP).
     */
    fun updatePictureInPictureParams(
        activity: Activity,
        aspectRatio: Rational? = null,
        autoEnterEnabled: Boolean = false
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runSafe(TAG) {
                val builder = PictureInPictureParams.Builder()
                aspectRatio?.let { builder.setAspectRatio(it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setAutoEnterEnabled(autoEnterEnabled)
                }
                activity.setPictureInPictureParams(builder.build())
            }
        }
    }

    // ==================== Orientation ====================

    /**
     * Handle guest app requesting screen orientation.
     */
    fun setRequestedOrientation(instanceId: String, activity: Activity, orientation: Int) {
        val state = getWindowState(instanceId)
        state.requestedOrientation = orientation
        runSafe(TAG) { activity.requestedOrientation = orientation }
    }

    /**
     * Get the current orientation for a guest app.
     */
    fun getRequestedOrientation(instanceId: String): Int {
        return getWindowState(instanceId).requestedOrientation
    }

    // ==================== Toast Management ====================

    /**
     * Show a toast from a guest app (isolated from host).
     * Includes spam protection with cooldown.
     */
    fun showToast(instanceId: String, context: Context, text: CharSequence, duration: Int) {
        val state = getWindowState(instanceId)
        val now = System.currentTimeMillis()

        // Spam protection: enforce cooldown between toasts
        if (now - state.lastToastTime < TOAST_COOLDOWN_MS) {
            Timber.tag(TAG).d("Toast suppressed for $instanceId (cooldown)")
            return
        }

        // Limit toast queue to prevent memory leaks
        if (state.toastQueue.size >= MAX_TOAST_QUEUE) {
            state.toastQueue.removeFirst()
        }

        state.toastQueue.add(ToastRecord(text, duration, instanceId))
        state.lastToastTime = now

        runSafe(TAG) {
            Toast.makeText(context, text, duration).show()
        }
    }

    // ==================== Display Metrics Spoofing ====================

    /**
     * Set display metrics override for a guest app.
     * Used for per-app resolution/density spoofing.
     */
    fun setDisplayMetricsOverride(instanceId: String, override: DisplayMetricsOverride) {
        displayOverrides[instanceId] = override
        Timber.tag(TAG).d("Display metrics override set for $instanceId: " +
            "${override.widthPixels}x${override.heightPixels} @ ${override.densityDpi}dpi")
    }

    /**
     * Clear display metrics override for a guest app.
     */
    fun clearDisplayMetricsOverride(instanceId: String) {
        displayOverrides.remove(instanceId)
        Timber.tag(TAG).d("Display metrics override cleared for $instanceId")
    }

    /**
     * Get the display metrics for a guest app (with overrides applied).
     * Returns spoofed metrics if an override exists, otherwise real metrics.
     */
    fun getDisplayMetrics(instanceId: String): DisplayMetrics {
        val realMetrics = DisplayMetrics()

        runSafe(TAG) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = hostWindowManager?.currentWindowMetrics?.bounds
                realMetrics.widthPixels = bounds?.width() ?: 1080
                realMetrics.heightPixels = bounds?.height() ?: 2400
            } else {
                @Suppress("DEPRECATION")
                hostWindowManager?.defaultDisplay?.getMetrics(realMetrics)
            }
        }

        // Apply overrides if present
        val override = displayOverrides[instanceId]
        if (override != null) {
            override.widthPixels?.let { realMetrics.widthPixels = it }
            override.heightPixels?.let { realMetrics.heightPixels = it }
            override.densityDpi?.let { realMetrics.densityDpi = it }
            override.scaledDensity?.let { realMetrics.scaledDensity = it }
            override.xdpi?.let { realMetrics.xdpi = it }
            override.ydpi?.let { realMetrics.ydpi = it }
            // Recalculate density from dpi
            override.densityDpi?.let {
                realMetrics.density = it / 160f
            }
        }

        return realMetrics
    }

    /**
     * Apply display metrics override to a guest app's activity via reflection.
     * Modifies the Activity's Configuration and Resources to reflect the spoofed metrics.
     */
    fun applyDisplayMetricsToActivity(instanceId: String, activity: Activity) {
        val override = displayOverrides[instanceId] ?: return

        runSafe(TAG) {
            val spoofedMetrics = getDisplayMetrics(instanceId)

            // Update the Activity's resources display metrics
            val resources = activity.resources
            val dm = resources.displayMetrics
            override.widthPixels?.let { dm.widthPixels = it }
            override.heightPixels?.let { dm.heightPixels = it }
            override.densityDpi?.let {
                dm.densityDpi = it
                dm.density = it / 160f
            }
            override.scaledDensity?.let { dm.scaledDensity = it }

            // Update Configuration
            val config = resources.configuration
            override.widthDp?.let { config.screenWidthDp = it }
            override.heightDp?.let { config.screenHeightDp = it }
            override.densityDpi?.let { config.densityDpi = it }

            // Force resources to pick up the changes
            @Suppress("DEPRECATION")
            resources.updateConfiguration(config, dm)

            Timber.tag(TAG).d("Display metrics applied to activity for $instanceId")
        }
    }

    /**
     * Intercept Display.getMetrics/getRealMetrics calls to return spoofed values.
     * Called by SystemServiceProxyManager's IWindowManager proxy.
     */
    fun interceptGetMetrics(instanceId: String, outMetrics: DisplayMetrics) {
        val override = displayOverrides[instanceId] ?: return
        override.widthPixels?.let { outMetrics.widthPixels = it }
        override.heightPixels?.let { outMetrics.heightPixels = it }
        override.densityDpi?.let {
            outMetrics.densityDpi = it
            outMetrics.density = it / 160f
        }
        override.scaledDensity?.let { outMetrics.scaledDensity = it }
        override.xdpi?.let { outMetrics.xdpi = it }
        override.ydpi?.let { outMetrics.ydpi = it }
    }

    // ==================== Keyboard Tracking ====================

    /**
     * Track soft keyboard visibility for a guest app.
     */
    fun onKeyboardVisibilityChanged(instanceId: String, visible: Boolean) {
        val state = getWindowState(instanceId)
        state.isKeyboardVisible = visible
        keyboardVisible[instanceId] = visible
        Timber.tag(TAG).d("Keyboard visibility for $instanceId: $visible")
    }

    /**
     * Check if keyboard is currently visible for a guest app.
     */
    fun isKeyboardVisible(instanceId: String): Boolean {
        return keyboardVisible[instanceId] ?: false
    }

    // ==================== Configuration Changes ====================

    /**
     * Determine whether an activity should be recreated on config change.
     * Based on android:configChanges declared flags.
     *
     * @param declaredConfigChanges The configChanges bitmask from the manifest
     * @param changeMask The actual change bitmask from Configuration.diff()
     * @return true if activity handles change itself, false if it should be recreated
     */
    fun shouldHandleConfigChange(declaredConfigChanges: Int, changeMask: Int): Boolean {
        return (declaredConfigChanges and changeMask) == changeMask
    }

    /**
     * Dispatch onConfigurationChanged to a guest activity.
     */
    fun dispatchConfigurationChanged(activity: Activity, newConfig: Configuration) {
        runSafe(TAG) { activity.onConfigurationChanged(newConfig) }
    }

    // ==================== Window Lifecycle Listeners ====================

    /**
     * Register a window lifecycle listener.
     */
    fun addWindowListener(listener: WindowLifecycleListener) {
        windowListeners.add(listener)
    }

    /**
     * Remove a window lifecycle listener.
     */
    fun removeWindowListener(listener: WindowLifecycleListener) {
        windowListeners.remove(listener)
    }

    // ==================== Helpers ====================

    /**
     * Classify a WindowManager.LayoutParams into our window type categories.
     */
    private fun classifyWindowType(params: WindowManager.LayoutParams): Int {
        return when (params.type) {
            WindowManager.LayoutParams.TYPE_APPLICATION,
            WindowManager.LayoutParams.TYPE_BASE_APPLICATION -> WINDOW_TYPE_APPLICATION

            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL -> WINDOW_TYPE_POPUP

            WindowManager.LayoutParams.TYPE_TOAST -> WINDOW_TYPE_TOAST

            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY -> WINDOW_TYPE_OVERLAY

            WindowManager.LayoutParams.TYPE_INPUT_METHOD -> WINDOW_TYPE_INPUT_METHOD

            else -> {
                if (params.type >= WindowManager.LayoutParams.FIRST_SUB_WINDOW &&
                    params.type <= WindowManager.LayoutParams.LAST_SUB_WINDOW
                ) {
                    WINDOW_TYPE_DIALOG
                } else {
                    WINDOW_TYPE_APPLICATION
                }
            }
        }
    }

    /**
     * Sanitize window params for security — prevent guest apps from creating
     * system-level windows they shouldn't have access to.
     */
    private fun sanitizeWindowParams(params: WindowManager.LayoutParams, windowType: Int) {
        when (windowType) {
            WINDOW_TYPE_OVERLAY -> {
                // Force to APPLICATION_OVERLAY (requires SYSTEM_ALERT_WINDOW permission)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                }
                params.format = PixelFormat.TRANSLUCENT
            }
            WINDOW_TYPE_TOAST -> {
                params.type = WindowManager.LayoutParams.TYPE_TOAST
                params.flags = params.flags or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            }
            WINDOW_TYPE_INPUT_METHOD -> {
                // Don't allow guest apps to create input method windows
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION
            }
        }

        // Block dangerous flags
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()

        // Prevent guest apps from requesting system window types (security)
        if (params.type >= WindowManager.LayoutParams.FIRST_SYSTEM_WINDOW &&
            params.type <= WindowManager.LayoutParams.LAST_SYSTEM_WINDOW
        ) {
            if (params.type != WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY &&
                params.type != WindowManager.LayoutParams.TYPE_TOAST
            ) {
                Timber.tag(TAG).w("Blocked system window type: ${params.type}, downgrading to APPLICATION")
                params.type = WindowManager.LayoutParams.TYPE_APPLICATION
            }
        }
    }

    /**
     * Clean up everything for an instance (on uninstall/stop).
     */
    fun cleanup(instanceId: String) {
        windowStates.remove(instanceId)
        activeOverlays.remove(instanceId)
        displayOverrides.remove(instanceId)
        keyboardVisible.remove(instanceId)
        Timber.tag(TAG).d("Cleaned up window state for $instanceId")
    }

    /**
     * Get window count statistics.
     */
    fun getWindowStats(): Map<String, Int> {
        return windowStates.mapValues { (_, state) -> state.windows.size }
    }

    /**
     * Get all overlays for an instance.
     */
    fun getActiveOverlays(instanceId: String): List<VirtualWindowRecord> {
        return activeOverlays[instanceId]?.toList() ?: emptyList()
    }

    /**
     * Check if any overlays are active for an instance.
     */
    fun hasActiveOverlays(instanceId: String): Boolean {
        return (activeOverlays[instanceId]?.size ?: 0) > 0
    }

    /**
     * Debug dump of all window state.
     */
    fun dump(): String {
        val sb = StringBuilder()
        sb.appendLine("=== VirtualWindowManager Dump ===")
        sb.appendLine("Total apps with windows: ${windowStates.size}")
        sb.appendLine("Display overrides: ${displayOverrides.size}")
        sb.appendLine()

        for ((id, state) in windowStates) {
            sb.appendLine("[$id]")
            sb.appendLine("  Windows: ${state.windows.size}")
            sb.appendLine("  Fullscreen: ${state.isFullscreen}")
            sb.appendLine("  Immersive: ${state.isImmersiveMode}")
            sb.appendLine("  Multi-window: ${state.isInMultiWindow}")
            sb.appendLine("  PiP: ${state.isInPictureInPicture}")
            sb.appendLine("  Orientation: ${state.requestedOrientation}")
            sb.appendLine("  Keyboard: ${state.isKeyboardVisible}")
            state.windows.forEachIndexed { i, w ->
                sb.appendLine("  Window[$i]: type=${w.windowType}, visible=${w.isVisible}, z=${w.zOrder}")
            }
            displayOverrides[id]?.let { o ->
                sb.appendLine("  Display override: ${o.widthPixels}x${o.heightPixels} @ ${o.densityDpi}dpi")
            }
            sb.appendLine()
        }

        return sb.toString()
    }
}

/**
 * Listener for window lifecycle events.
 */
interface WindowLifecycleListener {
    fun onWindowAdded(instanceId: String, windowId: Int, windowType: Int) {}
    fun onWindowRemoved(instanceId: String, windowId: Int, windowType: Int) {}
}
