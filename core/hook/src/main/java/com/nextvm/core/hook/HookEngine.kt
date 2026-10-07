package com.nextvm.core.hook

import timber.log.Timber
import java.lang.reflect.Method
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HookEngine — Unified hook management for NEXTVM.
 *
 * Currently supports:
 * - Java method hooking via reflection (no native required)
 * - Proxy-based interception (InvocationHandler)
 *
 * Java methods are hooked in-process with Pine (see EngineXposed).
 * Native file hooks stay in NativeHookBridge.
 *
 * For Phase 1, we use pure Java/Kotlin reflection which doesn't require
 * native libraries. This works for:
 * - Static field modification (Build.* spoofing)
 * - Singleton replacement (IActivityManager, IPackageManager)
 * - Handler.Callback injection (ActivityThread.mH)
 * - ClassLoader swapping
 */
@Singleton
class HookEngine @Inject constructor() {

    companion object {
        private const val TAG = "HookEngine"
    }

    // Track installed hooks for cleanup
    private val installedHooks = mutableListOf<HookInfo>()

    /**
     * Replace a static field value on a class.
     * Used for Build.* spoofing and singleton replacement.
     */
    fun hookStaticField(
        className: String,
        fieldName: String,
        newValue: Any?
    ): Boolean {
        return try {
            val clazz = Class.forName(className)
            val field = clazz.getDeclaredField(fieldName)
            field.isAccessible = true

            // Remove final modifier
            try {
                val accessFlagsField = java.lang.reflect.Field::class.java
                    .getDeclaredField("accessFlags")
                accessFlagsField.isAccessible = true
                accessFlagsField.setInt(
                    field,
                    field.modifiers and java.lang.reflect.Modifier.FINAL.inv()
                )
            } catch (_: Exception) {
                // Try Java standard "modifiers" field
                val modField = java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
                modField.isAccessible = true
                modField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
            }

            val oldValue = field.get(null)
            field.set(null, newValue)

            installedHooks.add(HookInfo(
                type = HookType.STATIC_FIELD,
                target = "$className.$fieldName",
                originalValue = oldValue
            ))

            Timber.tag(TAG).d("Hooked static field: $className.$fieldName = $newValue")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook static field: $className.$fieldName")
            false
        }
    }

    /**
     * Replace an instance field value.
     */
    fun hookInstanceField(
        instance: Any,
        fieldName: String,
        newValue: Any?
    ): Boolean {
        return try {
            var clazz: Class<*>? = instance::class.java
            var field: java.lang.reflect.Field? = null

            while (clazz != null && field == null) {
                try {
                    field = clazz.getDeclaredField(fieldName)
                } catch (_: NoSuchFieldException) {
                    clazz = clazz.superclass
                }
            }

            if (field == null) {
                Timber.tag(TAG).e("Field not found: $fieldName")
                return false
            }

            field.isAccessible = true
            val oldValue = field.get(instance)
            field.set(instance, newValue)

            installedHooks.add(HookInfo(
                type = HookType.INSTANCE_FIELD,
                target = "${instance::class.java.name}.$fieldName",
                originalValue = oldValue,
                instance = instance
            ))

            Timber.tag(TAG).d("Hooked instance field: ${instance::class.java.simpleName}.$fieldName")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to hook instance field: $fieldName")
            false
        }
    }

    /**
     * Restore all hooks to original state.
     */
    fun unhookAll() {
        for (hook in installedHooks.reversed()) {
            try {
                when (hook.type) {
                    HookType.STATIC_FIELD -> {
                        val parts = hook.target.split(".")
                        val className = parts.dropLast(1).joinToString(".")
                        val fieldName = parts.last()
                        val clazz = Class.forName(className)
                        val field = clazz.getDeclaredField(fieldName)
                        field.isAccessible = true
                        field.set(null, hook.originalValue)
                    }
                    HookType.INSTANCE_FIELD -> {
                        val fieldName = hook.target.substringAfterLast(".")
                        val instance = hook.instance ?: continue
                        val field = instance::class.java.getDeclaredField(fieldName)
                        field.isAccessible = true
                        field.set(instance, hook.originalValue)
                    }
                    else -> { /* Future hook types */ }
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to unhook: ${hook.target}")
            }
        }

        val count = installedHooks.size
        installedHooks.clear()
        Timber.tag(TAG).i("Unhooked $count hooks")
    }

    /**
     * Get count of installed hooks.
     */
    fun getHookCount(): Int = installedHooks.size

    /** Hook tracking data */
    private data class HookInfo(
        val type: HookType,
        val target: String,
        val originalValue: Any? = null,
        val instance: Any? = null
    )

    private enum class HookType {
        STATIC_FIELD,
        INSTANCE_FIELD,
        METHOD_PROXY,
        NATIVE_INLINE,
        NATIVE_PLT
    }
}
