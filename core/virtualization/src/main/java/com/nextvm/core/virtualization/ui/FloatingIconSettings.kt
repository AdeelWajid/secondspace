package com.nextvm.core.virtualization.ui

import android.content.Context
import java.io.File

/**
 * Whether the draggable space icon is shown on top of virtual apps.
 * A file marker is the source of truth so a guest process sees a toggle
 * changed by the main process without a stale preference cache.
 */
object FloatingIconSettings {
    private const val PREFS = "secondspace_settings"
    private const val KEY_ENABLED = "floating_icon_enabled"

    @Volatile
    private var hostFilesDir: File? = null

    /** Call from Application.onCreate before any guest sandbox redirects this process. */
    fun captureHostFilesDir(context: Context) {
        if (hostFilesDir == null) {
            hostFilesDir = context.filesDir
        }
    }

    fun isEnabled(context: Context): Boolean {
        if (marker(context).isFile) return true
        val prefsFile = hostFilesDir?.parentFile?.let { File(it, "shared_prefs/$PREFS.xml") }
        if (prefsFile != null && prefsFile.isFile) {
            return prefsFile.readText().contains("name=\"$KEY_ENABLED\" value=\"true\"")
        }
        return context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .commit()
        val file = marker(context)
        if (enabled) {
            file.parentFile?.mkdirs()
            file.writeText("1")
        } else {
            file.delete()
        }
    }

    fun hostRoot(context: Context): File {
        return hostFilesDir ?: context.applicationContext.filesDir
    }

    private fun marker(context: Context): File {
        return File(hostRoot(context), "virtual/floating_icon.on")
    }
}
