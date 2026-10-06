package com.nextvm.core.framework.parsing

import android.content.pm.PackageInfo
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Complete parsed package information — combines the lightweight ApkLiteInfo
 * with full component details. This is what NEXTVM uses after fully parsing
 * an APK for installation.
 */
data class FullPackageInfo(
    /** Lightweight APK metadata (mirrors Android 16 ApkLite). Null when loaded from disk. */
    val apkLite: ApkLiteInfo? = null,

    /** Lightweight package metadata (mirrors Android 16 PackageLite). Null when loaded from disk. */
    val packageLite: PackageLiteInfo? = null,

    /** Android SDK PackageInfo (standard public API) */
    val packageInfo: PackageInfo,

    /** Human-readable app name */
    val appName: String,

    /** App icon drawable */
    @Transient val icon: Drawable? = null,

    /** Application class name (for custom Application subclass) */
    val applicationClassName: String? = null,

    /** Main launcher activity fully-qualified name */
    val mainActivity: String? = null,

    /** All declared activities with metadata */
    val activities: List<ComponentDetail> = emptyList(),

    /** All declared services with metadata */
    val services: List<ComponentDetail> = emptyList(),

    /** All declared content providers with metadata */
    val providers: List<ComponentDetail> = emptyList(),

    /** All declared broadcast receivers with metadata */
    val receivers: List<ComponentDetail> = emptyList(),

    /** Requested permissions */
    val permissions: List<String> = emptyList(),

    /** Native ABI architectures found in APK */
    val nativeAbis: List<String> = emptyList(),

    /** APK file size in bytes */
    val apkSizeBytes: Long = 0,

    /** Application-level metadata from <meta-data> tags */
    val metaData: Map<String, String> = emptyMap()
) {
    val packageName: String get() = apkLite?.packageName ?: packageInfo.packageName ?: ""
    val versionName: String get() = packageInfo.versionName ?: "unknown"
    val versionCode: Long
        get() = apkLite?.longVersionCode ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
    val is64Bit: Boolean get() = nativeAbis.any { it.contains("64") }
}

/**
 * Detailed component information — richer than just a class name.
 * Mirrors Android 16's ActivityInfo/ServiceInfo/ProviderInfo fields.
 *
 * Source: ActivityInfo.java (2,541 lines) — launch mode constants, config changes,
 *         screen orientation, resize modes, etc.
 */
data class ComponentDetail(
    /** Fully qualified class name */
    val name: String,

    /** Short class name (alias for name, for convenience) */
    val className: String = name,

    /** Package name */
    val packageName: String,

    /**
     * Launch mode — from ActivityInfo.java constants:
     *   LAUNCH_MULTIPLE (0), LAUNCH_SINGLE_TOP (1),
     *   LAUNCH_SINGLE_TASK (2), LAUNCH_SINGLE_INSTANCE (3),
     *   LAUNCH_SINGLE_INSTANCE_PER_TASK (4)
     */
    val launchMode: Int = 0,

    /** Component flags */
    val flags: Int = 0,

    /**
     * Configuration changes this component handles itself.
     * Bitmask of CONFIG_* constants from ActivityInfo.java:
     *   CONFIG_MCC (0x0001), CONFIG_MNC (0x0002), CONFIG_LOCALE (0x0004),
     *   CONFIG_TOUCHSCREEN (0x0008), CONFIG_KEYBOARD (0x0010), etc.
     */
    val configChanges: Int = 0,

    /**
     * Screen orientation — from ActivityInfo.java:
     *   SCREEN_ORIENTATION_UNSPECIFIED (-1), SCREEN_ORIENTATION_LANDSCAPE (0),
     *   SCREEN_ORIENTATION_PORTRAIT (1), SCREEN_ORIENTATION_USER (2), etc.
     */
    val screenOrientation: Int = -1,

    /** Task affinity */
    val taskAffinity: String? = null,

    /** Whether the component is exported */
    val exported: Boolean = false,

    /** Whether the component is enabled */
    val enabled: Boolean = true,

    /** Required permission to access this component */
    val permission: String? = null,

    /** Process name this component runs in */
    val processName: String? = null,

    /** Theme resource ID (for activities) */
    val theme: Int = 0,

    /** Content provider authority (for providers only) */
    val authority: String? = null,

    /** Target activity for activity-alias declarations. Null for regular activities. */
    val targetActivity: String? = null
)
