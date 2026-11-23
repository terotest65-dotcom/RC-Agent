package com.acme.rc

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.acme.rc.agent.AgentConfig
import com.acme.rc.agent.AgentConnection
import com.acme.rc.agent.AgentService
import com.acme.rc.device.DeviceStateReader
import org.json.JSONObject

/**
 * The app UI is the local agent WebView. It handles enrollment (name + phone)
 * and exposes a permission/status panel. All device control happens in the
 * admin panel; the foreground service does the actual work.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    companion object {
        private const val REQUEST_PERMISSIONS = 1001
        private val RUNTIME_PERMISSIONS = arrayOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_PHONE_NUMBERS,
            Manifest.permission.CALL_PHONE
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightStatusBars = true
        WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = true

        webView = WebView(this).apply {
            setBackgroundColor(Color.rgb(245, 247, 251))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowContentAccess = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "RCAndroid")
            loadUrl("file:///android_asset/agent.html")
        }
        setContentView(webView)

        if (AgentConfig.hasProfile(this)) {
            startAgentService()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshWeb()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("RCAndroid")
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshWeb()
    }

    private fun refreshWeb() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.rcRefresh && window.rcRefresh()", null)
    }

    private fun startAgentService(sendProfileOnly: Boolean = false) {
        if (!AgentConfig.hasProfile(this)) return
        try {
            val intent = Intent(this, AgentService::class.java)
            if (sendProfileOnly) {
                startService(intent.setAction(AgentService.ACTION_SEND_PROFILE))
            } else {
                ContextCompat.startForegroundService(this, intent)
            }
        } catch (_: Throwable) {
            // The WebView status will show the service as offline.
        }
    }

    private fun requestRuntimePermissions() {
        ActivityCompat.requestPermissions(
            this,
            RUNTIME_PERMISSIONS,
            REQUEST_PERMISSIONS
        )
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun statusJson(): String {
        val state = DeviceStateReader.read(this)
        val profile = AgentConfig.profileJson(this)
        val permissions = JSONObject()
            .put(
                "notifications",
                Build.VERSION.SDK_INT < 33 ||
                    granted(Manifest.permission.POST_NOTIFICATIONS)
            )
            .put("receiveSms", granted(Manifest.permission.RECEIVE_SMS))
            .put("readSms", granted(Manifest.permission.READ_SMS))
            .put("sendSms", granted(Manifest.permission.SEND_SMS))
            .put("phoneState", granted(Manifest.permission.READ_PHONE_STATE))
            .put("phoneNumbers", granted(Manifest.permission.READ_PHONE_NUMBERS))
            .put("calls", granted(Manifest.permission.CALL_PHONE))

        return JSONObject()
            .put("profile", profile ?: JSONObject.NULL)
            .put("deviceId", AgentConfig.deviceId(this))
            .put("serverUrl", BuildConfig.RELAY_URL)
            .put("connected", AgentConnection.connected)
            .put("connection", AgentConnection.status)
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("android", Build.VERSION.SDK_INT)
            .put("accessibility", state.accessibilityEnabled)
            .put("notificationAccess", state.notificationAccess)
            .put("battery", state.batteryPercent)
            .put("charging", state.charging)
            .put("deviceOwner", state.deviceOwner)
            .put("screenOn", state.screenOn)
            .put("locked", state.locked)
            .put("permissions", permissions)
            .toString()
    }

    private fun openAppInfo() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private inner class Bridge {
        @JavascriptInterface
        fun getStatus(): String = try {
            statusJson()
        } catch (t: Throwable) {
            JSONObject()
                .put("error", t.message ?: t.javaClass.simpleName)
                .toString()
        }

        @JavascriptInterface
        fun saveProfile(name: String, phone: String): Boolean {
            val cleanName = name.trim()
            val digits = phone.count { it.isDigit() }
            if (cleanName.length < 2 || digits !in 7..15) return false
            AgentConfig.saveProfile(this@MainActivity, cleanName, phone.trim())
            runOnUiThread {
                refreshWeb()
                startAgentService(sendProfileOnly = AgentConnection.connected)
            }
            return true
        }

        @JavascriptInterface
        fun startAgent() {
            runOnUiThread { startAgentService() }
        }

        @JavascriptInterface
        fun requestPermissions() {
            runOnUiThread { requestRuntimePermissions() }
        }

        @JavascriptInterface
        fun openAccessibility() {
            runOnUiThread {
                DeviceStateReader.openAccessibilitySettings(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun openNotificationAccess() {
            runOnUiThread {
                DeviceStateReader.openNotificationListenerSettings(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                DeviceStateReader.openBatteryOptimizationSettings(this@MainActivity)
            }
        }

        @JavascriptInterface
        fun openAppInfo() {
            runOnUiThread { openAppInfo() }
        }
    }
}
