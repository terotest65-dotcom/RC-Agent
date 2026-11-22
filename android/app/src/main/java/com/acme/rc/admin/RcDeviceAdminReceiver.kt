package com.acme.rc.admin

import android.app.admin.DeviceAdminReceiver

/**
 * Minimal admin receiver. A Device Owner can call setKeyguardDisabled() through it.
 * Provision with:
 *   adb shell dpm set-device-owner com.acme.rc/.admin.RcDeviceAdminReceiver
 */
class RcDeviceAdminReceiver : DeviceAdminReceiver()
