package com.nextvm.core.virtualization.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.nextvm.core.hook.GameSpeed
import com.nextvm.core.model.VirtualConstants
import com.nextvm.core.model.VirtualIntentExtras
import com.nextvm.core.virtualization.R
import com.nextvm.core.virtualization.engine.GuestClassLoaderRegistry
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import kotlin.math.abs

/**
 * Draggable bubble on the virtual app that is in front.
 * That app is selected automatically. Tap the bubble for speed, then pick a method.
 */
class FloatingSpaceIconController(
    private val app: Application
) : Application.ActivityLifecycleCallbacks {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager by lazy {
        app.getSystemService(WindowManager::class.java)
    }
    private var root: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var panel: LinearLayout? = null
    private var speedIcon: View? = null
    private var targetLabel: TextView? = null
    private val methodViews = LinkedHashMap<GameSpeed.Method, TextView>()
    private val speedViews = LinkedHashMap<Float, TextView>()
    private val resumedIds = LinkedHashSet<Int>()
    private var speedIconVisible = false
    private var methodsVisible = false
    private var activityAnchored = false
    private var hostActivity: Activity? = null
    private var attachedWindowManager: WindowManager? = null
    private var posX = -1
    private var posY = -1
    private val hideRunnable = Runnable {
        if (resumedIds.isEmpty()) hide()
    }

    init {
        active = this
    }

    override fun onActivityResumed(activity: Activity) {
        onGuestResumed(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        onGuestPaused(activity)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private fun handleGuestResumed(activity: Activity) {
        if (!shouldShow(activity)) return
        resumedIds.add(System.identityHashCode(activity))
        handler.removeCallbacks(hideRunnable)
        if (!FloatingIconSettings.isEnabled(app)) {
            Timber.tag(TAG).i("Floating icon is off")
            return
        }
        scheduleShow(activity)
    }

    private fun handleGuestPaused(activity: Activity) {
        if (!shouldShow(activity)) return
        resumedIds.remove(System.identityHashCode(activity))
        handler.removeCallbacks(hideRunnable)
        if (resumedIds.isEmpty()) {
            handler.postDelayed(hideRunnable, 700)
        }
    }

    private fun shouldShow(activity: Activity): Boolean {
        val name = activity.javaClass.name
        if (name.startsWith("com.nextvm.app.") && !name.startsWith("com.nextvm.app.stub.")) {
            return false
        }
        if (GuestClassLoaderRegistry.isGuestClassLoader(activity.javaClass.classLoader)) return true
        if (!activity.intent?.getStringExtra(VirtualIntentExtras.INSTANCE_ID).isNullOrBlank()) return true
        if (isGuestProcess()) return true
        return name.startsWith("com.nextvm.app.stub.")
    }

    private fun scheduleShow(activity: Activity) {
        val show = Runnable {
            if (resumedIds.isEmpty() || activity.isFinishing || activity.isDestroyed) return@Runnable
            present(activity)
        }
        handler.post(show)
        handler.postDelayed({
            if (root == null) show.run()
        }, 400)
    }

    private fun present(activity: Activity) {
        captureTarget(activity)
        if (root != null) {
            val switchingActivity = activityAnchored && hostActivity !== activity
            if (!switchingActivity) return
            root?.let { detach(it) }
            forget()
        }
        hostActivity = activity
        if (tryPanel(activity)) return
        if (tryOverlay()) return
        tryContent(activity)
    }

    private fun windowParams(type: Int, token: android.os.IBinder?): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            dp(64),
            dp(64),
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (posX >= 0) posX else dp(16)
            y = if (posY >= 0) posY else dp(160)
            if (token != null) this.token = token
        }
    }

    private fun tryOverlay(): Boolean {
        val content = buildContent()
        val params = windowParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        return try {
            windowManager.addView(content, params)
            remember(content, params, windowManager, anchored = false)
            Timber.tag(TAG).i("Floating icon shown as overlay")
            true
        } catch (e: Exception) {
            if (content.parent != null || content.isAttachedToWindow) {
                remember(content, params, windowManager, anchored = false)
                return true
            }
            Timber.tag(TAG).e(e, "Overlay floating icon failed")
            false
        }
    }

    private fun tryPanel(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        val token = decor.windowToken ?: decor.applicationWindowToken ?: return false
        val content = root ?: buildContent()
        val params = windowParams(WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, token)
        return try {
            activity.windowManager.addView(content, params)
            remember(content, params, activity.windowManager, anchored = true)
            Timber.tag(TAG).i("Floating icon shown on the app window")
            true
        } catch (e: Exception) {
            if (content.parent != null || content.isAttachedToWindow) {
                remember(content, params, activity.windowManager, anchored = true)
                return true
            }
            Timber.tag(TAG).e(e, "Panel floating icon failed")
            false
        }
    }

    private fun tryContent(activity: Activity): Boolean {
        val parent = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val content = root ?: buildContent()
        (content.parent as? ViewGroup)?.removeView(content)
        val params = FrameLayout.LayoutParams(dp(64), dp(64), Gravity.TOP or Gravity.START).apply {
            leftMargin = if (posX >= 0) posX else dp(16)
            topMargin = if (posY >= 0) posY else dp(160)
        }
        return try {
            parent.addView(content, params)
            content.translationZ = dp(48).toFloat()
            content.bringToFront()
            root = content
            layoutParams = null
            attachedWindowManager = null
            activityAnchored = true
            Timber.tag(TAG).i("Floating icon attached to app content")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Could not attach floating space icon")
            false
        }
    }

    private fun remember(
        content: View,
        params: WindowManager.LayoutParams,
        manager: WindowManager,
        anchored: Boolean
    ) {
        root = content
        layoutParams = params
        attachedWindowManager = manager
        activityAnchored = anchored
    }

    private fun forget() {
        root = null
        layoutParams = null
        panel = null
        speedIcon = null
        targetLabel = null
        methodViews.clear()
        speedViews.clear()
        attachedWindowManager = null
        activityAnchored = false
    }

    private fun applyWindowSize() {
        val view = root ?: return
        val width = if (methodsVisible) dp(250) else dp(64)
        val height = when {
            methodsVisible -> dp(460)
            speedIconVisible -> dp(140)
            else -> dp(64)
        }
        val params = layoutParams
        val manager = attachedWindowManager
        if (params != null && manager != null) {
            params.width = width
            params.height = height
            try {
                manager.updateViewLayout(view, params)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not resize floating icon")
            }
        } else {
            val frame = view.layoutParams ?: return
            frame.width = width
            frame.height = height
            view.layoutParams = frame
        }
    }

    private fun hide() {
        val view = root ?: return
        speedIconVisible = false
        methodsVisible = false
        detach(view)
        root = null
        layoutParams = null
        panel = null
        speedIcon = null
        targetLabel = null
        methodViews.clear()
        speedViews.clear()
        attachedWindowManager = null
        activityAnchored = false
        hostActivity = null
    }

    private fun detach(view: View) {
        try {
            attachedWindowManager?.removeView(view)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not remove floating space icon")
        }
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun buildContent(): View {
        val context = app
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
        }

        val bubble = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#6750A4"))
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_floating_space)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            addView(icon, FrameLayout.LayoutParams(dp(64), dp(64)))
            contentDescription = "Space"
            setOnTouchListener(dragListener())
        }

        val speed = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#E85D04"))
                setStroke(dp(3), Color.WHITE)
            }
            elevation = dp(8).toFloat()
            visibility = View.GONE
            val icon = ImageView(context).apply {
                setImageResource(R.drawable.ic_speed)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }
            addView(icon, FrameLayout.LayoutParams(dp(56), dp(56)))
            contentDescription = "Speed"
            setOnClickListener { onSpeedIconClick() }
        }
        speedIcon = speed

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.WHITE)
            }
            elevation = dp(6).toFloat()
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(TextView(context).apply {
                text = "Speed"
                setTextColor(Color.parseColor("#1C1B1F"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            })
            val target = TextView(context).apply {
                text = GameSpeed.state.targetName.ifBlank { "This app" }
                setTextColor(Color.parseColor("#6B6570"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(2), 0, dp(8))
            }
            targetLabel = target
            addView(target)
            addView(methodRow(GameSpeed.Method.UNITY, "Unity"))
            addView(methodRow(GameSpeed.Method.CLOCK, "Clock"))
            addView(methodRow(GameSpeed.Method.SLEEP, "Sleep"))
            addView(methodRow(GameSpeed.Method.ANIMATION, "Animation"))
            addView(TextView(context).apply {
                text = "Rate"
                setTextColor(Color.parseColor("#6B6570"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, dp(8), 0, dp(4))
            })
            addView(speedRow())
        }
        panel = card

        container.addView(bubble, LinearLayout.LayoutParams(dp(64), dp(64)))
        container.addView(
            speed,
            LinearLayout.LayoutParams(dp(56), dp(56)).apply { topMargin = dp(8) }
        )
        container.addView(
            card,
            LinearLayout.LayoutParams(dp(226), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(8)
            }
        )
        return container
    }

    private fun dragListener(): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        val slop = dp(8)

        return View.OnTouchListener { _, event ->
            val view = root ?: return@OnTouchListener false
            val overlayParams = layoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = false
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = overlayParams?.x ?: (view.layoutParams as? FrameLayout.LayoutParams)?.leftMargin ?: 0
                    startY = overlayParams?.y ?: (view.layoutParams as? FrameLayout.LayoutParams)?.topMargin ?: 0
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (abs(dx) > slop || abs(dy) > slop) dragging = true
                    if (dragging) {
                        val maxX = (screenWidth() - view.width).coerceAtLeast(0)
                        val maxY = (screenHeight() - view.height).coerceAtLeast(0)
                        val nextX = (startX + dx).toInt().coerceIn(0, maxX)
                        val nextY = (startY + dy).toInt().coerceIn(0, maxY)
                        posX = nextX
                        posY = nextY
                        if (overlayParams != null) {
                            overlayParams.x = nextX
                            overlayParams.y = nextY
                            try {
                                (attachedWindowManager ?: windowManager).updateViewLayout(view, overlayParams)
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "Could not move floating icon")
                            }
                        } else {
                            val frame = view.layoutParams as? FrameLayout.LayoutParams
                            if (frame != null) {
                                frame.leftMargin = nextX
                                frame.topMargin = nextY
                                view.layoutParams = frame
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) onBubbleClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun onBubbleClick() {
        speedIconVisible = !speedIconVisible
        if (!speedIconVisible) methodsVisible = false
        speedIcon?.visibility = if (speedIconVisible) View.VISIBLE else View.GONE
        panel?.visibility = if (methodsVisible) View.VISIBLE else View.GONE
        applyWindowSize()
    }

    private fun onSpeedIconClick() {
        methodsVisible = !methodsVisible
        panel?.visibility = if (methodsVisible) View.VISIBLE else View.GONE
        if (methodsVisible) refreshSelection()
        applyWindowSize()
    }

    private fun methodRow(method: GameSpeed.Method, label: String): TextView {
        return TextView(app).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener {
                GameSpeed.toggle(method)
                refreshSelection()
            }
            methodViews[method] = this
        }
    }

    private fun speedRow(): LinearLayout {
        val row = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0.5f, 1f, 2f, 5f, 10f).forEach { scale ->
            val chip = TextView(app).apply {
                text = if (scale < 1f) "½×" else "${scale.toInt()}×"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setOnClickListener {
                    GameSpeed.setScale(scale)
                    refreshSelection()
                }
            }
            speedViews[scale] = chip
            row.addView(chip, LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                marginEnd = dp(4)
            })
        }
        return row
    }

    private fun refreshSelection() {
        val state = GameSpeed.state
        targetLabel?.text = state.targetName.ifBlank { "This app" }
        methodViews.forEach { (method, view) ->
            val on = method in state.methods
            view.setTextColor(if (on) Color.WHITE else Color.parseColor("#1C1B1F"))
            view.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (on) Color.parseColor("#6750A4") else Color.parseColor("#F3EDF7"))
            }
        }
        speedViews.forEach { (scale, view) ->
            val on = scale == state.scale
            view.setTextColor(if (on) Color.WHITE else Color.parseColor("#1C1B1F"))
            view.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (on) Color.parseColor("#E85D04") else Color.parseColor("#F3EDF7"))
            }
        }
    }

    private fun captureTarget(activity: Activity) {
        val intent = activity.intent
        val instanceId = intent?.getStringExtra(VirtualIntentExtras.INSTANCE_ID)
        val packageName = intent?.getStringExtra(VirtualIntentExtras.TARGET_PACKAGE)
        val slot = currentGuestSlot()
        val installed = readInstalledApps()
        val match = installed.firstOrNull { instanceId != null && it.instanceId == instanceId }
            ?: installed.firstOrNull { !packageName.isNullOrBlank() && it.packageName == packageName }
            ?: installed.firstOrNull { slot >= 0 && it.processSlot == slot }
        val name = match?.appName?.ifBlank { null }
            ?: match?.packageName
            ?: packageName
            ?: "This app"
        GameSpeed.selectTarget(name, match?.packageName ?: packageName.orEmpty())
        targetLabel?.text = name
    }

    private fun currentGuestSlot(): Int {
        return guestSlot(currentProcessName()) ?: -1
    }

    private fun readInstalledApps(): List<InstalledSpaceApp> {
        val dir = File(FloatingIconSettings.hostRoot(app), "${VirtualConstants.VIRTUAL_DIR}/apps")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles().orEmpty()
            .filter { it.extension == "json" }
            .mapNotNull { file ->
                try {
                    val json = JSONObject(file.readText())
                    InstalledSpaceApp(
                        instanceId = json.optString("instanceId").ifBlank { file.nameWithoutExtension },
                        packageName = json.optString("packageName"),
                        appName = json.optString("appName"),
                        apkPath = json.optString("apkPath"),
                        processSlot = json.optInt("processSlot", -1)
                    )
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Could not read ${file.name}")
                    null
                }
            }
            .filter { it.processSlot >= 0 && it.apkPath.isNotBlank() }
    }

    private fun isGuestProcess(): Boolean = guestSlot(currentProcessName()) != null

    private fun guestSlot(processName: String?): Int? {
        if (processName.isNullOrBlank()) return null
        val marker = processName.substringAfterLast(':', "")
        if (!marker.startsWith("p")) return null
        return marker.removePrefix("p").toIntOrNull()
    }

    private fun currentProcessName(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Application.getProcessName()
        }
        val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pid = Process.myPid()
        return manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
    }

    private fun screenWidth(): Int = app.resources.displayMetrics.widthPixels

    private fun screenHeight(): Int = app.resources.displayMetrics.heightPixels

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            app.resources.displayMetrics
        ).toInt()
    }

    private data class InstalledSpaceApp(
        val instanceId: String,
        val packageName: String,
        val appName: String,
        val apkPath: String,
        val processSlot: Int
    )

    companion object {
        private const val TAG = "FloatingSpaceIcon"
        private var active: FloatingSpaceIconController? = null

        fun onGuestResumed(activity: Activity) {
            active?.handleGuestResumed(activity)
        }

        fun onGuestPaused(activity: Activity) {
            active?.handleGuestPaused(activity)
        }

        fun attachTo(application: Application) {
            val controller = active ?: return
            try {
                application.registerActivityLifecycleCallbacks(controller)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Could not watch guest activity lifecycle")
            }
        }
    }
}
