package com.acme.rc.device

/** Result of an attempted keyguard PIN entry. Never contains the PIN. */
data class PinUnlockResult(
    val ok: Boolean,
    val detail: String
)
