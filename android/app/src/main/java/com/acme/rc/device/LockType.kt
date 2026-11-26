package com.acme.rc.device

import android.content.Context

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
