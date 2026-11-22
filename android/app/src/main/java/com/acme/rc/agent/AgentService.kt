package com.acme.rc.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.acme.rc.MainActivity
import com.acme.rc.accessibility.RcAccessibilityService
import com.acme.rc.device.DeviceStateReader
import com.acme.rc.inventory.AppInventory
import com.acme.rc.model.AppInfo
import com.acme.rc.model.Element
import com.acme.rc.model.ProbeResult
import com.acme.rc.model.Snapshot
import com.acme.rc.model.TargetApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AgentService : Service() {

    companion object {
        private const val TAG = "RcAgentService"
        const val ACTION_STOP = "com.acme.rc.agent.STOP"
        private const val CHANNEL_ID = "rc_agent"
        private const val NOTIFICATION_ID = 41
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val commandMutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var shouldReconnect = false

    @Volatile
    private var lastSnapshot: Snapshot? = null

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat("Starting…")
        AgentConnection.serverUrl = AgentConfig.serverUrl(this)
        AgentConnection.deviceId = AgentConfig.deviceId(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shouldReconnect = false
            stopSelf()
            return START_NOT_STICKY
        }

        val url = AgentConfig.serverUrl(this)
        AgentConnection.serverUrl = url
        AgentConnection.deviceId = AgentConfig.deviceId(this)
        AgentConnection.status = "Connecting to $url…"
        shouldReconnect = true
        connect(url)
        return START_STICKY
    }

    override fun onDestroy() {
        shouldReconnect = false
        reconnectJob?.cancel()
        socket?.close(1000, "service stopped")
        socket = null
        scope.cancel()
        AgentConnection.connected = false
        AgentConnection.status = "Stopped"
        super.onDestroy()
    }

    // -----------------------------------------------------------------
    // Connection
    // -----------------------------------------------------------------

    private fun connect(url: String) {
        reconnectJob?.cancel()
        socket?.close(1000, "reconnect")
        socket = null

        AgentConnection.status = "Connecting to $url…"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, Listener())
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        reconnectJob?.cancel()
        val delayMs = min(30_000L, 1_000L * (1 shl min(reconnectAttempt, 4)))
        reconnectAttempt++
        AgentConnection.connected = false
        AgentConnection.status = "Reconnecting in ${delayMs / 1000}s…"
        reconnectJob = scope.launch {
            delay(delayMs)
            if (shouldReconnect) connect(AgentConfig.serverUrl(this@AgentService))
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            reconnectAttempt = 0
            AgentConnection.connected = true
            AgentConnection.status = "Connected"
            updateNotification("Connected to relay")
            sendHello(webSocket)
            scope.launch {
                sendApps(webSocket)
                sendState(webSocket)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handleMessage(JSONObject(text))
            } catch (t: Throwable) {
                Log.w(TAG, "bad message", t)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            AgentConnection.connected = false
            AgentConnection.status = "Connection failed: ${t.message ?: t.javaClass.simpleName}"
            updateNotification("Disconnected")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            AgentConnection.connected = false
            AgentConnection.status = "Disconnected: $reason"
            updateNotification("Disconnected")
            scheduleReconnect()
        }
    }

    private fun sendHello(ws: WebSocket) {
        val hello = JSONObject()
            .put("t", "hello")
            .put("role", "device")
            .put("token", AgentConfig.token(this))
            .put("deviceId", AgentConfig.deviceId(this))
            .put("name", AgentConfig.deviceName())
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.SDK_INT)
            .put("agent", "1.0.0")
            .put("protocolVersion", 1)
        ws.send(hello.toString())
    }

    private suspend fun sendApps(ws: WebSocket) {
        try {
            val apps = AppInventory.list(this)
            val array = JSONArray()
            for (app in apps) array.put(appJson(app))
            ws.send(JSONObject().put("t", "apps").put("apps", array).toString())
        } catch (t: Throwable) {
            Log.w(TAG, "sendApps", t)
        }
    }

    private fun sendState(ws: WebSocket) {
        try {
            val state = DeviceStateReader.read(this)
            val service = RcAccessibilityService.instance
            ws.send(
                JSONObject()
                    .put("t", "state")
                    .put(
                        "state",
                        JSONObject()
                            .put("online", true)
                            .put("screenOn", state.screenOn)
                            .put("locked", state.locked)
                            .put("secureLock", state.secureLock)
                            .put("battery", state.batteryPercent)
                            .put("charging", state.charging)
                            .put("a11y", state.accessibilityEnabled)
                            .put("notifAccess", state.notificationAccess)
                            .put("deviceOwner", state.deviceOwner)
                            .put("stayAwake", state.stayAwake)
                            .put("package", service?.currentPackage ?: JSONObject.NULL)
                    )
                    .toString()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sendState", t)
        }
    }

    // -----------------------------------------------------------------
    // Commands
    // -----------------------------------------------------------------

    private fun handleMessage(msg: JSONObject) {
        when (msg.optString("t")) {
            "helloOk" -> {
                val id = msg.optString("deviceId")
                if (id.isNotBlank()) AgentConnection.deviceId = id
            }
            "cmd" -> handleCommand(msg)
            else -> Unit
        }
    }

    private fun handleCommand(msg: JSONObject) {
        scope.launch {
            commandMutex.withLock {
                val cmdId = msg.optString("cmdId")
                val action = msg.optString("action")
                try {
                    when (action) {
                        "listApps" -> sendAppsNow()
                        "snapshot" -> captureAndSend()
                        "launch" -> {
                            val pkg = msg.optString("pkg")
                            val service = service()
                            if (!service.launchApp(pkg)) error("launch failed: $pkg")
                            delay(1500)
                            service.awaitPackage(pkg, 5000)
                            delay(800)
                            captureAndSend()
                        }
                        "tap" -> {
                            val element = findElement(msg) ?: error("element not found")
                            service().tapElement(element)
                            delay(900)
                            captureAndSend()
                        }
                        "tapXY" -> {
                            val x = msg.optDouble("x", -1.0)
                            val y = msg.optDouble("y", -1.0)
                            if (x < 0 || y < 0) error("bad coordinates")
                            val metrics = resources.displayMetrics
                            service().tapAt(
                                (x * metrics.widthPixels).toInt(),
                                (y * metrics.heightPixels).toInt()
                            )
                            delay(900)
                            captureAndSend()
                        }
                        "setText" -> {
                            val element = findElement(msg) ?: error("element not found")
                            val text = msg.optString("text")
                            if (!service().setText(element, text)) error("setText failed")
                            delay(500)
                            captureAndSend()
                        }
                        "scroll" -> {
                            val element = findElement(msg) ?: error("element not found")
                            val down = msg.optString("dir", "down") == "down"
                            service().scroll(element, down)
                            delay(600)
                            captureAndSend()
                        }
                        "globalAction" -> {
                            val name = msg.optString("name")
                            if (!service().performGlobalActionByName(name)) {
                                error("global action failed: $name")
                            }
                            delay(600)
                            captureAndSend()
                        }
                        "probe" -> {
                            val pkg = msg.optString("pkg")
                            val result = service().probe(pkg)
                            sendProbe(result)
                            delay(400)
                            captureAndSend()
                        }
                        "probeTargets" -> {
                            val service = service()
                            val apps = AppInventory.list(this@AgentService)
                            val targets = apps.filter { TargetApps.isTarget(it.packageName) }
                            for ((index, app) in targets.withIndex()) {
                                AgentConnection.status =
                                    "Probing ${app.label} (${index + 1}/${targets.size})…"
                                sendProbe(service.probe(app.packageName))
                            }
                            captureAndSend()
                        }
                        else -> error("unknown action: $action")
                    }
                    sendResult(cmdId, true)
                } catch (t: Throwable) {
                    sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                }
            }
        }
    }

    private fun service(): RcAccessibilityService =
        RcAccessibilityService.instance
            ?: error("accessibility service not enabled")

    private fun findElement(msg: JSONObject): Element? {
        val snapshot = lastSnapshot ?: return null
        val id = msg.optString("element")
        val index = msg.optInt("index", -1)
        return snapshot.elements.firstOrNull { it.id == id }
            ?: snapshot.elements.firstOrNull { it.index == index }
    }

    private suspend fun sendAppsNow() {
        val ws = socket ?: return
        sendApps(ws)
    }

    private suspend fun captureAndSend() {
        val snapshot = service().captureSnapshot()
        lastSnapshot = snapshot
        socket?.send(snapshotJson(snapshot).toString())
        socket?.let { sendState(it) }
    }

    private fun sendResult(cmdId: String, ok: Boolean, error: String? = null) {
        val json = JSONObject()
            .put("t", "actionResult")
            .put("cmdId", cmdId)
            .put("ok", ok)
        if (error != null) json.put("error", error)
        socket?.send(json.toString())
    }

    private fun sendProbe(result: ProbeResult) {
        val payload = JSONObject()
            .put("pkg", result.packageName)
            .put("verdict", result.verdict)
            .put("screenshotOk", result.screenshotOk)
            .put("secureWindow", result.secureWindow)
            .put("nodeCount", result.nodeCount)
            .put("clickableCount", result.clickableCount)
            .put("viewIdCount", result.viewIdCount)
            .put("depth", result.depth)
            .put("elapsedMs", result.elapsedMs)
            .put("error", result.error ?: JSONObject.NULL)
            .put("probedAt", result.probedAt)
        socket?.send(JSONObject().put("t", "probe").put("result", payload).toString())
    }

    // -----------------------------------------------------------------
    // JSON
    // -----------------------------------------------------------------

    private fun appJson(app: AppInfo): JSONObject = JSONObject()
        .put("pkg", app.packageName)
        .put("label", app.label)
        .put("version", app.versionName)
        .put("code", app.versionCode)
        .put("system", app.isSystem)

    private fun snapshotJson(snapshot: Snapshot): JSONObject {
        val elements = JSONArray()
        for (element in snapshot.elements) {
            val bounds = JSONArray()
                .put(element.boundsNorm.left.toDouble())
                .put(element.boundsNorm.top.toDouble())
                .put(element.boundsNorm.right.toDouble())
                .put(element.boundsNorm.bottom.toDouble())
            val actions = JSONArray()
            for (action in element.actions) actions.put(action)
            elements.put(
                JSONObject()
                    .put("i", element.index)
                    .put("id", element.id)
                    .put("b", bounds)
                    .put("text", element.text ?: JSONObject.NULL)
                    .put("desc", element.desc ?: JSONObject.NULL)
                    .put("vid", element.viewId ?: JSONObject.NULL)
                    .put("cls", element.className)
                    .put("acts", actions)
                    .put("editable", element.editable)
                    .put("scrollable", element.scrollable)
                    .put("clickable", element.clickable)
            )
        }

        return JSONObject()
            .put("t", "snapshot")
            .put("id", snapshot.id)
            .put("ts", snapshot.capturedAt)
            .put("w", snapshot.width)
            .put("h", snapshot.height)
            .put("rotation", snapshot.rotation)
            .put("pkg", snapshot.packageName ?: JSONObject.NULL)
            .put("activity", snapshot.activityName ?: JSONObject.NULL)
            .put("image", imageBase64(snapshot.bitmap))
            .put("elements", elements)
            .put("nodeCount", snapshot.nodeCount)
            .put("maxDepth", snapshot.maxDepth)
            .put("elapsedMs", snapshot.elapsedMs)
            .put("error", snapshot.error ?: JSONObject.NULL)
    }

    private fun imageBase64(bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (t: Throwable) {
            Log.w(TAG, "imageBase64", t)
            ""
        }
    }

    // -----------------------------------------------------------------
    // Notification
    // -----------------------------------------------------------------

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "RC Agent relay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps RC Agent connected to the relay"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, AgentService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RC Agent")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun startForegroundCompat(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(text),
                type
            )
        } catch (t: Throwable) {
            Log.w(TAG, "startForeground failed", t)
        }
    }

    private fun updateNotification(text: String) {
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed", t)
        }
    }
}
