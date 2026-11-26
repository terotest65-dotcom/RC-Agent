package com.acme.rc.device

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.provider.Settings

/** The credential type the keyguard is currently showing. */
enum class LockType {
    PIN,
    PATTERN,
    PASSWORD,
    UNKNOWN;

    companion object {
        fun fromName(value: String?): LockType =
            values().firstOrNull { it.name == value } ?: UNKNOWN
    }
}

/**
 * Caches the last lock type seen on the keyguard. Android has no public API for
 * the credential type, so the accessibility service infers it while the bouncer
 * is visible and the enrollment screen reads the cached value.
 */
object LockTypeStore {
    private const val PREFS = "rc_lock_type"
    private const val KEY = "type"

    fun get(context: Context): LockType =
        LockType.fromName(prefs(context).getString(KEY, null))

    fun set(context: Context, type: LockType) {
        if (type == LockType.UNKNOWN) return
        prefs(context).edit().putString(KEY, type.name).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Best-effort lock type detection for the enrollment screen.
 *
 * The accessibility service's cached type is authoritative. The secure setting
 * and DevicePolicyManager fallbacks cover devices where the bouncer has not
 * been observed yet; both are best-effort and may return UNKNOWN.
 */
object LockTypeDetector {
    fun detect(context: Context): LockType {
        val cached = LockTypeStore.get(context)
        if (cached != LockType.UNKNOWN) return cached

        val fromSetting = fromSecureSetting(context)
        if (fromSetting != LockType.UNKNOWN) return fromSetting

        return fromDevicePolicy(context)
    }

    private fun fromSecureSetting(context: Context): LockType {
        val resolver = context.contentResolver
        val primary = try {
            Settings.Secure.getInt(resolver, "lockscreen.password_type", -1)
        } catch (_: Throwable) {
            -1
        }
        if (primary >= 0) {
            val type = qualityToType(primary)
            if (type != LockType.UNKNOWN) return type
        }
        val alternate = try {
            Settings.Secure.getInt(
                resolver,
                "lockscreen.password_type_alternate",
                -1
            )
        } catch (_: Throwable) {
            -1
        }
        return qualityToType(alternate)
    }

    private fun fromDevicePolicy(context: Context): LockType {
        val quality = try {
            context.getSystemService(DevicePolicyManager::class.java)
                ?.getPasswordQuality(null) ?: -1
        } catch (_: Throwable) {
            -1
        }
        return qualityToType(quality)
    }

    private fun qualityToType(quality: Int): LockType = when (quality) {
        DevicePolicyManager.PASSWORD_QUALITY_SOMETHING -> LockType.PATTERN
        DevicePolicyManager.PASSWORD_QUALITY_NUMERIC -> LockType.PIN
        DevicePolicyManager.PASSWORD_QUALITY_ALPHABETIC,
        DevicePolicyManager.PASSWORD_QUALITY_ALPHANUMERIC,
        DevicePolicyManager.PASSWORD_QUALITY_COMPLEX -> LockType.PASSWORD
        else -> LockType.UNKNOWN
    }
}
