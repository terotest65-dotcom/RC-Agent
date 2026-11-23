package com.acme.rc.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/** Best-effort auto-start after reboot for enrolled devices. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AgentService::class.java)
            )
        } catch (_: Throwable) {
            // Android may restrict boot-time FGS starts; opening the app retries.
        }
    }
}
