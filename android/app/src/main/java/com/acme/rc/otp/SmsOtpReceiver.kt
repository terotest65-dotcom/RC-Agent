package com.acme.rc.otp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Reads incoming SMS OTPs into OtpBuffer. Requires RECEIVE_SMS. The user can
 * deny it; notification-based OTP capture still works.
 */
class SmsOtpReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val body = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
                .joinToString(" ") { it.messageBody ?: "" }
        } catch (_: Throwable) {
            return
        }
        if (body.isBlank()) return

        val lower = body.lowercase()
        if (!lower.contains("otp") &&
            !lower.contains("code") &&
            !lower.contains("verification") &&
            !lower.contains("passcode")
        ) {
            return
        }

        Regex("\\b(\\d{4,8})\\b").find(body)?.let {
            OtpBuffer.push(it.groupValues[1], "sms")
        }
    }
}
