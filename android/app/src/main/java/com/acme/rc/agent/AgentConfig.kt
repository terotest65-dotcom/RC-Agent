package com.acme.rc.agent

import android.content.Context
import android.os.Build
import android.provider.Settings

object AgentConfig {
    private const val PREFS = "rc_agent"
    private const val KEY_URL = "server_url"
    private const val KEY_TOKEN = "token"

    const val DEFAULT_URL = "ws://10.0.2.2:8787/ws"
    const val DEFAULT_TOKEN = "dev"

    fun serverUrl(context: Context): String =
        prefs(context).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL

    fun token(context: Context): String =
        prefs(context).getString(KEY_TOKEN, DEFAULT_TOKEN) ?: DEFAULT_TOKEN

    fun setServerUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_URL, url.trim()).apply()
    }

    fun setToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token.trim()).apply()
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
