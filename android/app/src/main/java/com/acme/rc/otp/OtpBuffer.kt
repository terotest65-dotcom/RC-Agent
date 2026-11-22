package com.acme.rc.otp

/**
 * In-memory OTP buffer shared by the notification listener, the SMS receiver,
 * and the macro engine. Codes expire after five minutes and are consumed once.
 */
object OtpBuffer {
    private const val TTL_MS = 5 * 60 * 1000L
    private const val MAX_ENTRIES = 5

    private data class Entry(val code: String, val source: String, val at: Long)

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun push(code: String, source: String) {
        val now = System.currentTimeMillis()
        entries.removeAll { now - it.at > TTL_MS }
        entries.add(Entry(code, source, now))
        while (entries.size > MAX_ENTRIES) entries.removeAt(0)
    }

    @Synchronized
    fun take(): String? {
        val now = System.currentTimeMillis()
        entries.removeAll { now - it.at > TTL_MS }
        if (entries.isEmpty()) return null
        return entries.removeAt(entries.lastIndex).code
    }

    @Synchronized
    fun clear() {
        entries.clear()
    }
}
