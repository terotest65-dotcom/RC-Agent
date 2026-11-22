package com.acme.rc.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object SnapshotStore {
    var lastSnapshot by mutableStateOf<Snapshot?>(null)
    var busy by mutableStateOf(false)
    var status by mutableStateOf<String?>(null)
    val probes = mutableStateMapOf<String, ProbeResult>()
}
