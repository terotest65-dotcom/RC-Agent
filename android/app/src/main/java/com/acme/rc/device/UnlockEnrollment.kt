package com.acme.rc.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.acme.rc.R

/**
 * One-time prompt asking the operator to save this device's unlock credential.
 *
 * The prompt is posted after a manual unlock when the device is secure and no
 * credential is stored. It opens a local activity; the credential is typed
 * there and stored with the same Keystore key as the panel flow, so it never
 * traverses the relay.
 */
object UnlockEnrollment {
    const val CHANNEL_ID = "rc_unlock_enroll"
    const val NOTIFICATION_ID = 42

    private const val PREFS = "rc_unlock_enroll"
    private const val KEY_PROMPTED = "prompted"
    private const val KEY_AUTO_OPENED = "auto_opened"

    fun shouldPrompt(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_PROMPTED, false) &&
            !DevicePin.isConfigured(context)

    fun markPrompted(context: Context) {
        prefs(context).edit().putBoolean(KEY_PROMPTED, true).apply()
    }

    /** Auto-open once after setup; manual/local entry points remain available. */
    fun shouldAutoOpen(context: Context): Boolean =
        !prefs(context).getBoolean(KEY_AUTO_OPENED, false) &&
            !DevicePin.isConfigured(context)

    fun markAutoOpened(context: Context) {
        prefs(context).edit().putBoolean(KEY_AUTO_OPENED, true).apply()
    }

    fun reset(context: Context) {
        prefs(context).edit().clear().apply()
        cancel(context)
    }

    fun prompt(context: Context) {
        if (!shouldPrompt(context)) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        ensureChannel(context)
        val openIntent = PendingIntent.getActivity(
            context,
            41,
            Intent(context, EnrollUnlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(context.getString(R.string.enroll_notification_title))
            .setContentText(context.getString(R.string.enroll_notification_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.enroll_notification_text))
            )
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            manager.notify(NOTIFICATION_ID, notification)
            markPrompted(context)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS is not granted; the local status screen still
            // offers the same enrollment action.
        }
    }

    fun cancel(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: Throwable) {
            // Nothing to cancel.
        }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.enroll_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.enroll_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
