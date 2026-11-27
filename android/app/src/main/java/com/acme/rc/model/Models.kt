package com.acme.rc.model

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF

data class AppInfo(
    val label: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val isTarget: Boolean
)

data class Element(
    val index: Int,
    val id: String,
    val boundsPx: Rect,
    val boundsNorm: RectF,
    val text: String?,
    val desc: String?,
    val viewId: String?,
    val className: String,
    val actions: List<String>,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean
) {
    val label: String
        get() = text?.takeIf { it.isNotBlank() }
            ?: desc?.takeIf { it.isNotBlank() }
            ?: className.substringAfterLast('.')
}

data class Snapshot(
    val id: String,
    val capturedAt: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    val packageName: String?,
    val activityName: String?,
    val elements: List<Element>,
    val bitmap: Bitmap?,
    val imagePath: String?,
    val imageBytes: Int,
    val nodeCount: Int,
    val maxDepth: Int,
    val error: String? = null,
    val elapsedMs: Long = 0
)

data class ProbeResult(
    val packageName: String,
    val verdict: String,
    val screenshotOk: Boolean,
    val secureWindow: Boolean,
    val nodeCount: Int,
    val clickableCount: Int,
    val viewIdCount: Int,
    val depth: Int,
    val elapsedMs: Long,
    val error: String? = null,
    val probedAt: Long = System.currentTimeMillis()
)

data class DeviceState(
    val accessibilityEnabled: Boolean,
    val notificationAccess: Boolean,
    val screenOn: Boolean,
    val locked: Boolean,
    val secureLock: Boolean,
    val batteryPercent: Int,
    val charging: Boolean,
    val deviceOwner: Boolean,
    val stayAwake: Boolean,
    val devicePin: Boolean,
    val pinFallback: Boolean,
    val appUnlockPackages: List<String>,
    val androidSdk: Int,
    val model: String
)

data class Selector(
    val viewId: String? = null,
    val text: String? = null,
    val contains: String? = null,
    val desc: String? = null,
    val descContains: String? = null,
    val className: String? = null,
    /** Pick the Nth match in tree order, 1-based. Defaults to the first. */
    val index: Int = 1,
    /** Match text, contains, desc, and descContains without regard to case. */
    val ignoreCase: Boolean = false
)

object TargetApps {
    val packages = listOf(
        "com.whatsapp",
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "com.android.chrome",
        "com.android.settings"
    )

    fun isTarget(packageName: String): Boolean = packageName in packages
}
