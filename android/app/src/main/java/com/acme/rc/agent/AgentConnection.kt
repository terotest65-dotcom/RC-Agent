package com.acme.rc.agent

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Observable connection state for the Compose UI. */
object AgentConnection {
    var connected by mutableStateOf(false)
    var status by mutableStateOf("Not connected")
    var serverUrl by mutableStateOf("")
    var deviceId by mutableStateOf("")
}
