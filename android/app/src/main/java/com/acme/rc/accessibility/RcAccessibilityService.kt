package com.acme.rc.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import com.acme.rc.agent.AgentService
import com.acme.rc.device.AppUnlockStore
import com.acme.rc.device.DevicePin
import com.acme.rc.device.KeypadLayout
import com.acme.rc.device.LockType
import com.acme.rc.device.LockTypeStore
import com.acme.rc.device.PinKeypadStore
import com.acme.rc.device.PinUnlockResult
import com.acme.rc.device.UnlockEnrollment
import com.acme.rc.model.Element
import com.acme.rc.model.ProbeResult
import com.acme.rc.model.Selector
import com.acme.rc.model.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class RcAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "RcAccessibility"

        @Volatile
        var instance: RcAccessibilityService? = null
            private set

        private const val MAX_ELEMENTS = 40
        private const val MIN_DP = 16
        private const val ROW_DP = 48
        private const val MAX_SCREENSHOT_EDGE = 1280

        private const val ROOT_FLAGS = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID

        private val ENTER_LABELS = setOf(
            "enter", "ok", "okay", "done", "confirm", "check", "submit",
            "verify", "proceed", "continue", "next", "authorize", "approve",
            "forward", "arrow forward", "arrow_forward"
        )
        private val ENTER_TERMS = listOf(
            "submit", "verify", "proceed", "continue", "confirm",
            "authorize", "approve", "next", "forward", "arrow"
        )
        private val PIN_SWITCH_LABELS = setOf(
            "pin", "use pin", "enter pin", "use pin instead", "use your pin"
        )
        private val SCREEN_LOCK_TERMS = listOf(
            "use password", "use pin", "use pattern", "use screen lock",
            "use device", "use passcode", "use lock screen", "unlock with",
            "enter password", "enter pin", "enter pattern", "enter screen lock",
            "use backup", "switch to password", "switch to pin", "switch to pattern",
            "use your pin", "use your password", "use your pattern",
            "enter your pin", "enter your password", "enter your pattern",
            "verify with pin", "verify with password", "verify with pattern",
            "verify with screen lock"
        )
    }

    private val lastContentChange = AtomicLong(0)
    private var lastScreenshotAt = 0L
    private val lastLockTypeScan = AtomicLong(0)
    private var lastPushedPackage: String? = null
    private var lastStatePushAt = 0L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val promptCheck = Runnable { maybePromptForUnlockEnrollment() }
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_USER_PRESENT) {
                // Let the keyguard state settle before checking it.
                mainHandler.postDelayed(promptCheck, 400)
            }
        }
    }

    private val keypadDigitId = Regex(
        "(?:key|digit|num)[_-]?([0-9])$",
        RegexOption.IGNORE_CASE
    )
    private val keypadEnterId = Regex(
        "(?:enter|ok|okay|done|confirm|check|submit|verify|proceed|continue|next|forward|arrow)",
        RegexOption.IGNORE_CASE
    )

    @Volatile
    var currentPackage: String? = null
        private set

    @Volatile
    var currentActivity: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "accessibility service connected")
        serviceInfo?.let { info ->
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = info
        }

        try {
            ContextCompat.registerReceiver(
                this,
                userPresentReceiver,
                IntentFilter(Intent.ACTION_USER_PRESENT),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (t: Throwable) {
            Log.w(TAG, "could not register unlock prompt receiver", t)
        }

        // The setup flow may already have unlocked the device before the
        // service connected; check once after startup as well.
        mainHandler.postDelayed(promptCheck, 5_000)

        // The service may connect while the credential bouncer is already
        // visible, so classify it now.
        maybeDetectLockType()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastContentChange.set(SystemClock.elapsedRealtime())
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { pkg ->
                currentPackage = pkg
                pushStateIfPackageChanged(pkg)
            }
            event.className?.toString()?.let { currentActivity = it }
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        ) {
            maybeDetectLockType()
        }
        if (AgentService.recording) {
            try {
                AgentService.instance?.onRecordEvent(event)
            } catch (_: Throwable) {
                // The recorder must never break accessibility delivery.
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        mainHandler.removeCallbacks(promptCheck)
        try {
            unregisterReceiver(userPresentReceiver)
        } catch (_: Throwable) {
            // Not registered, or the process is tearing down.
        }
        if (instance === this) instance = null
        Log.i(TAG, "accessibility service destroyed")
        super.onDestroy()
    }

    // ---------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------

    suspend fun captureSnapshot(): Snapshot = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        waitForIdle()

        val bitmap = try {
            takeScreenshotBitmap()
        } catch (t: Throwable) {
            return@withContext errorSnapshot(started, screenshotError(t))
        } ?: return@withContext errorSnapshot(started, "SCREENSHOT_NULL")

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        val scaled = downscale(bitmap, MAX_SCREENSHOT_EDGE)
        val root = try {
            getRootInActiveWindow(ROOT_FLAGS)
        } catch (_: Throwable) {
            null
        }

        val stats = TreeStats()
        val raw = mutableListOf<RawElement>()
        collect(root, raw, stats, 0)
        val elements = number(originalWidth, originalHeight, raw)
        val (path, bytes) = saveWebp(scaled)

        Snapshot(
            id = UUID.randomUUID().toString(),
            capturedAt = System.currentTimeMillis(),
            width = scaled.width,
            height = scaled.height,
            rotation = currentRotation(),
            packageName = root?.packageName?.toString() ?: currentPackage,
            activityName = currentActivity,
            elements = elements,
            bitmap = scaled,
            imagePath = path,
            imageBytes = bytes,
            nodeCount = stats.nodeCount,
            maxDepth = stats.maxDepth,
            elapsedMs = SystemClock.elapsedRealtime() - started
        )
    }

    suspend fun probe(packageName: String): ProbeResult = withContext(Dispatchers.Default) {
        val started = SystemClock.elapsedRealtime()
        if (!launchApp(packageName)) {
            return@withContext ProbeResult(
                packageName = packageName,
                verdict = "blocked",
                screenshotOk = false,
                secureWindow = false,
                nodeCount = 0,
                clickableCount = 0,
                viewIdCount = 0,
                depth = 0,
                elapsedMs = SystemClock.elapsedRealtime() - started,
                error = "LAUNCH_FAILED"
            )
        }

        delay(1500)
        awaitPackage(packageName, 5000)
        delay(600)
        waitForIdle()
        val snapshot = captureSnapshot()

        val clickable = snapshot.elements.count { it.clickable }
        val withId = snapshot.elements.count { !it.viewId.isNullOrBlank() }
        val hasLabel = snapshot.elements.any {
            !it.text.isNullOrBlank() || !it.desc.isNullOrBlank()
        }
        val verdict = when {
            snapshot.error != null -> "blocked"
            clickable >= 5 && (withId > 0 || hasLabel) -> "controllable"
            clickable >= 1 -> "partial"
            else -> "blocked"
        }

        ProbeResult(
            packageName = packageName,
            verdict = verdict,
            screenshotOk = snapshot.error == null,
            secureWindow = snapshot.error == "SECURE_WINDOW",
            nodeCount = snapshot.nodeCount,
            clickableCount = clickable,
            viewIdCount = withId,
            depth = snapshot.maxDepth,
            elapsedMs = SystemClock.elapsedRealtime() - started,
            error = snapshot.error
        )
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    fun launchApp(packageName: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        return try {
            startActivity(intent)
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun awaitPackage(packageName: String, timeoutMs: Long = 5000): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (currentPackage == packageName) return true
            delay(150)
        }
        return currentPackage == packageName
    }

    /**
     * Phase 0 demo: relaunch the app and tap the stored coordinates.
     * The real controller taps while the target app is already foreground.
     */
    suspend fun replayTap(snapshot: Snapshot, element: Element): Boolean =
        withContext(Dispatchers.Default) {
            val pkg = snapshot.packageName ?: return@withContext false
            if (!launchApp(pkg)) return@withContext false
            delay(1500)
            awaitPackage(pkg, 5000)
            delay(1000)
            waitForIdle()
            tapElement(element)
        }

    fun tapElement(element: Element): Boolean {
        val root = try {
            getRootInActiveWindow(ROOT_FLAGS)
        } catch (_: Throwable) {
            null
        }
        val node = findBySelector(root, element)
        if (node != null) {
            try {
                if (node.isClickable &&
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                ) {
                    return true
                }
            } catch (_: Throwable) {
                // Fall through to a coordinate tap.
            }
        }
        return tapAt(element.boundsPx.centerX(), element.boundsPx.centerY())
    }

    fun tapAt(x: Int, y: Int): Boolean {
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0, 60)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }
    }

    fun setText(element: Element, text: String): Boolean {
        val root = try {
            getRootInActiveWindow(ROOT_FLAGS)
        } catch (_: Throwable) {
            null
        }
        val node = findBySelector(root, element) ?: return false
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Throwable) {
            false
        }
    }

    fun scroll(element: Element, forward: Boolean): Boolean {
        val root = try {
            getRootInActiveWindow(ROOT_FLAGS)
        } catch (_: Throwable) {
            null
        }
        val node = findBySelector(root, element)
        val action = if (forward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (node != null) {
            try {
                if (node.performAction(action)) return true
            } catch (_: Throwable) {
                // Fall back to a swipe.
            }
        }
        val centerX = element.boundsPx.centerX()
        val startY = if (forward) element.boundsPx.bottom - 24 else element.boundsPx.top + 24
        val endY = if (forward) element.boundsPx.top + 24 else element.boundsPx.bottom - 24
        return swipe(centerX, startY, centerX, endY, 320)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (_: Throwable) {
            false
        }
    }

    // ---------------------------------------------------------------------
    // Keyguard PIN entry
    // ---------------------------------------------------------------------

    private data class KeypadScan(
        val digits: Map<Char, Point>,
        val enter: Point?,
        val packageName: String? = null,
        val grid: Rect? = null,
        val keyNodes: Map<Char, AccessibilityNodeInfo> = emptyMap()
    )

    /**
     * Enters a stored PIN or password on the lock screen.
     *
     * The keyguard is a system window that a normal app cannot read or write
     * credentials into. An enabled accessibility service can, however, dispatch
     * real touch gestures at screen coordinates and set text on the bouncer's
     * credential field.
     *
     * A numeric credential on a PIN keypad is tapped key by key. On a password
     * bouncer the focused EditText is filled with ACTION_SET_TEXT, which also
     * handles letters and symbols without driving the keyboard. A pattern lock
     * is traced as one continuous gesture through the 3x3 dots.
     *
     * One attempt per command: Android escalates failed unlock attempts, and a
     * blind retry can burn another one.
     */
    suspend fun unlockWithCredential(
        secret: String,
        allowCachedLayout: Boolean
    ): PinUnlockResult = withContext(Dispatchers.Default) {
        if (!DevicePin.isValid(secret)) {
            return@withContext PinUnlockResult(false, "Stored credential is invalid")
        }

        val keyguard = getSystemService(KeyguardManager::class.java)
            ?: return@withContext PinUnlockResult(false, "Keyguard service is unavailable")
        if (!keyguard.isKeyguardLocked) {
            return@withContext PinUnlockResult(true, "Device is already unlocked")
        }
        if (!keyguard.isDeviceSecure) {
            return@withContext PinUnlockResult(
                false,
                "Device has no secure lock; use the normal unlock action"
            )
        }

        waitForInteractive()

        var keypad = scanKeypad()
        if (keypad.digits.size < 10) {
            // Most lock screens need a swipe up before the credential bouncer
            // is shown, especially when biometrics are enrolled.
            revealKeypad()
            keypad = scanKeypad()
        }
        if (keypad.digits.size < 10) {
            findPinSwitch()?.let { point ->
                if (tapAndAwait(point.x, point.y)) {
                    delay(400)
                    keypad = scanKeypad()
                }
            }
        }

        val (width, height) = displaySize()
        val rotation = currentRotation()

        // No numeric keypad: this is a password/hidden-field bouncer. The
        // keyguard exposes a focused EditText, and ACTION_SET_TEXT handles the
        // whole credential at once.
        if (keypad.digits.size < 10) {
            findLockScreenField()?.let { field ->
                enterPassword(field, secret)?.let { return@withContext it }
            }
        }

        // Pattern bouncers expose a square LockPatternView with no per-dot
        // nodes. Derive the 3x3 dot centers from the view bounds and trace the
        // stored 1–9 sequence in one continuous gesture.
        if (keypad.digits.size < 10) {
            findPatternView()?.let { bounds ->
                return@withContext enterPattern(bounds, secret)
            }
        }

        var digits = keypad.digits
        var enter = keypad.enter
        var usedCache = false

        if (digits.size >= 10) {
            PinKeypadStore.save(
                this@RcAccessibilityService,
                width,
                height,
                rotation,
                KeypadLayout(
                    digits = digits.mapValues { (_, point) ->
                        PointF(point.x.toFloat() / width, point.y.toFloat() / height)
                    },
                    enter = enter?.let {
                        PointF(it.x.toFloat() / width, it.y.toFloat() / height)
                    }
                )
            )
        } else if (allowCachedLayout) {
            val cached = PinKeypadStore.load(
                this@RcAccessibilityService,
                width,
                height,
                rotation
            )
            if (cached != null) {
                digits = cached.digits.mapValues { (_, point) ->
                    Point((point.x * width).toInt(), (point.y * height).toInt())
                }
                enter = cached.enter?.let {
                    Point((it.x * width).toInt(), (it.y * height).toInt())
                }
                usedCache = true
            }
        }

        if (digits.size < 10) {
            return@withContext PinUnlockResult(
                false,
                "Lock-screen field or keypad not exposed to accessibility " +
                    "(${keypad.digits.size}/10 keys found). Unlock on the device."
            )
        }

        if (!secret.all { it.isDigit() }) {
            return@withContext PinUnlockResult(
                false,
                "The lock screen is a PIN keypad, but the stored credential is not numeric."
            )
        }

        if (!keyguard.isKeyguardLocked) {
            return@withContext PinUnlockResult(true, "Device was unlocked on the device")
        }

        for ((index, digit) in secret.withIndex()) {
            if (!keyguard.isKeyguardLocked) {
                return@withContext PinUnlockResult(true, "Device was unlocked on the device")
            }
            val point = digits[digit]
            if (point == null) {
                if (usedCache) PinKeypadStore.clear(this@RcAccessibilityService)
                return@withContext PinUnlockResult(false, "Keypad is missing a required digit")
            }
            if (!tapAndAwait(point.x, point.y)) {
                if (usedCache) PinKeypadStore.clear(this@RcAccessibilityService)
                return@withContext PinUnlockResult(
                    false,
                    "Tap gesture was rejected at digit ${index + 1}"
                )
            }
            delay(110)
        }

        // AOSP auto-submits at the correct length. OEM keypads may need Enter.
        if (awaitUnlocked(1800)) {
            return@withContext PinUnlockResult(
                true,
                if (usedCache) "Unlocked with device PIN (cached keypad)"
                else "Unlocked with device PIN"
            )
        }
        if (!keyguard.isKeyguardLocked) {
            return@withContext PinUnlockResult(true, "Unlocked with device PIN")
        }

        val enterPoint = enter ?: findEnterButton()
        if (enterPoint != null &&
            tapAndAwait(enterPoint.x, enterPoint.y) &&
            awaitUnlocked(2500)
        ) {
            return@withContext PinUnlockResult(
                true,
                if (usedCache) "Unlocked with device PIN (cached keypad)"
                else "Unlocked with device PIN"
            )
        }

        if (usedCache) PinKeypadStore.clear(this@RcAccessibilityService)
        PinUnlockResult(
            false,
            "PIN entry did not unlock the device. Android limits repeated attempts."
        )
    }

    /**
     * The admin panel's App unlock section targets the foreground app. Push a
     * state update when the foreground package changes so its Save/Unlock
     * controls do not stay stuck on the previous app for up to ten seconds.
     */
    private fun pushStateIfPackageChanged(pkg: String) {
        if (pkg == lastPushedPackage) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatePushAt < 1_000) return
        lastPushedPackage = pkg
        lastStatePushAt = now
        try {
            AgentService.instance?.refreshState()
        } catch (_: Throwable) {
            // The relay service may not be connected yet.
        }
    }

    /**
     * Infer whether the visible bouncer is a PIN keypad, a pattern view, or a
     * password field. Android has no public API for this, so cache what the
     * accessibility tree shows while the keyguard is up.
     */
    private fun maybeDetectLockType() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLockTypeScan.get() < 800) return
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isKeyguardLocked) return

        mainHandler.post {
            val latest = getSystemService(KeyguardManager::class.java)
            if (latest?.isKeyguardLocked != true) return@post
            val type = when {
                findPatternView() != null -> LockType.PATTERN
                scanKeypad().digits.size >= 10 -> LockType.PIN
                findLockScreenField() != null -> LockType.PASSWORD
                else -> LockType.UNKNOWN
            }
            if (type != LockType.UNKNOWN) {
                // Only throttle after a real classification; the clock screen
                // and the bouncer can both fire events within a second.
                lastLockTypeScan.set(SystemClock.elapsedRealtime())
                LockTypeStore.set(this, type)
            }
        }
    }

    /**
     * Offer the one-time local enrollment after a manual unlock when the device
     * is secure and no credential is stored.
     */
    private fun maybePromptForUnlockEnrollment() {
        if (DevicePin.isConfigured(this)) return
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return
        if (!keyguard.isDeviceSecure || keyguard.isKeyguardLocked) return
        if (!UnlockEnrollment.shouldPrompt(this)) return
        UnlockEnrollment.prompt(this)
    }

    private suspend fun waitForInteractive(timeoutMs: Long = 1500) {
        val power = getSystemService(PowerManager::class.java)
        val started = SystemClock.elapsedRealtime()
        while (power?.isInteractive != true &&
            SystemClock.elapsedRealtime() - started < timeoutMs
        ) {
            delay(100)
        }
    }

    private suspend fun revealKeypad() {
        val (width, height) = displaySize()
        swipe(
            width / 2,
            (height * 0.82f).toInt(),
            width / 2,
            (height * 0.28f).toInt(),
            260
        )
        delay(500)
    }

    private suspend fun awaitUnlocked(timeoutMs: Long): Boolean {
        val keyguard = getSystemService(KeyguardManager::class.java) ?: return false
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (!keyguard.isKeyguardLocked) return true
            delay(100)
        }
        return !keyguard.isKeyguardLocked
    }

    /**
     * The password bouncer's focused EditText, or a text field in the keyguard
     * window. Only system windows are considered so an app behind the keyguard
     * cannot be mistaken for the credential prompt.
     */
    private fun findLockScreenField(): AccessibilityNodeInfo? {
        val roots = try {
            windows
                .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
                .sortedByDescending { it.isFocused }
                .mapNotNull { window ->
                    try {
                        window.root
                    } catch (_: Throwable) {
                        null
                    }
                }
        } catch (_: Throwable) {
            emptyList()
        }
        val candidates = roots.ifEmpty { listOfNotNull(activeRoot()) }
        for (root in candidates) {
            val focused = try {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            } catch (_: Throwable) {
                null
            }
            val target = focused?.let { editableField(it) } ?: findFirstEditable(root)
            if (target != null) return target
        }
        return null
    }

    private fun editableField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        findEditableAncestor(node)?.let { return it }
        val supportsSetText = node.actionList.any {
            it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
        }
        return if (supportsSetText) node else null
    }

    /**
     * Fills a password field in one shot. Returns null when the field rejected
     * the text (so the caller can still try a cached keypad); otherwise it
     * returns the final result, including a failed unlock.
     */
    private suspend fun enterPassword(
        field: AccessibilityNodeInfo,
        secret: String
    ): PinUnlockResult? {
        val set = try {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    secret
                )
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Throwable) {
            false
        }
        if (!set) return null

        delay(150)
        try {
            val target = findLockScreenField() ?: field
            target.performAction(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            )
        } catch (_: Throwable) {
            // The check/enter button below is the fallback.
        }
        if (awaitUnlocked(2500)) {
            return PinUnlockResult(true, "Unlocked with device password")
        }

        findEnterButton()?.let { point ->
            if (tapAndAwait(point.x, point.y) && awaitUnlocked(1500)) {
                return PinUnlockResult(true, "Unlocked with device password")
            }
        }

        return PinUnlockResult(
            false,
            "The stored credential did not unlock the device. Android limits repeated attempts."
        )
    }

    /**
     * Enters an app-level PIN or password into the foreground app.
     *
     * App lock screens are ordinary app windows (or an app-locker overlay), not
     * the keyguard, so a labeled digit keypad and a focused text field are both
     * usable. The secret comes from the macro's `secretRef`; it is not logged
     * or included in the run report.
     */
    suspend fun enterPin(
        secret: String,
        selector: Selector? = null,
        submit: Boolean = true,
        pinMethod: String = "gesture"
    ): PinUnlockResult = withContext(Dispatchers.Default) {
        if (secret.isBlank()) {
            return@withContext PinUnlockResult(false, "No PIN value was provided")
        }

        val scopedRoot = selector?.let { findNode(it) }
        if (selector != null && scopedRoot == null) {
            return@withContext PinUnlockResult(false, "PIN entry container not found")
        }

        val digitsOnly = secret.all { it.isDigit() }
        var keypad = scanKeypad(scopedRoot, activeOnly = scopedRoot == null)
        var field = findAppField()

        // A progress dialog often sits between the previous screen and PIN
        // entry. A UPI PIN screen also exposes a hidden editable field before
        // its keypad is ready; filling that field does not submit the PIN, so
        // a numeric secret waits for the real keypad. Only accept a keypad
        // from the active window: a progress dialog on top of the PIN screen
        // would otherwise swallow the digit taps.
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (digitsOnly && keypad.digits.size < 10 &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            delay(250)
            keypad = scanKeypad(
                selector?.let { findNode(it) },
                activeOnly = selector == null
            )
            field = findAppField()
        }
        if (digitsOnly && keypad.digits.size < 10 && selector == null) {
            // Some PIN pads live in a non-focused window. Try the full window
            // set once the progress dialog has had time to close.
            keypad = scanKeypad()
        }

        if (digitsOnly && keypad.digits.size >= 10) {
            val activity = currentActivity ?: currentPackage ?: "unknown"
            val source = listOfNotNull(keypad.packageName, activity)
                .distinct()
                .joinToString(" / ")
            val gridNote = keypad.grid?.let {
                "grid [${it.left},${it.top}][${it.right},${it.bottom}]"
            } ?: "grid unknown"
            val startLength = pinFieldLength()
            for ((index, digit) in secret.withIndex()) {
                val point = keypad.digits[digit]
                    ?: return@withContext PinUnlockResult(
                        false,
                        "PIN keypad is missing the digit '$digit' on $source"
                    )
                val keyNode = keypad.keyNodes[digit]
                val clicked = if (pinMethod == "click" && keyNode != null) {
                    try {
                        keyNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    } catch (_: Throwable) {
                        false
                    }
                } else {
                    false
                }
                if (!clicked && !tapAndAwait(point.x, point.y, 120)) {
                    return@withContext PinUnlockResult(
                        false,
                        "Keypad tap failed at digit ${index + 1} on $source"
                    )
                }
                delay(120)
            }
            val endLength = pinFieldLength()
            if (submit) {
                delay(200)
                val enter = keypad.enter
                    ?: findEnterButton()
                    ?: keypadCheckPoint(keypad)
                    ?: findBottomRightCheck()
                    ?: return@withContext PinUnlockResult(
                        false,
                        "PIN entered, but no submit/check button was found on $source"
                    )
                if (!tapAndAwait(enter.x, enter.y)) {
                    return@withContext PinUnlockResult(
                        false,
                        "PIN entered, but the submit/check tap failed on $source"
                    )
                }
            }
            val lengthNote = if (startLength != null && endLength != null) {
                "field $startLength→$endLength"
            } else {
                "field unreadable"
            }
            return@withContext PinUnlockResult(
                true,
                "PIN entered with keypad ($pinMethod, ${secret.length} digits, $lengthNote, $gridNote) on $source"
            )
        }

        val targetField = field
            ?: return@withContext PinUnlockResult(
                false,
                if (digitsOnly) {
                    "No numeric PIN keypad or text field was found " +
                        "(activity: ${currentActivity ?: currentPackage ?: "unknown"})"
                } else {
                    "No PIN/password field was found " +
                        "(activity: ${currentActivity ?: currentPackage ?: "unknown"})"
                }
            )
        val source = currentActivity ?: currentPackage ?: "unknown"
        val set = try {
            targetField.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    secret
                )
            }
            targetField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Throwable) {
            false
        }
        if (!set) {
            return@withContext PinUnlockResult(
                false,
                "The PIN/password field did not accept text on $source"
            )
        }

        delay(150)
        if (submit) {
            val enterButton = findEnterButton()
            if (enterButton != null) {
                if (!tapAndAwait(enterButton.x, enterButton.y)) {
                    return@withContext PinUnlockResult(
                        false,
                        "The submit/check tap failed on $source"
                    )
                }
            } else {
                try {
                    targetField.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                    )
                } catch (_: Throwable) {
                    // The app did not expose an IME action.
                }
            }
        }
        PinUnlockResult(
            true,
            "PIN filled into a text field (${secret.length} chars) on $source"
        )
    }

    /** Length of the visible PIN/password field, when the app exposes it. */
    private fun pinFieldLength(): Int? {
        val field = findAppField() ?: return null
        return try {
            field.text?.length
        } catch (_: Throwable) {
            null
        }
    }

    private fun findAppField(): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Throwable) {
            null
        }
        return focused?.let { editableField(it) } ?: findFirstEditable(root)
    }

    private fun findPatternView(): Rect? {
        var best: Rect? = null
        var bestScore = Float.MIN_VALUE
        for (root in orderedWindowRoots()) {
            collectPatternViews(root, 0) { node, bounds ->
                val ratio = bounds.width().toFloat() / bounds.height().coerceAtLeast(1)
                if (ratio in 0.55f..1.8f && bounds.width() > 240) {
                    val strong =
                        node.viewIdResourceName.orEmpty().endsWith("lockPatternView") ||
                            node.className?.toString().orEmpty().contains("LockPatternView")
                    val score =
                        (if (strong) 3f else 0f) +
                            (if (node.isClickable) 1.5f else 0f) +
                            (1f - abs(1f - ratio))
                    if (score > bestScore) {
                        bestScore = score
                        best = Rect(bounds)
                    }
                }
            }
        }
        return best ?: findUnlabeledPatternView()
    }

    /**
     * OEM and Google confirmation screens sometimes expose the pattern canvas
     * as an unlabeled, large, clickable square. Use that as a last resort.
     */
    private fun findUnlabeledPatternView(): Rect? {
        var best: Rect? = null
        var bestArea = 0
        for (root in orderedWindowRoots()) {
            collectLargeSquareViews(root, 0) { _, bounds ->
                val ratio = bounds.width().toFloat() / bounds.height().coerceAtLeast(1)
                if (ratio in 0.85f..1.15f && bounds.width() > 280) {
                    val area = bounds.width() * bounds.height()
                    if (area > bestArea) {
                        bestArea = area
                        best = Rect(bounds)
                    }
                }
            }
        }
        return best
    }

    private fun collectLargeSquareViews(
        node: AccessibilityNodeInfo?,
        depth: Int,
        onMatch: (AccessibilityNodeInfo, Rect) -> Unit
    ) {
        if (node == null || depth > 60) return
        val className = node.className?.toString().orEmpty()
        val isControl = className.contains("Button") ||
            className.contains("TextView") ||
            className.contains("EditText") ||
            className.contains("Image")
        if (node.isClickable && !isControl) {
            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: Throwable) {
                // Stale node; keep looking.
            }
            if (!bounds.isEmpty) onMatch(node, bounds)
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            collectLargeSquareViews(child, depth + 1, onMatch)
        }
    }

    private fun collectPatternViews(
        node: AccessibilityNodeInfo?,
        depth: Int,
        onMatch: (AccessibilityNodeInfo, Rect) -> Unit
    ) {
        if (node == null || depth > 60) return
        val viewId = node.viewIdResourceName.orEmpty()
        val className = node.className?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val matches =
            viewId.endsWith("lockPatternView") ||
                viewId.contains("pattern", ignoreCase = true) ||
                className.contains("PatternView") ||
                (description.contains("pattern", ignoreCase = true) &&
                    !description.contains("incorrect", ignoreCase = true))
        if (matches) {
            val bounds = Rect()
            try {
                node.getBoundsInScreen(bounds)
            } catch (_: Throwable) {
                // Stale node; keep looking.
            }
            if (!bounds.isEmpty) onMatch(node, bounds)
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            collectPatternViews(child, depth + 1, onMatch)
        }
    }

    /**
     * Traces the stored dot sequence through the 3x3 pattern view. The view is
     * one custom canvas node, so the dot centers come from its square bounds.
     */
    private suspend fun enterPattern(
        bounds: Rect,
        secret: String
    ): PinUnlockResult {
        if (!DevicePin.isValidPattern(secret)) {
            return PinUnlockResult(
                false,
                "The lock screen is a pattern, but the stored credential is not " +
                    "a 4–9 dot sequence."
            )
        }
        if (!drawPattern(bounds, secret)) {
            return PinUnlockResult(false, "Pattern gesture was rejected")
        }
        if (awaitUnlocked(2_500)) {
            return PinUnlockResult(true, "Unlocked with device pattern")
        }
        return PinUnlockResult(
            false,
            "The stored pattern did not unlock the device. Android limits repeated attempts."
        )
    }

    private suspend fun drawPattern(bounds: Rect, secret: String): Boolean {
        if (!DevicePin.isValidPattern(secret)) return false

        // LockPatternView centers a square grid inside its bounds. Using the
        // shorter edge also handles confirmation screens with extra padding.
        val side = min(bounds.width(), bounds.height()).toFloat()
        val left = bounds.left + (bounds.width() - side) / 2f
        val top = bounds.top + (bounds.height() - side) / 2f
        val cell = side / 3f
        val points = secret.map { digit ->
            val index = digit - '1'
            val row = index / 3
            val column = index % 3
            PointF(
                left + (column + 0.5f) * cell,
                top + (row + 0.5f) * cell
            )
        }
        val path = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            // A small loop inside the dot gives the view extra samples at each
            // dot, without leaving the touch target.
            path.lineTo(point.x + 10f, point.y)
            path.lineTo(point.x, point.y)
        }

        // A slower stroke is more reliable across OEM/confirm-credential views.
        return gestureAndAwait(path, 1_200)
    }

    /**
     * Unlocks an app-lock screen in the foreground.
     *
     * App locks come in two shapes:
     * - a screen-lock fallback (MIUI/POCO App lock, Samsung, etc.) where the
     *   prompt offers fingerprint and then the device PIN/pattern/password;
     * - an app-specific PIN, password, or pattern.
     *
     * Taps the "Use PIN/password/pattern/screen lock" option when present, then
     * enters the per-app credential if one is stored, otherwise the device
     * credential. One attempt; the caller can verify on the device.
     */
    suspend fun unlockApp(targetPackage: String? = null): PinUnlockResult =
        withContext(Dispatchers.Default) {
            val target = targetPackage?.takeIf { it.isNotBlank() } ?: currentPackage
            if (target != null && currentPackage == target && !hasAppLockScreen()) {
                return@withContext PinUnlockResult(
                    true,
                    "No app-lock screen is showing"
                )
            }
            val startPackage = currentPackage
            val appSecret = target?.let {
                AppUnlockStore.read(this@RcAccessibilityService, it)
            }
            val deviceSecret = DevicePin.read(this@RcAccessibilityService)
            val secret = appSecret ?: deviceSecret
            if (secret == null || !DevicePin.isValid(secret)) {
                return@withContext PinUnlockResult(
                    false,
                    "No app credential or device unlock credential is stored."
                )
            }

            // Some devices show the credential field immediately; others show a
            // biometric prompt with a "Use PIN/password/pattern" fallback.
            var fallback = findScreenLockOption()
            if (fallback == null && !isCredentialScreenPresent()) {
                fallback = awaitScreenLockOption(2_500)
            }
            var fallbackTapped = false
            if (fallback != null) {
                if (!tapAndAwait(fallback.x, fallback.y)) {
                    return@withContext PinUnlockResult(
                        false,
                        "Could not open the Use password/PIN/pattern option."
                    )
                }
                fallbackTapped = true
                // The system credential screen can appear after the biometric
                // sheet animates out; poll instead of assuming it is immediate.
                awaitCredentialScreen(2_500)
                awaitFallbackGone(1_500)
                delay(300)
            }

            if (!awaitCredentialScreen(1_000)) {
                return@withContext PinUnlockResult(
                    false,
                    if (fallbackTapped) {
                        "Tapped the Use password/PIN/pattern option, but no " +
                            "credential field appeared. Detected: ${describeAppLockUi()}"
                    } else {
                        "No Use password/PIN/pattern option or credential field is " +
                            "visible. If a biometric prompt is showing, tap Use " +
                            "password on the device and retry. Detected: " +
                            describeAppLockUi()
                    }
                )
            }

            val ui = describeAppLockUi()
            if (!enterAppCredential(secret)) {
                return@withContext PinUnlockResult(
                    false,
                    "Could not enter the credential on the app-lock screen. Detected: $ui"
                )
            }

            if (awaitAppUnlocked(target, startPackage)) {
                return@withContext PinUnlockResult(
                    true,
                    if (appSecret != null) "App unlocked with its stored credential"
                    else "App unlocked with the device credential"
                )
            }
            return@withContext PinUnlockResult(
                false,
                "The ${if (appSecret != null) "app" else "device"} credential did " +
                    "not unlock the app. Detected: $ui"
            )
        }

    private fun describeAppLockUi(): String {
        val pattern = findPatternView()
        val keypad = scanKeypad().digits.size
        val field = findLockScreenField() ?: findAppField()
        return buildString {
            append("pkg=").append(currentPackage ?: "?")
            append(", pattern=").append(pattern?.toShortString() ?: "none")
            append(", keypadKeys=").append(keypad)
            append(", field=").append(field?.className?.toString() ?: "none")
        }
    }

    /**
     * Strong signals that an app-lock prompt is on screen. Ordinary app screens
     * (a dialer keypad, a check-balance form) must not be mistaken for a lock.
     */
    private fun hasAppLockScreen(): Boolean {
        val activity = currentActivity.orEmpty()
        if (activity.contains("GetCredential", ignoreCase = true) ||
            activity.contains("pinactivitycomponent", ignoreCase = true)
        ) {
            // The UPI PIN screen has a keypad and PIN text, but it is not an
            // app-lock prompt.
            return false
        }
        if (activity.contains("LockActivity", ignoreCase = true) ||
            activity.contains("AppLock", ignoreCase = true) ||
            activity.contains("LockScreen", ignoreCase = true) ||
            activity.contains("ConfirmDeviceCredential", ignoreCase = true) ||
            activity.contains("Bouncer", ignoreCase = true)
        ) {
            return true
        }

        if (findScreenLockOption() != null) return true
        if (findPatternView() != null) return true
        if (findLockScreenField() != null) return true
        val field = findAppField()
        if (field?.isPassword == true) return true
        if (scanKeypad().digits.size >= 10 && hasLockText()) return true
        return false
    }

    private fun hasLockText(): Boolean {
        val terms = listOf(
            "unlock", "enter pin", "enter your pin", "confirm pin",
            "confirm your pin", "password", "pattern", "screen lock",
            "phonepe pin"
        )
        for (root in orderedWindowRoots()) {
            if (containsText(root, terms, 0)) return true
        }
        return false
    }

    private fun containsText(
        node: AccessibilityNodeInfo?,
        terms: List<String>,
        depth: Int
    ): Boolean {
        if (node == null || depth > 60) return false
        val label = labelOf(node)?.lowercase()
        if (label != null && terms.any { label.contains(it) }) return true
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            if (containsText(child, terms, depth + 1)) return true
        }
        return false
    }

    private fun findScreenLockOption(): Point? {
        for (root in orderedWindowRoots()) {
            findClickableLabelContaining(root, SCREEN_LOCK_TERMS, 0)?.let { return it }
        }
        return null
    }

    private suspend fun awaitScreenLockOption(timeoutMs: Long): Point? {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            findScreenLockOption()?.let { return it }
            delay(150)
        }
        return findScreenLockOption()
    }

    private suspend fun awaitCredentialScreen(timeoutMs: Long): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (isCredentialScreenPresent()) return true
            delay(150)
        }
        return isCredentialScreenPresent()
    }

    private suspend fun awaitFallbackGone(timeoutMs: Long) {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < timeoutMs) {
            if (findScreenLockOption() == null) return
            delay(150)
        }
    }

    private fun findClickableLabelContaining(
        node: AccessibilityNodeInfo?,
        terms: List<String>,
        depth: Int
    ): Point? {
        if (node == null || depth > 60) return null
        val label = labelOf(node)?.lowercase()
        if (label != null && terms.any { label.contains(it) }) {
            clickableBounds(node, requireClickable = true)?.let {
                return Point(it.centerX(), it.centerY())
            }
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            findClickableLabelContaining(child, terms, depth + 1)?.let { return it }
        }
        return null
    }

    private fun isCredentialScreenPresent(): Boolean =
        findPatternView() != null ||
            scanKeypad().digits.size >= 10 ||
            findLockScreenField() != null ||
            findAppField() != null

    private suspend fun enterAppCredential(secret: String): Boolean {
        findPatternView()?.let { bounds ->
            return drawPattern(bounds, secret)
        }

        val keypad = scanKeypad()
        if (secret.all { it.isDigit() } && keypad.digits.size >= 10) {
            for (digit in secret) {
                val point = keypad.digits[digit] ?: return false
                if (!tapAndAwait(point.x, point.y)) return false
                delay(80)
            }
            val enter = keypad.enter ?: findEnterButton()
            enter?.let { tapAndAwait(it.x, it.y) }
            return true
        }

        val field = findLockScreenField() ?: findAppField() ?: return false
        val set = try {
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    secret
                )
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Throwable) {
            false
        }
        if (!set) return false

        delay(150)
        val enterButton = findEnterButton()
        if (enterButton != null) {
            tapAndAwait(enterButton.x, enterButton.y)
        } else {
            try {
                field.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
            } catch (_: Throwable) {
                // The app did not expose an IME action.
            }
        }
        return true
    }

    private suspend fun awaitAppUnlocked(
        target: String?,
        startPackage: String?
    ): Boolean {
        val started = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - started < 3_000) {
            val fallback = findScreenLockOption()
            val credentialUi = isCredentialScreenPresent()
            if (fallback == null && !credentialUi) return true

            // A system app-lock window (MIUI/POCO, Samsung) hands control to the
            // target app; an in-app lock keeps the same package and must be
            // verified by the credential UI disappearing.
            if (target != null &&
                startPackage != target &&
                currentPackage == target
            ) {
                return true
            }
            delay(200)
        }
        return findScreenLockOption() == null && !isCredentialScreenPresent()
    }

    private suspend fun tapAndAwait(
        x: Int,
        y: Int,
        durationMs: Long = 60
    ): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        return gestureAndAwait(path, durationMs)
    }

    private suspend fun gestureAndAwait(
        path: Path,
        durationMs: Long,
        timeoutMs: Long = if (durationMs > 1_000) durationMs + 2_000 else 1_500
    ): Boolean {
        val completed = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val stroke = GestureDescription.StrokeDescription(path, 0, durationMs)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                val callback = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
                val dispatched = try {
                    dispatchGesture(gesture, callback, null)
                } catch (_: Throwable) {
                    false
                }
                if (!dispatched && cont.isActive) cont.resume(false)
            }
        }
        return completed == true
    }

    private fun scanKeypad(
        root: AccessibilityNodeInfo? = null,
        activeOnly: Boolean = false
    ): KeypadScan {
        val roots = when {
            root != null -> listOf(root)
            activeOnly -> listOfNotNull(activeRoot())
            else -> {
                val active = activeRoot()
                // The topmost window is the PIN dialog. Try it before any
                // window behind it; mixing digit nodes from two windows
                // produces taps at the wrong coordinates.
                if (active != null) listOf(active) + orderedWindowRoots()
                else orderedWindowRoots()
            }
        }

        for (candidate in roots) {
            keypadIn(candidate, useClickableBounds = false)?.let { return it }
            keypadIn(candidate, useClickableBounds = true)?.let { return it }
        }
        return KeypadScan(emptyMap(), null, null, null)
    }

    private fun keypadIn(
        candidate: AccessibilityNodeInfo,
        useClickableBounds: Boolean
    ): KeypadScan? {
        val digits = linkedMapOf<Char, Point>()
        val enter = mutableListOf<Point>()
        val digitBounds = mutableListOf<Rect>()
        val keyNodes = linkedMapOf<Char, AccessibilityNodeInfo>()
        collectKeypad(candidate, digits, enter, digitBounds, keyNodes, useClickableBounds)
        // A password bouncer's QWERTY row (or a numeric IME) can expose
        // ten "digit" nodes. Only accept a real 3-column keypad.
        if (digits.size < 10 || !looksLikeKeypad(digits)) return null
        // Prefer the clickable key bounds: on some PIN pads the digit label
        // nodes are virtual and sit outside the real keypad rectangle.
        val keyBounds = keyNodes.values.mapNotNull { nodeBounds(it) }
        val gridSource = if (keyBounds.isNotEmpty()) keyBounds else digitBounds
        val grid = if (gridSource.isEmpty()) {
            null
        } else {
            Rect(
                gridSource.minOf { it.left },
                gridSource.minOf { it.top },
                gridSource.maxOf { it.right },
                gridSource.maxOf { it.bottom }
            )
        }
        return KeypadScan(
            digits = digits,
            enter = enter.firstOrNull(),
            packageName = candidate.packageName?.toString(),
            grid = grid,
            keyNodes = keyNodes
        )
    }

    private fun orderedWindowRoots(): List<AccessibilityNodeInfo> {
        val roots = mutableListOf<AccessibilityNodeInfo>()
        val ordered = try {
            windows
                .filter { it.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                .sortedWith(
                    compareByDescending<AccessibilityWindowInfo> {
                        it.type == AccessibilityWindowInfo.TYPE_SYSTEM
                    }.thenByDescending { it.isFocused }
                )
        } catch (_: Throwable) {
            emptyList()
        }
        for (window in ordered) {
            val root = try {
                window.root
            } catch (_: Throwable) {
                null
            } ?: continue
            roots += root
        }
        activeRoot()?.let { roots += it }
        return roots
    }

    private fun looksLikeKeypad(digits: Map<Char, Point>): Boolean {
        if (digits.size < 10) return false
        val (width, height) = displaySize()
        val rowTolerance = (height / 40).coerceAtLeast(24)
        val columnTolerance = (width / 40).coerceAtLeast(24)
        val rows = clusterCount(digits.values.map { it.y }, rowTolerance)
        val columns = clusterCount(digits.values.map { it.x }, columnTolerance)
        return rows >= 3 && columns in 3..4
    }

    private fun clusterCount(values: List<Int>, tolerance: Int): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        var count = 1
        var last = sorted.first()
        for (value in sorted.drop(1)) {
            if (value - last > tolerance) count++
            last = value
        }
        return count
    }

    private fun collectKeypad(
        node: AccessibilityNodeInfo?,
        digits: MutableMap<Char, Point>,
        enter: MutableList<Point>,
        digitBounds: MutableList<Rect>,
        keyNodes: MutableMap<Char, AccessibilityNodeInfo>,
        useClickableBounds: Boolean = false,
        depth: Int = 0
    ) {
        if (node == null || depth > 60) return
        val label = labelOf(node)?.lowercase()
        val digit = digitFromLabel(label) ?: digitFromViewId(node.viewIdResourceName)

        if (digit != null) {
            if (!digits.containsKey(digit)) {
                // Prefer the digit node's own bounds. Climbing to a clickable
                // ancestor can return the whole keypad on custom PIN pads, so
                // that is only a fallback when the node has no usable bounds.
                val bounds = if (useClickableBounds) {
                    clickableBounds(node)
                } else {
                    nodeBounds(node) ?: clickableBounds(node)
                }
                if (bounds != null) {
                    digits[digit] = Point(bounds.centerX(), bounds.centerY())
                    digitBounds += Rect(bounds)
                }
                keyClickTarget(node)?.let { keyNodes[digit] = it }
            }
        } else if (enter.size < 2 && isEnterNode(node, label)) {
            clickableBounds(node, requireClickable = true)?.let {
                enter += Point(it.centerX(), it.centerY())
            }
        }

        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            collectKeypad(
                child, digits, enter, digitBounds, keyNodes,
                useClickableBounds, depth + 1
            )
        }
    }

    /**
     * A clickable node for one digit key, but only when it is key-sized. This
     * avoids clicking a whole keypad container that happens to be clickable.
     */
    private fun keyClickTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val maxWidth = resources.displayMetrics.widthPixels * 0.35f
        val maxHeight = resources.displayMetrics.heightPixels * 0.2f
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val value = current ?: return null
            if (value.isClickable) {
                val bounds = nodeBounds(value) ?: return null
                return if (bounds.width() <= maxWidth && bounds.height() <= maxHeight) {
                    value
                } else {
                    null
                }
            }
            current = try {
                value.parent
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun digitFromLabel(label: String?): Char? =
        label?.takeIf { it.length == 1 && it[0] in '0'..'9' }?.get(0)

    private fun digitFromViewId(viewId: String?): Char? {
        val match = viewId?.let { keypadDigitId.find(it) } ?: return null
        return match.groupValues.getOrNull(1)?.firstOrNull()
    }

    private fun isEnterNode(node: AccessibilityNodeInfo, label: String?): Boolean {
        if (label != null && label in ENTER_LABELS) return true
        val viewId = node.viewIdResourceName ?: return false
        return keypadEnterId.containsMatchIn(viewId)
    }

    private fun findPinSwitch(): Point? =
        findClickableLabel(PIN_SWITCH_LABELS)

    private fun labelOf(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) return text
        val description = node.contentDescription?.toString()?.trim()
        return description?.takeIf { it.isNotEmpty() }
    }

    private fun findEnterButton(): Point? {
        val active = activeRoot()
        if (active != null) {
            findEnterIn(active, 0, allowContains = true, allowId = true)?.let {
                return it
            }
        }
        for (root in orderedWindowRoots()) {
            findEnterIn(root, 0, allowContains = false, allowId = false)?.let {
                return it
            }
        }
        return null
    }

    private fun findEnterIn(
        node: AccessibilityNodeInfo?,
        depth: Int,
        allowContains: Boolean,
        allowId: Boolean
    ): Point? {
        if (node == null || depth > 60) return null
        val label = labelOf(node)?.lowercase()
        val byLabel = label != null && (
            label in ENTER_LABELS ||
                (allowContains && ENTER_TERMS.any { label.contains(it) })
            )
        val viewId = node.viewIdResourceName
        val byId = allowId && viewId != null && keypadEnterId.containsMatchIn(viewId)
        if (byLabel || byId) {
            clickableBounds(node, requireClickable = true)?.let {
                return Point(it.centerX(), it.centerY())
            }
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            findEnterIn(child, depth + 1, allowContains, allowId)?.let { return it }
        }
        return null
    }

    /**
     * Last-resort submit detection for PIN pads whose check icon has no label
     * or stable id. The UPI PIN screen puts the submit control in the lower
     * right; choose the lowest clickable target there that is not a digit key.
     */
    /**
     * The standard NPCI keypad puts the check at the bottom-right cell of the
     * 3x4 digit grid. Some PhonePe builds draw it without an accessible node,
     * so derive the point from the digit grid.
     */
    private fun keypadCheckPoint(keypad: KeypadScan): Point? {
        val grid = keypad.grid ?: return null
        if (grid.width() < 100 || grid.height() < 100) return null
        val cellWidth = grid.width() / 3f
        val cellHeight = grid.height() / 4f
        val x = (grid.right - cellWidth / 2).toInt()
        val y = (grid.bottom - cellHeight / 2).toInt()
        val (width, height) = displaySize()
        if (x !in 0 until width || y !in 0 until height) return null
        return Point(x, y)
    }

    private fun findBottomRightCheck(): Point? {
        val (width, height) = displaySize()
        val minSize = (20 * resources.displayMetrics.density).toInt()
        val roots = mutableListOf<AccessibilityNodeInfo>()
        activeRoot()?.let { roots += it }
        roots += orderedWindowRoots()
        val seen = mutableSetOf<String>()
        for (root in roots) {
            var best: Rect? = null
            collectBottomRight(root, 0, width, height, minSize) { rect ->
                val key = "${rect.left},${rect.top},${rect.right},${rect.bottom}"
                if (!seen.add(key)) return@collectBottomRight
                val current = best
                if (current == null || rect.bottom > current.bottom ||
                    (rect.bottom == current.bottom && rect.right > current.right)
                ) {
                    best = rect
                }
            }
            best?.let { return Point(it.centerX(), it.centerY()) }
        }
        return null
    }

    private fun collectBottomRight(
        node: AccessibilityNodeInfo?,
        depth: Int,
        width: Int,
        height: Int,
        minSize: Int,
        onMatch: (Rect) -> Unit
    ) {
        if (node == null || depth > 60) return
        // The check icon is often a non-clickable ImageView inside a clickable
        // frame, so use the nearest clickable ancestor when there is one.
        val bounds = clickableBounds(node) ?: nodeBounds(node)
        if (bounds != null &&
            bounds.centerX() > width * 0.55f &&
            bounds.centerY() > height * 0.55f &&
            bounds.width() >= minSize &&
            bounds.height() >= minSize &&
            bounds.width() <= width * 0.9f &&
            bounds.height() <= height * 0.25f
        ) {
            onMatch(bounds)
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            collectBottomRight(child, depth + 1, width, height, minSize, onMatch)
        }
    }

    private fun findClickableLabel(labels: Set<String>): Point? {
        for (root in orderedWindowRoots()) {
            findClickableLabelIn(root, labels, 0)?.let { return it }
        }
        return null
    }

    private fun findClickableLabelIn(
        node: AccessibilityNodeInfo?,
        labels: Set<String>,
        depth: Int
    ): Point? {
        if (node == null || depth > 60) return null
        val label = labelOf(node)?.lowercase()
        if (label != null && label in labels) {
            clickableBounds(node, requireClickable = true)?.let {
                return Point(it.centerX(), it.centerY())
            }
        }
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            }
            findClickableLabelIn(child, labels, depth + 1)?.let { return it }
        }
        return null
    }

    private fun nodeBounds(node: AccessibilityNodeInfo): Rect? {
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) null else Rect(rect)
        } catch (_: Throwable) {
            null
        }
    }

    private fun clickableBounds(
        node: AccessibilityNodeInfo,
        requireClickable: Boolean = false
    ): Rect? {
        var current: AccessibilityNodeInfo? = node
        repeat(5) {
            val value = current ?: return null
            if (value.isClickable) {
                val rect = Rect()
                try {
                    value.getBoundsInScreen(rect)
                    if (!rect.isEmpty) return rect
                } catch (_: Throwable) {
                    // Keep walking up.
                }
            }
            current = try {
                value.parent
            } catch (_: Throwable) {
                null
            }
        }
        if (requireClickable) return null
        val rect = Rect()
        return try {
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) null else rect
        } catch (_: Throwable) {
            null
        }
    }

    private fun displaySize(): Pair<Int, Int> {
        return try {
            val bounds = getSystemService(WindowManager::class.java)
                ?.currentWindowMetrics
                ?.bounds
            if (bounds != null && !bounds.isEmpty) {
                bounds.width() to bounds.height()
            } else {
                resources.displayMetrics.widthPixels to
                    resources.displayMetrics.heightPixels
            }
        } catch (_: Throwable) {
            resources.displayMetrics.widthPixels to
                resources.displayMetrics.heightPixels
        }
    }

    fun performGlobalActionByName(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun exists(selector: Selector): Boolean = findNode(selector) != null

    fun findNode(selector: Selector): AccessibilityNodeInfo? {
        val root = activeRoot() ?: return null
        val remaining = intArrayOf((selector.index - 1).coerceAtLeast(0))
        return search(root, selector, 0, remaining)
    }

    fun tapSelector(selector: Selector): Boolean {
        val node = findNode(selector) ?: return false
        val bounds = Rect()
        val hasBounds = try {
            node.getBoundsInScreen(bounds)
            !bounds.isEmpty
        } catch (_: Throwable) {
            false
        }

        // A label inside a row is usually not clickable itself. Tap the label
        // like a finger would, so the row's touch handler runs; ACTION_CLICK on
        // the parent can report success while doing nothing.
        if (hasBounds && !node.isClickable) {
            if (tapAt(bounds.centerX(), bounds.centerY())) return true
        }

        val target = findClickableAncestor(node) ?: node
        if (target.isClickable) {
            try {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            } catch (_: Throwable) {
                // Fall through to a coordinate tap.
            }
        }
        return if (!hasBounds) false else tapAt(bounds.centerX(), bounds.centerY())
    }

    fun setTextSelector(selector: Selector, text: String): Boolean {
        val node = findNode(selector) ?: return false
        val target = if (node.isEditable) node else findEditableAncestor(node) ?: node
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        } catch (_: Throwable) {
            false
        }
    }

    fun inputText(text: String, submit: Boolean): Boolean {
        val root = activeRoot() ?: return false
        val focused = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } catch (_: Throwable) {
            null
        }
        val target = when {
            focused?.isEditable == true -> focused
            focused != null -> findEditableAncestor(focused) ?: findFirstEditable(root)
            else -> findFirstEditable(root)
        } ?: return false

        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val arguments = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    text
                )
            }
            val set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (set && submit) {
                target.performAction(
                    AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                )
            }
            set
        } catch (_: Throwable) {
            false
        }
    }

    fun scrollSelector(selector: Selector, down: Boolean): Boolean {
        val node = findNode(selector) ?: return false
        val target = if (node.isScrollable) node else findScrollableAncestor(node)
        val action = if (down) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }
        if (target != null) {
            try {
                if (target.performAction(action)) return true
            } catch (_: Throwable) {
                // Fall back to a swipe.
            }
        }
        val bounds = Rect()
        return try {
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return false
            val centerX = bounds.centerX()
            val startY = if (down) bounds.bottom - 24 else bounds.top + 24
            val endY = if (down) bounds.top + 24 else bounds.bottom - 24
            swipe(centerX, startY, centerX, endY, 320)
        } catch (_: Throwable) {
            false
        }
    }

    private fun activeRoot(): AccessibilityNodeInfo? = try {
        getRootInActiveWindow(ROOT_FLAGS)
    } catch (_: Throwable) {
        null
    }

    private fun search(
        node: AccessibilityNodeInfo?,
        selector: Selector,
        depth: Int,
        remaining: IntArray
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (matches(node, selector)) {
            if (remaining[0] == 0) return node
            remaining[0]--
        }
        for (i in 0 until node.childCount) {
            search(node.getChild(i), selector, depth + 1, remaining)?.let { return it }
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, selector: Selector): Boolean {
        val hasAny = !selector.viewId.isNullOrBlank() || !selector.text.isNullOrBlank() ||
            !selector.contains.isNullOrBlank() || !selector.desc.isNullOrBlank() ||
            !selector.descContains.isNullOrBlank() || !selector.className.isNullOrBlank()
        if (!hasAny) return false
        if (!selector.viewId.isNullOrBlank() &&
            node.viewIdResourceName != selector.viewId
        ) return false
        val fold: (String) -> String =
            if (selector.ignoreCase) { value -> value.lowercase() } else { value -> value }
        val text = fold(normalize(node.text?.toString()))
        if (!selector.text.isNullOrBlank() &&
            text != fold(normalize(selector.text))
        ) return false
        if (!selector.contains.isNullOrBlank() &&
            !text.contains(fold(normalize(selector.contains)))
        ) return false
        val description = fold(normalize(node.contentDescription?.toString()))
        if (!selector.desc.isNullOrBlank() &&
            description != fold(normalize(selector.desc))
        ) return false
        if (!selector.descContains.isNullOrBlank() &&
            !description.contains(fold(normalize(selector.descContains)))
        ) return false
        if (!selector.className.isNullOrBlank() &&
            node.className?.toString() != selector.className
        ) return false
        return true
    }

    /** Collapse line breaks and repeated spaces so multi-line labels still match. */
    private fun normalize(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        val out = StringBuilder(value.length)
        var pendingSpace = false
        for (ch in value) {
            if (ch.isWhitespace()) {
                pendingSpace = true
                continue
            }
            if (pendingSpace && out.isNotEmpty()) out.append(' ')
            pendingSpace = false
            out.append(ch)
        }
        return out.toString()
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(10) {
            val value = current ?: return null
            if (value.isClickable) return value
            current = try {
                value.parent
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun findEditableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val value = current ?: return null
            val supportsSetText = value.actionList.any {
                it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id
            }
            if (value.isEditable || supportsSetText) return value
            current = try {
                value.parent
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun findFirstEditable(
        node: AccessibilityNodeInfo?,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i), depth + 1)?.let { return it }
        }
        return null
    }

    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
            val value = current ?: return null
            if (value.isScrollable) return value
            current = try {
                value.parent
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }

    private fun findBySelector(
        node: AccessibilityNodeInfo?,
        element: Element,
        depth: Int = 0
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        val idMatch = !element.viewId.isNullOrBlank() &&
            node.viewIdResourceName == element.viewId
        val textMatch = !element.text.isNullOrBlank() &&
            node.text?.toString() == element.text
        val descMatch = !element.desc.isNullOrBlank() &&
            node.contentDescription?.toString() == element.desc
        if (idMatch || textMatch || descMatch) return node
        for (i in 0 until node.childCount) {
            findBySelector(node.getChild(i), element, depth + 1)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------------
    // Screenshot plumbing
    // ---------------------------------------------------------------------

    private suspend fun waitForIdle(maxMs: Long = 1200, quietMs: Long = 150) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < maxMs) {
            val quiet = SystemClock.elapsedRealtime() - lastContentChange.get()
            if (quiet >= quietMs) return
            delay(50)
        }
    }

    private suspend fun takeScreenshotBitmap(): Bitmap? {
        val wait = 360 - (SystemClock.elapsedRealtime() - lastScreenshotAt)
        if (wait > 0) delay(wait)
        lastScreenshotAt = SystemClock.elapsedRealtime()
        return try {
            requestScreenshot()
        } catch (e: ScreenshotException) {
            if (e.code == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) {
                delay(420)
                lastScreenshotAt = SystemClock.elapsedRealtime()
                requestScreenshot()
            } else {
                throw e
            }
        }
    }

    private suspend fun requestScreenshot(): Bitmap? = suspendCancellableCoroutine { cont ->
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            ContextCompat.getMainExecutor(this),
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        val copy = hardware?.copy(Bitmap.Config.ARGB_8888, false)
                        if (cont.isActive) cont.resume(copy)
                    } catch (t: Throwable) {
                        if (cont.isActive) cont.resumeWithException(t)
                    } finally {
                        buffer.close()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    if (cont.isActive) cont.resumeWithException(ScreenshotException(errorCode))
                }
            }
        )
    }

    private fun errorSnapshot(started: Long, error: String) = Snapshot(
        id = UUID.randomUUID().toString(),
        capturedAt = System.currentTimeMillis(),
        width = 0,
        height = 0,
        rotation = currentRotation(),
        packageName = currentPackage,
        activityName = currentActivity,
        elements = emptyList(),
        bitmap = null,
        imagePath = null,
        imageBytes = 0,
        nodeCount = 0,
        maxDepth = 0,
        error = error,
        elapsedMs = SystemClock.elapsedRealtime() - started
    )

    private fun screenshotError(t: Throwable): String =
        if (t is ScreenshotException) errorName(t.code)
        else (t.message ?: t.javaClass.simpleName)

    private fun errorName(code: Int): String = when (code) {
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "INTERNAL_ERROR"
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "NO_ACCESSIBILITY_ACCESS"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "INTERVAL_TIME_SHORT"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "INVALID_DISPLAY"
        ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "INVALID_WINDOW"
        ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
        else -> "UNKNOWN($code)"
    }

    private fun downscale(source: Bitmap, maxEdge: Int): Bitmap {
        val longest = max(source.width, source.height)
        if (longest <= maxEdge) return source
        val scale = maxEdge.toFloat() / longest
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun saveWebp(bitmap: Bitmap): Pair<String?, Int> {
        return try {
            val dir = File(cacheDir, "snapshots").apply { mkdirs() }
            val file = File(dir, "snap_${System.currentTimeMillis()}.webp")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 75, out)
            }
            file.absolutePath to file.length().toInt()
        } catch (_: Throwable) {
            null to 0
        }
    }

    private fun currentRotation(): Int {
        return try {
            getSystemService(DisplayManager::class.java)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
                ?.rotation ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    // ---------------------------------------------------------------------
    // Tree walking
    // ---------------------------------------------------------------------

    private data class RawElement(
        val bounds: Rect,
        val text: String?,
        val desc: String?,
        val viewId: String?,
        val className: String,
        val actions: List<String>,
        val clickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean
    )

    private class TreeStats {
        var nodeCount = 0
        var maxDepth = 0
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        out: MutableList<RawElement>,
        stats: TreeStats,
        depth: Int
    ) {
        if (node == null || depth > 60 || out.size >= 120) return
        stats.nodeCount++
        if (depth > stats.maxDepth) stats.maxDepth = depth

        val bounds = Rect()
        try {
            node.getBoundsInScreen(bounds)
            val className = node.className?.toString().orEmpty()
            val skip = className.contains("NavigationBar") || className.contains("StatusBar")
            val minPx = (MIN_DP * resources.displayMetrics.density).toInt()
            val interesting = node.isClickable || node.isEditable ||
                node.isCheckable || node.isLongClickable
            val addSelf = interesting && !hasInteractiveDescendant(node)

            if (!skip && node.isVisibleToUser && !bounds.isEmpty && addSelf &&
                bounds.width() >= minPx && bounds.height() >= minPx
            ) {
                val ownText = node.text?.toString()?.takeIf { it.isNotBlank() }
                val ownDesc = node.contentDescription?.toString()?.takeIf { it.isNotBlank() }
                val descendant = if (ownText == null && ownDesc == null) {
                    descendantLabelInfo(node)
                } else {
                    null
                }
                val label = ownText ?: descendant?.first
                val displayBounds = descendant?.second
                    ?.takeIf { !it.isEmpty && it.width() >= minPx && it.height() >= minPx }
                    ?: Rect(bounds)
                out += RawElement(
                    bounds = displayBounds,
                    text = label,
                    desc = ownDesc,
                    viewId = node.viewIdResourceName,
                    className = className,
                    actions = node.actionList.mapNotNull { actionName(it.id) },
                    clickable = node.isClickable,
                    editable = node.isEditable,
                    scrollable = node.isScrollable
                )
            }
        } catch (_: Throwable) {
            // A stale node is normal while the screen updates.
        }

        for (i in 0 until node.childCount) {
            try {
                collect(node.getChild(i), out, stats, depth + 1)
            } catch (_: Throwable) {
                // Skip a subtree that disappeared mid-walk.
            }
        }
    }

    private fun actionName(id: Int): String? = when (id) {
        AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id -> "click"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.id -> "longClick"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT.id -> "setText"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id -> "scrollForward"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD.id -> "scrollBackward"
        AccessibilityNodeInfo.AccessibilityAction.ACTION_FOCUS.id -> "focus"
        else -> null
    }

    private fun descendantLabelInfo(
        node: AccessibilityNodeInfo,
        depth: Int = 0
    ): Pair<String, Rect>? {
        if (depth > 8) return null
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            val text = child.text?.toString()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) {
                val bounds = Rect()
                try {
                    child.getBoundsInScreen(bounds)
                } catch (_: Throwable) {
                    // Ignore stale child bounds and keep looking.
                }
                if (!bounds.isEmpty) return text to bounds
            }
            descendantLabelInfo(child, depth + 1)?.let { return it }
        }
        return null
    }

    private fun hasInteractiveDescendant(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 20) return false
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            if (child.isClickable || child.isEditable ||
                child.isCheckable || child.isLongClickable
            ) {
                return true
            }
            if (hasInteractiveDescendant(child, depth + 1)) return true
        }
        return false
    }

    private fun number(width: Int, height: Int, raw: List<RawElement>): List<Element> {
        if (raw.isEmpty() || width <= 0 || height <= 0) return emptyList()

        val deduped = mutableListOf<RawElement>()
        val byPreference = raw.sortedWith(
            compareByDescending<RawElement> {
                !it.text.isNullOrBlank() || !it.desc.isNullOrBlank()
            }.thenByDescending {
                it.bounds.width().toLong() * it.bounds.height()
            }
        )
        for (element in byPreference) {
            if (deduped.none { iou(it.bounds, element.bounds) > 0.95f }) {
                deduped += element
            }
        }

        val rowPx = (ROW_DP * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        val ordered = deduped
            .sortedWith(compareBy({ it.bounds.top / rowPx }, { it.bounds.left }))
            .take(MAX_ELEMENTS)

        return ordered.mapIndexed { index, item ->
            Element(
                index = index + 1,
                id = elementId(item),
                boundsPx = Rect(item.bounds),
                boundsNorm = RectF(
                    item.bounds.left.toFloat() / width,
                    item.bounds.top.toFloat() / height,
                    item.bounds.right.toFloat() / width,
                    item.bounds.bottom.toFloat() / height
                ),
                text = item.text,
                desc = item.desc,
                viewId = item.viewId,
                className = item.className,
                actions = item.actions,
                clickable = item.clickable,
                editable = item.editable,
                scrollable = item.scrollable
            )
        }
    }

    private fun elementId(element: RawElement): String {
        val key = listOfNotNull(
            element.viewId,
            element.text,
            element.desc,
            element.className
        ).joinToString("|") + "|${element.bounds.centerX() / 8}|${element.bounds.centerY() / 8}"
        return "e_${key.hashCode().toUInt().toString(16)}"
    }

    private fun iou(a: Rect, b: Rect): Float {
        val left = max(a.left, b.left)
        val top = max(a.top, b.top)
        val right = min(a.right, b.right)
        val bottom = min(a.bottom, b.bottom)
        if (right <= left || bottom <= top) return 0f
        val intersection = (right - left).toLong() * (bottom - top)
        val union = a.width().toLong() * a.height() +
            b.width().toLong() * b.height() - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union
    }

    private class ScreenshotException(val code: Int) : Exception("screenshot error $code")
}
