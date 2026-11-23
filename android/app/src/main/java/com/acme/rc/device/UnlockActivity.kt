package com.acme.rc.device

import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.app.Activity

/**
 * Invisible activity shown over the lock screen. For a swipe/no-security
 * keyguard the system dismisses it immediately; for a PIN/pattern/password it
 * shows the normal lock screen so the person can enter it. There is no way for
 * a normal app to bypass a secure lock.
 */
class UnlockActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var completed = false

    private val timeout = Runnable {
        finishWith(false, "No response from the lock screen")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)

        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard == null) {
            finishWith(false, "Keyguard service is unavailable")
            return
        }
        if (!keyguard.isKeyguardLocked) {
            finishWith(true, "Device is already unlocked")
            return
        }

        handler.postDelayed(timeout, 35_000)
        try {
            keyguard.requestDismissKeyguard(
                this,
                object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        finishWith(true, "Keyguard dismissed")
                    }

                    override fun onDismissCancelled() {
                        finishWith(false, "Unlock cancelled on the device")
                    }

                    override fun onDismissError() {
                        finishWith(false, "The lock screen could not be dismissed")
                    }
                }
            )
        } catch (t: Throwable) {
            finishWith(false, t.message ?: "Could not request keyguard dismissal")
        }
    }

    private fun finishWith(ok: Boolean, detail: String) {
        if (completed) return
        completed = true
        handler.removeCallbacks(timeout)
        UnlockBridge.complete(ok, detail)
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }
}
