package com.acme.rc.otp

import android.service.notification.NotificationListenerService

/**
 * Phase 3: parse OTP notifications into an in-memory buffer.
 * The component exists in Phase 0 so notification access can be requested
 * and reported on the device card.
 */
class RcNotificationListener : NotificationListenerService()
