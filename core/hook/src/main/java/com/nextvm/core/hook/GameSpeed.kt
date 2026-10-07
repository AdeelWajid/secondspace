package com.nextvm.core.hook

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import com.nextvm.core.hook.xposed.GameSpeedModule
import timber.log.Timber

/**
 * Speeds up the virtual app in this process.
 * Unity sets Time.timeScale, which is what Unity games actually simulate with.
 * Clock and sleep only affect native game libraries. Animation scales Android animators.
 * The target is the app the floating icon is currently on.
 */
object GameSpeed {
    private const val TAG = "GameSpeed"

    enum class Method { UNITY, CLOCK, SLEEP, ANIMATION }

    data class State(
        val scale: Float = 1f,
        val methods: Set<Method> = setOf(Method.UNITY),
        val targetName: String = "",
        val targetPackage: String = ""
    )

    @Volatile
    var state: State = State()
        private set

    fun selectTarget(name: String, packageName: String) {
        state = state.copy(
            targetName = name.ifBlank { packageName },
            targetPackage = packageName
        )
    }

    fun toggle(method: Method) {
        val next = state.methods.toMutableSet()
        if (!next.add(method)) {
            next.remove(method)
        } else if (method == Method.UNITY) {
            next.remove(Method.CLOCK)
        } else if (method == Method.CLOCK) {
            next.remove(Method.UNITY)
        }
        update(state.scale, next)
    }

    fun setScale(scale: Float) {
        update(scale, state.methods)
    }

    private val handler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val reapplyUnity: Runnable = object : Runnable {
        override fun run() {
            val current = state
            if (current.scale == 1f || Method.UNITY !in current.methods) return
            applyNative(current, quiet = true)
            handler.postDelayed(this, 300L)
        }
    }

    private fun update(scale: Float, methods: Set<Method>) {
        val safeScale = scale.coerceIn(0.1f, 20f)
        state = state.copy(scale = safeScale, methods = methods)
        handler.removeCallbacks(reapplyUnity)
        val active = safeScale != 1f
        GameSpeedModule.onSpeedChanged()
        applyNative(state, quiet = false)
        applyAnimation(if (active && Method.ANIMATION in methods) 1f / safeScale else 1f)
        if (active && Method.UNITY in methods) {
            handler.postDelayed(reapplyUnity, 300)
        }
    }

    private fun applyNative(current: State, quiet: Boolean) {
        val active = current.scale != 1f
        val applied = NativeHookBridge.applyGameSpeed(
            scale = current.scale,
            unity = active && Method.UNITY in current.methods,
            clock = active && Method.CLOCK in current.methods,
            sleep = active && Method.SLEEP in current.methods
        )
        if (!applied && !quiet) {
            Timber.tag(TAG).w("Game speed was not applied")
        }
    }

    private fun applyAnimation(durationScale: Float) {
        try {
            val field = ValueAnimator::class.java.getDeclaredField("sDurationScale")
            field.isAccessible = true
            field.setFloat(null, durationScale)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not set animator duration scale")
        }
        try {
            val method = ValueAnimator::class.java.getDeclaredMethod(
                "setDurationScale",
                Float::class.javaPrimitiveType
            )
            method.isAccessible = true
            method.invoke(null, durationScale)
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Animation scale method failed")
        }
    }
}
