package com.acme.rc.device

import android.content.Context
import android.os.PowerManager

object DevicePower {

    /**
     * Turns the display on. This uses the legacy bright-screen wake lock because
     * it still works from a background service on modern Android; a foreground
     * activity with setTurnScreenOn() is used only for unlocking.
     */
    @Suppress("DEPRECATION")
    fun wake(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
            ?: throw IllegalStateException("PowerManager is unavailable")
        if (power.isInteractive) return "Screen is already on"

        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE,
            "rc:agent:wake"
        )
        lock.acquire(5_000)
        return "Wake signal sent"
    }
}
