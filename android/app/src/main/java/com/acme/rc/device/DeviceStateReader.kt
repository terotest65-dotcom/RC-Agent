package com.acme.rc.device

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.acme.rc.accessibility.RcAccessibilityService
import com.acme.rc.model.DeviceState
import com.acme.rc.otp.RcNotificationListener

object DeviceStateReader {

    fun read(context: Context): DeviceState {
        val accessibilityEnabled = context
            .getSystemService(AccessibilityManager::class.java)
            ?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == RcAccessibilityService::class.java.name
            } == true

        val notificationAccess = context
            .getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(
                ComponentName(context, RcNotificationListener::class.java)
            ) == true

        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)

        val (batteryPercent, charging) = readBattery(context)
        val stayAwake = try {
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                0
            ) != 0
        } catch (_: Throwable) {
            false
        }

        return DeviceState(
            accessibilityEnabled = accessibilityEnabled,
            notificationAccess = notificationAccess,
            screenOn = power?.isInteractive == true,
            locked = keyguard?.isKeyguardLocked == true,
            secureLock = keyguard?.isDeviceSecure == true,
            batteryPercent = batteryPercent,
            charging = charging,
            deviceOwner = dpm?.isDeviceOwnerApp(context.packageName) == true,
            stayAwake = stayAwake,
            androidSdk = Build.VERSION.SDK_INT,
            model = "${Build.MANUFACTURER} ${Build.MODEL}"
        )
    }

    private fun readBattery(context: Context): Pair<Int, Boolean> {
        return try {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            percent to charging
        } catch (_: Throwable) {
            -1 to false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        // Best-effort deep link to this service's detail page. It is handled by
        // AOSP Settings and many OEMs; fall back to the accessibility list.
        val component = ComponentName(context, RcAccessibilityService::class.java)
        val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, details)) return
        open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openNotificationListenerSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    )

    fun openBatteryOptimizationSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    )

    fun openDeviceAdminSettings(context: Context) = open(
        context,
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    )

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    private fun open(context: Context, intent: Intent) {
        start(context, intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
