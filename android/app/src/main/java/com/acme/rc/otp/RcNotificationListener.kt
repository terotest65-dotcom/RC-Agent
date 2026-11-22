package com.acme.rc.otp

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads OTP-like notifications into OtpBuffer. The user must grant
 * notification access in Settings. Codes are never persisted.
 */
class RcNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val parts = mutableListOf<String>()

        listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT, Notification.EXTRA_SUB_TEXT)
            .forEach { key ->
                extras.getCharSequence(key)?.toString()?.let { parts += it }
            }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            line?.toString()?.let { parts += it }
        }

        val text = parts.joinToString(" ")
        if (text.isBlank()) return

        val lower = text.lowercase()
        if (!lower.contains("otp") &&
            !lower.contains("code") &&
            !lower.contains("verification") &&
            !lower.contains("passcode")
        ) {
            return
        }

        Regex("\\b(\\d{4,8})\\b").find(text)?.let {
            OtpBuffer.push(it.groupValues[1], "notification:${sbn.packageName}")
        }
    }
}
