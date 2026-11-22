package com.acme.rc.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import com.acme.rc.agent.AgentService
import com.acme.rc.model.Element
import com.acme.rc.model.ProbeResult
import com.acme.rc.model.Selector
import com.acme.rc.model.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        @Volatile
        var instance: RcAccessibilityService? = null
            private set

        private const val MAX_ELEMENTS = 40
        private const val MIN_DP = 16
        private const val ROW_DP = 48
        private const val MAX_SCREENSHOT_EDGE = 1280
    }

    private val lastContentChange = AtomicLong(0)
    private var lastScreenshotAt = 0L

    @Volatile
    var currentPackage: String? = null
        private set

    @Volatile
    var currentActivity: String? = null
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
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

        val scaled = downscale(bitmap, MAX_SCREENSHOT_EDGE)
        val root = try {
            getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID)
        } catch (_: Throwable) {
            null
        }

        val stats = TreeStats()
        val raw = mutableListOf<RawElement>()
        collect(root, raw, stats, 0)
        val elements = number(scaled.width, scaled.height, raw)
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
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
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
                val label = ownText ?: if (ownDesc == null) descendantLabel(node) else null
                out += RawElement(
                    bounds = Rect(bounds),
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

    private fun descendantLabel(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (depth > 8) return null
        for (i in 0 until node.childCount) {
            val child = try {
                node.getChild(i)
            } catch (_: Throwable) {
                null
            } ?: continue
            val text = child.text?.toString()?.takeIf { it.isNotBlank() }
                ?: child.contentDescription?.toString()?.takeIf { it.isNotBlank() }
            if (text != null) return text
            descendantLabel(child, depth + 1)?.let { return it }
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
