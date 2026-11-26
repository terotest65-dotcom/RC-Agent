package com.acme.rc.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
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
import com.acme.rc.device.DevicePin
import com.acme.rc.device.KeypadLayout
import com.acme.rc.device.PinKeypadStore
import com.acme.rc.device.PinUnlockResult
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

        private val ENTER_LABELS = setOf(
            "enter", "ok", "okay", "done", "confirm", "check", "submit"
        )
        private val PIN_SWITCH_LABELS = setOf(
            "pin", "use pin", "enter pin", "use pin instead", "use your pin"
        )
    }

    private val lastContentChange = AtomicLong(0)
    private var lastScreenshotAt = 0L

    private val keypadDigitId = Regex(
        "(?:key|digit|num)[_-]?([0-9])$",
        RegexOption.IGNORE_CASE
    )
    private val keypadEnterId = Regex(
        "(?:key|btn|button)[_-]?(?:enter|ok|done|confirm|check|submit)$",
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
        val info = serviceInfo ?: return
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            lastContentChange.set(SystemClock.elapsedRealtime())
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            event.packageName?.toString()?.let { currentPackage = it }
            event.className?.toString()?.let { currentActivity = it }
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
            getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
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
            getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
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
            getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
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
            getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
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
        val enter: Point?
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
     * handles letters and symbols without driving the keyboard.
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
        submit: Boolean = true
    ): Boolean = withContext(Dispatchers.Default) {
        if (secret.isBlank()) return@withContext false

        val scopedRoot = selector?.let { findNode(it) }
        if (selector != null && scopedRoot == null) return@withContext false

        val keypad = scanKeypad(scopedRoot)
        if (secret.all { it.isDigit() } && keypad.digits.size >= 10) {
            for (digit in secret) {
                val point = keypad.digits[digit] ?: return@withContext false
                if (!tapAndAwait(point.x, point.y)) return@withContext false
                delay(80)
            }
            if (submit) {
                val enter = keypad.enter ?: findEnterButton()
                enter?.let { tapAndAwait(it.x, it.y) }
            }
            return@withContext true
        }

        val field = findAppField() ?: return@withContext false
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
        if (!set) return@withContext false

        delay(150)
        if (submit) {
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
        }
        true
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

    private suspend fun tapAndAwait(x: Int, y: Int): Boolean {
        val completed = withTimeoutOrNull(1500) {
            suspendCancellableCoroutine { cont ->
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 60)
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

    private fun scanKeypad(root: AccessibilityNodeInfo? = null): KeypadScan {
        val digits = linkedMapOf<Char, Point>()
        val enter = mutableListOf<Point>()
        val roots = if (root != null) listOf(root) else orderedWindowRoots()

        for (candidate in roots) {
            collectKeypad(candidate, digits, enter)
            if (digits.size >= 10) break
        }
        // A password bouncer's QWERTY row (or a numeric IME) can expose ten
        // "digit" nodes. Only accept a real 3-column keypad so a wrong layout
        // does not burn a failed unlock attempt.
        val validKeypad = looksLikeKeypad(digits)
        return KeypadScan(
            digits = if (validKeypad) digits else emptyMap(),
            enter = if (validKeypad) enter.firstOrNull() else null
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
        depth: Int = 0
    ) {
        if (node == null || depth > 60) return
        val label = (node.text?.toString() ?: node.contentDescription?.toString())
            ?.trim()
            ?.lowercase()
        val digit = digitFromLabel(label) ?: digitFromViewId(node.viewIdResourceName)

        if (digit != null) {
            if (!digits.containsKey(digit)) {
                clickableBounds(node)?.let {
                    digits[digit] = Point(it.centerX(), it.centerY())
                }
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
            collectKeypad(child, digits, enter, depth + 1)
        }
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

    private fun findEnterButton(): Point? =
        findClickableLabel(ENTER_LABELS)

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
        val label = (node.text?.toString() ?: node.contentDescription?.toString())
            ?.trim()
            ?.lowercase()
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
        return search(root, selector, 0)
    }

    fun tapSelector(selector: Selector): Boolean {
        val node = findNode(selector) ?: return false
        val target = findClickableAncestor(node) ?: node
        if (target.isClickable) {
            try {
                if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            } catch (_: Throwable) {
                // Fall through to a coordinate tap.
            }
        }
        val bounds = Rect()
        return try {
            node.getBoundsInScreen(bounds)
            if (bounds.isEmpty) false else tapAt(bounds.centerX(), bounds.centerY())
        } catch (_: Throwable) {
            false
        }
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
        getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
    } catch (_: Throwable) {
        null
    }

    private fun search(
        node: AccessibilityNodeInfo?,
        selector: Selector,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (node == null || depth > 60) return null
        if (matches(node, selector)) return node
        for (i in 0 until node.childCount) {
            search(node.getChild(i), selector, depth + 1)?.let { return it }
        }
        return null
    }

    private fun matches(node: AccessibilityNodeInfo, selector: Selector): Boolean {
        val hasAny = !selector.viewId.isNullOrBlank() || !selector.text.isNullOrBlank() ||
            !selector.contains.isNullOrBlank() || !selector.desc.isNullOrBlank() ||
            !selector.className.isNullOrBlank()
        if (!hasAny) return false
        if (!selector.viewId.isNullOrBlank() &&
            node.viewIdResourceName != selector.viewId
        ) return false
        val text = node.text?.toString()
        if (!selector.text.isNullOrBlank() && text != selector.text) return false
        if (!selector.contains.isNullOrBlank() &&
            text?.contains(selector.contains) != true
        ) return false
        val description = node.contentDescription?.toString()
        if (!selector.desc.isNullOrBlank() && description != selector.desc) return false
        if (!selector.className.isNullOrBlank() &&
            node.className?.toString() != selector.className
        ) return false
        return true
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(6) {
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
