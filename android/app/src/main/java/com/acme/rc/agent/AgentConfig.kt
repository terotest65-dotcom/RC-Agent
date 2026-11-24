package com.acme.rc.agent

import android.content.Context
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import com.acme.rc.BuildConfig

object AgentConfig {
    private const val PREFS = "rc_agent"
    private const val KEY_NAME = "operator_name"
    private const val KEY_PHONE = "operator_phone"
    private const val KEY_CONSENT_AT = "consent_at"
    private const val KEY_TERMS = "terms_version"
    private const val KEY_PERMISSIONS_ASKED = "permissions_asked"

    fun serverUrl(): String = BuildConfig.RELAY_URL

    fun hasProfile(context: Context): Boolean =
        !profileName(context).isNullOrBlank() && !profilePhone(context).isNullOrBlank()

    fun profileName(context: Context): String? =
        prefs(context).getString(KEY_NAME, null)?.takeIf { it.isNotBlank() }

    fun profilePhone(context: Context): String? =
        prefs(context).getString(KEY_PHONE, null)?.takeIf { it.isNotBlank() }

    fun saveProfile(context: Context, name: String, phone: String) {
        prefs(context).edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_PHONE, phone.trim())
            .putLong(KEY_CONSENT_AT, System.currentTimeMillis())
            .putInt(KEY_TERMS, 1)
            .apply()
    }

    fun hasAskedPermissions(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERMISSIONS_ASKED, false)

    fun markPermissionsAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_PERMISSIONS_ASKED, true).apply()
    }

    fun profileJson(context: Context): JSONObject? {
        if (!hasProfile(context)) return null
        return JSONObject()
            .put("name", profileName(context))
            .put("phone", profilePhone(context))
            .put("consentAt", prefs(context).getLong(KEY_CONSENT_AT, 0L))
            .put("termsVersion", prefs(context).getInt(KEY_TERMS, 1))
    }

    fun deviceId(context: Context): String {
        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown"
        return "and_${androidId.take(12)}"
    }

    fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
