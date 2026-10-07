package com.nextvm.core.hook.xposed

import timber.log.Timber
import top.canyie.pine.Pine
import top.canyie.pine.PineConfig

/**
 * In-process ART hook runtime for the guest process.
 *
 * Epic and SandHook do not run on current Android. Pine is the same kind of
 * embed: it hooks Java methods inside this process, then [GameSpeedModule]
 * applies the speed rules.
 */
object EngineXposed {
    private const val TAG = "EngineXposed"
    private val lock = Any()

    @Volatile
    var ready: Boolean = false
        private set

    fun install() {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            try {
                PineConfig.debug = false
                PineConfig.debuggable = false
                PineConfig.antiChecks = true
                Pine.ensureInitialized()
                GameSpeedModule.load()
                ready = true
                Timber.tag(TAG).i("In-process Java hooks ready")
            } catch (t: Throwable) {
                Timber.tag(TAG).w(t, "In-process Java hooks unavailable")
            }
        }
    }
}
