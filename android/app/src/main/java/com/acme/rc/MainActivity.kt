package com.acme.rc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.acme.rc.agent.AgentConfig
import com.acme.rc.agent.AgentConnection
import com.acme.rc.agent.AgentService
import com.acme.rc.device.DeviceStateReader
import com.acme.rc.device.EnrollUnlockActivity
import org.json.JSONObject

/**
 * The app UI is the local agent WebView. It handles enrollment (name + phone)
 * and exposes a permission/status panel. All device control happens in the
 * admin panel; the foreground service does the actual work.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private var setupDialog: AlertDialog? = null
    private var setupStage = SetupStage.ACCESSIBILITY
    private var permissionsPending = false
    private val setupHandler = Handler(Looper.getMainLooper())

    private val accessibilityPoll = object : Runnable {
        override fun run() {
            if (setupStage != SetupStage.ACCESSIBILITY) return
            if (DeviceStateReader.isAccessibilityEnabled(this@MainActivity)) {
                advanceSetup()
            } else if (!isFinishing && !isDestroyed) {
                setupHandler.postDelayed(this, 1000)
            }
        }
    }

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

    private enum class SetupStage { ACCESSIBILITY, PERMISSIONS, DONE }

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

        // Gate the agent behind accessibility and the runtime permissions.
        advanceSetup()
    }

    override fun onResume() {
        super.onResume()
        refreshWeb()
        if (setupStage != SetupStage.DONE) advanceSetup()
    }

    override fun onPause() {
        setupHandler.removeCallbacks(accessibilityPoll)
        super.onPause()
    }

    override fun onDestroy() {
        setupHandler.removeCallbacks(accessibilityPoll)
        setupDialog?.dismiss()
        setupDialog = null
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
        if (requestCode == REQUEST_PERMISSIONS) {
            permissionsPending = false
            refreshWeb()
            if (RUNTIME_PERMISSIONS.any { !granted(it) }) {
                Toast.makeText(
                    this,
                    "You can grant the remaining permissions from Status.",
                    Toast.LENGTH_LONG
                ).show()
            }
            advanceSetup()
        }
    }

    /**
     * Accessibility -> runtime permissions -> start the foreground agent.
     * Opening the app never starts the agent until these setup steps resolve.
     */
    private fun advanceSetup() {
        if (setupStage == SetupStage.DONE) return

        if (!DeviceStateReader.isAccessibilityEnabled(this)) {
            setupStage = SetupStage.ACCESSIBILITY
            showAccessibilityDialog()
            return
        }

        dismissAccessibilityDialog()
        setupStage = SetupStage.PERMISSIONS

        if (permissionsPending) return

        val missing = RUNTIME_PERMISSIONS.filterNot { granted(it) }
        if (missing.isNotEmpty() && !AgentConfig.hasAskedPermissions(this)) {
            AgentConfig.markPermissionsAsked(this)
            permissionsPending = true
            ActivityCompat.requestPermissions(
                this,
                missing.toTypedArray(),
                REQUEST_PERMISSIONS
            )
            return
        }

        finishSetup()
    }

    private fun finishSetup() {
        setupStage = SetupStage.DONE
        permissionsPending = false
        dismissAccessibilityDialog()
        startAgentService()
        refreshWeb()
    }

    private fun showAccessibilityDialog() {
        if (setupDialog?.isShowing == true) {
            restartAccessibilityPoll()
            return
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Enable accessibility")
            .setMessage(
                "RC Agent needs accessibility to capture the screen, tap, and " +
                    "record macros.\n\n" +
                    "Tap \"Open settings\" and enable \"RC Agent control\".\n\n" +
                    "On Android 13+, if the toggle is greyed out, open App info → " +
                    "⋮ → Allow restricted settings first."
            )
            .setPositiveButton("Open settings") { _, _ ->
                DeviceStateReader.openAccessibilitySettings(this)
            }
            .setNegativeButton("Not now") { d, _ -> d.dismiss() }
            .setCancelable(false)
            .create()

        dialog.setOnDismissListener {
            if (setupDialog === dialog) setupDialog = null
            setupHandler.removeCallbacks(accessibilityPoll)
        }
        setupDialog = dialog
        dialog.show()
        restartAccessibilityPoll()
    }

    private fun dismissAccessibilityDialog() {
        setupHandler.removeCallbacks(accessibilityPoll)
        setupDialog?.dismiss()
        setupDialog = null
    }

    private fun restartAccessibilityPoll() {
        setupHandler.removeCallbacks(accessibilityPoll)
        setupHandler.postDelayed(accessibilityPoll, 1000)
    }

    private fun refreshWeb() {
        if (!::webView.isInitialized) return
        webView.evaluateJavascript("window.rcRefresh && window.rcRefresh()", null)
    }

    private fun startAgentService(
        sendProfileOnly: Boolean = false,
        forceReconnect: Boolean = false
    ) {
        try {
            val intent = Intent(this, AgentService::class.java)
            if (sendProfileOnly) {
                if (AgentConfig.hasProfile(this)) {
                    startService(intent.setAction(AgentService.ACTION_SEND_PROFILE))
                }
            } else {
                if (forceReconnect) intent.action = AgentService.ACTION_RECONNECT
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
            .put("devicePin", state.devicePin)
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
                if (setupStage == SetupStage.DONE) {
                    startAgentService(sendProfileOnly = AgentConnection.connected)
                } else {
                    advanceSetup()
                }
            }
            return true
        }

        @JavascriptInterface
        fun startAgent() {
            runOnUiThread {
                if (setupStage == SetupStage.DONE) {
                    startAgentService(forceReconnect = true)
                } else {
                    advanceSetup()
                }
            }
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

        @JavascriptInterface
        fun openUnlockEnrollment() {
            runOnUiThread {
                startActivity(
                    Intent(this@MainActivity, EnrollUnlockActivity::class.java)
                )
            }
        }
    }
}
