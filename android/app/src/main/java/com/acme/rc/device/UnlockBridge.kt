package com.acme.rc.device

/**
 * Carries the result of the keyguard-dismiss activity back to AgentService.
 * Only one unlock request can be pending at a time.
 */
object UnlockBridge {
    private var callback: ((Boolean, String) -> Unit)? = null

    @Synchronized
    fun register(next: (Boolean, String) -> Unit) {
        callback = next
    }

    @Synchronized
    fun clear() {
        callback = null
    }

    @Synchronized
    fun complete(ok: Boolean, detail: String) {
        val pending = callback
        callback = null
        pending?.invoke(ok, detail)
    }
}
