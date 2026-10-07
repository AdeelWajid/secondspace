package com.nextvm.core.hook.xposed

import android.os.CountDownTimer
import com.nextvm.core.hook.GameSpeed
import timber.log.Timber
import top.canyie.pine.Pine
import top.canyie.pine.callback.MethodHook

/**
 * Built-in speed module, hooked in-process with Pine.
 * Add later Java features here. Unity Time.timeScale is native, so the
 * Unity button drives that from the game thread in the native bridge.
 * LockSupport and the garbage-collector threads are not scaled. Shortening
 * those waits races ART and aborts the process after a few seconds.
 * SystemClock is left alone so the floating icon and input stay on real time.
 */
object GameSpeedModule {
    private const val TAG = "GameSpeedModule"

    @Volatile
    private var loaded = false

    fun onSpeedChanged() {
        EngineXposed.install()
    }

    fun load() {
        if (loaded) return
        loaded = true
        hookMethod(Thread::class.java, "sleep", arrayOf(Long::class.javaPrimitiveType!!), sleepMillis)
        hookMethod(
            Thread::class.java,
            "sleep",
            arrayOf(Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!),
            sleepMillisNanos
        )
        hookConstructor(
            CountDownTimer::class.java,
            arrayOf(Long::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!),
            countDown
        )
    }

    private val sleepMillis = object : MethodHook() {
        override fun beforeCall(callFrame: Pine.CallFrame) {
            if (!sleepActive()) return
            callFrame.args[0] = scaleLong(callFrame.args[0] as Long)
        }
    }

    private val sleepMillisNanos = object : MethodHook() {
        override fun beforeCall(callFrame: Pine.CallFrame) {
            if (!sleepActive()) return
            callFrame.args[0] = scaleLong(callFrame.args[0] as Long)
            val nanos = callFrame.args[1] as Int
            callFrame.args[1] = (nanos / GameSpeed.state.scale).toInt().coerceAtLeast(0)
        }
    }

    private val countDown = object : MethodHook() {
        override fun beforeCall(callFrame: Pine.CallFrame) {
            if (!sleepActive()) return
            callFrame.args[0] = scaleLong(callFrame.args[0] as Long)
            callFrame.args[1] = scaleLong(callFrame.args[1] as Long).coerceAtLeast(1L)
        }
    }

    private fun sleepActive(): Boolean {
        val state = GameSpeed.state
        if (state.scale == 1f || GameSpeed.Method.SLEEP !in state.methods) return false
        val name = Thread.currentThread().name
        return !name.contains("HeapTask") &&
            !name.contains("Finalizer") &&
            !name.contains("ReferenceQueue") &&
            !name.contains("GC") &&
            !name.contains("Jit") &&
            !name.contains("Binder") &&
            !name.contains("Watchdog")
    }

    private fun scaleLong(value: Long): Long {
        val scale = GameSpeed.state.scale
        if (scale <= 0f || scale == 1f || value <= 0L) return value
        return (value / scale).toLong().coerceAtLeast(1L)
    }

    private fun hookMethod(clazz: Class<*>, name: String, params: Array<Class<*>>, callback: MethodHook) {
        try {
            Pine.hook(clazz.getDeclaredMethod(name, *params), callback)
            Timber.tag(TAG).i("Hooked ${clazz.simpleName}.$name")
        } catch (t: Throwable) {
            Timber.tag(TAG).w("Skip ${clazz.simpleName}.$name: ${t.message}")
        }
    }

    private fun hookConstructor(clazz: Class<*>, params: Array<Class<*>>, callback: MethodHook) {
        try {
            Pine.hook(clazz.getDeclaredConstructor(*params), callback)
            Timber.tag(TAG).i("Hooked ${clazz.simpleName} constructor")
        } catch (t: Throwable) {
            Timber.tag(TAG).w("Skip ${clazz.simpleName} constructor: ${t.message}")
        }
    }
}
