package com.acme.rc.agent

/** Connection state shared with the WebView status screen. */
object AgentConnection {
    @Volatile
    var connected: Boolean = false

    @Volatile
    var status: String = "Not connected"

    @Volatile
    var serverUrl: String = ""

    @Volatile
    var deviceId: String = ""
}
