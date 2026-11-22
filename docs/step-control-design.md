# Step-Based Remote Control — Design (v3)

**Project:** `RC-app`
**Status:** decisions locked — implementation design
**Date:** 2026-11-22
**Supersedes:** `docs/remote-control-report.md` (streaming exploration) and v2 of this doc

---

## 0. Locked decisions

| Decision | Choice |
|---|---|
| Target apps | **Own** banking/payment apps that permit screenshots and control; messaging/social via UI automation; device settings |
| Third-party banking | **Out of scope**; official APIs later through an `api` step |
| Device location | Remote / field phones (no ADB) |
| Autonomy | **Fully automatic**; human fallback only for OTPs and macro repair |
| App management | Launch + control only; no Device Owner for install/uninstall |
| OTP source | SMS or notification, with **human input fallback** |
| Device state | Unlocked + charging in v1; add keep-awake/unlock support |
| Admin surface | Web panel only |
| Live view | Screenshot per step; no video |
| Scale | 1–5 devices |
| Android | **13+ (API 33+)** |
| Macro authoring | Record user interactions + edit; hardware keys where possible |

**Product shape:** choose device → choose app → run macro → read report. The numbered-screenshot console is the **debug/repair** tool, not the primary UI.

---

## 1. Why this scope works

- **Own apps** means you can guarantee the accessibility tree is present and stable, and you can add stable test IDs. You cannot do that with third-party banking apps.
- **Android 13+** removes most legacy branches: `takeScreenshot` (API 30), prefetching tree API (33), notification listener, modern FGS rules.
- **1–5 devices** means one small server, SQLite or Postgres, no Redis, no queue cluster, no multi-tenant design.
- **Fully automatic** means the macro engine and the run report are the product. The manual console exists to debug failed runs and repair selectors.
- **No video** means no MediaProjection consent, no codecs, no TURN.

### What we deliberately do not support

- Third-party banking/payment apps (they hide their UI and block screenshots).
- Biometric prompts and secure PIN pads.
- Games/canvas apps without a tree (grid fallback only).
- Real-time interaction and free-form drawing.
- Install/uninstall/clear-data (needs Device Owner or ADB; not in v1).

---

## 2. Architecture

```
┌────────────────────────────┐         WSS (outbound)         ┌──────────────────────┐
│ Android agent              │◄──────────────────────────────►│ Backend (1 process)  │
│                            │                                │                      │
│ LauncherApps ──────────────┼── app inventory ──────────────►│ devices, apps        │
│ AccessibilityService       │                                │ snapshots, runs      │
│  ├ tree + selectors        │◄─ runMacro / tap / prompt ─────│ macros, secrets      │
│  ├ takeScreenshot          │── snapshot + step events ─────►│ queue, audit         │
│  ├ dispatchGesture         │                                │ OTP events           │
│  └ onKeyEvent (recorder)   │                                │                      │
│ OTP listeners              │                                └──────────┬───────────┘
│ Macro engine + preflight   │                                           │ WSS
│ Foreground service         │                                ┌──────────▼───────────┐
└────────────────────────────┘                                │ Web panel            │
                                                              │  run dashboard       │
                                                              │  macro editor        │
                                                              │  report + snapshots  │
                                                              │  assist console      │
                                                              └──────────────────────┘
```

Single Node/TypeScript process + SQLite (or Postgres) + local disk for snapshots. No Redis, no media server, no SFU. Deploy on one small VPS or an internal machine, reachable over Tailscale/WireGuard.

---

## 3. Device agent

### 3.1 Reachability

Devices are on the field and may be behind CGNAT, so the agent always opens an **outbound** WebSocket.

For 1–5 devices kept on chargers, a **persistent WSS + foreground service** is the simplest reliable choice:

- Use foreground service type `specialUse` on Android 14+ (`FOREGROUND_SERVICE_SPECIAL_USE`, subtype `remote_device_control`). Do **not** use `dataSync`: Android 15 caps it at ~6 h per 24 h.
- On Android 13, no FGS type is required.
- Keep `POST_NOTIFICATIONS` granted so the persistent notification is visible.
- Reconnect with exponential backoff (1 s → 30 s), heartbeat every 20 s, resume from the last event sequence number.
- Optional later: FCM high-priority wake-up + on-demand connection, if battery becomes a problem.

### 3.2 Device state and keeping it awake/unlocked

This is the #1 operational failure mode for unattended runs. Handle it explicitly.

**v1 setup (recommended, no Device Owner):**

1. Set screen lock to **None/Swipe**.
2. Enable **Stay awake while charging**:
   - Developer Options → "Stay awake", or one-time ADB:
     `adb shell settings put global stay_on_while_plugged_in 7`
3. Keep devices on chargers.
4. The agent checks and reports state; the dashboard shows a warning banner if a device is locked or asleep.

**App-level keep-awake fallback:** a 1×1 overlay window with `FLAG_KEEP_SCREEN_ON` and `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCHABLE`, requiring `SYSTEM_ALERT_WINDOW`. Use only if the device-level setting is not possible.

**Unlocking:**

| Situation | What works |
|---|---|
| No secure lock (swipe/none) | An activity with `requestDismissKeyguard()`; `FLAG_SHOW_WHEN_LOCKED` |
| Secure lock (PIN/pattern/biometric) | **Cannot** be automated by a normal app |
| Device Owner | `DevicePolicyManager.setKeyguardDisabled(admin, true)` removes the keyguard |

**Preflight must check:** `KeyguardManager.isKeyguardLocked`, `isDeviceSecure`, screen on, battery/charging, accessibility enabled, notification access (if OTP), target app installed and version. If a check fails, return `preflight_failed` with reasons and do not start the run.

**Note:** `GLOBAL_ACTION_LOCK_SCREEN` can lock the device, but there is no global "unlock" action.

### 3.3 App inventory

Use `LauncherApps`, not `PackageManager`, so work profiles work correctly:

```kotlin
val launcher = getSystemService(LauncherApps::class.java)
val apps = launcher.getActivityList(null, Process.myUserHandle())
    .map { it.applicationInfo }
    .distinctBy { it.packageName }
```

- Manifest `<queries>` for `MAIN`/`LAUNCHER` is enough on Android 11+; `QUERY_ALL_PACKAGES` is unnecessary and should be avoided.
- Per app: label, package, versionName, versionCode, enabled, system flag, icon URL.
- **Icons are served over HTTP with cache headers**, not base64 in every `apps` message.
- Refresh on `PACKAGE_ADDED`, `PACKAGE_REMOVED`, `PACKAGE_CHANGED` broadcasts.

### 3.4 Snapshot: screenshot + hotspots

A snapshot = one screenshot + one list of interactive elements with normalized bounds.

**Capture (corrected):**

```kotlin
takeScreenshot(Display.DEFAULT_DISPLAY, executor, object : TakeScreenshotCallback {
  override fun onSuccess(result: ScreenshotResult) {
    val buffer = result.hardwareBuffer
    try {
      val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return
      val copy = hw.copy(Bitmap.Config.ARGB_8888, false)   // detach before close
      // downscale to <=1280px long edge, compress WebP/JPEG q75 off the main thread
    } finally {
      buffer.close()                                       // only after the copy
    }
  }
  override fun onFailure(code: Int) { /* map error */ }
})
```

Requires `android:canTakeScreenshot="true"`.

| Error | Meaning | Handling |
|---|---|---|
| `INTERVAL_TIME_SHORT` (3) | <333 ms since last shot | Coalesce; retry after 350 ms |
| `SECURE_WINDOW` (6) | `FLAG_SECURE` window | Report "protected screen"; do not retry |
| `NO_ACCESSIBILITY_ACCESS` (2) | Service disconnected | Re-onboard |
| `INVALID_DISPLAY` (4) | Bad display | Use `Display.DEFAULT_DISPLAY` |
| `INVALID_WINDOW` (5) | Bad window id | Use display capture |
| `INTERNAL_ERROR` (1) | Transient | Retry once |

**Capture must be settled.** The tree and screenshot are not atomic. Wait for ~150 ms with no `TYPE_WINDOW_CONTENT_CHANGED`, then capture the tree, then the screenshot, then sanity-check a few bounds. If the screen is animating, retry.

**Hotspot extraction:**

```kotlin
fun collect(node: AccessibilityNodeInfo?, out: MutableList<Hotspot>, depth: Int = 0) {
  if (node == null || depth > 40 || out.size >= 200) return
  val r = Rect().also { node.getBoundsInScreen(it) }
  val interactive = node.isClickable || node.isEditable || node.isScrollable ||
      node.isCheckable || node.isLongClickable ||
      node.actionList.any { it.id in INTERESTING_ACTIONS }
  if (node.isVisibleToUser && !r.isEmpty && interactive &&
      r.width() >= dp(16) && r.height() >= dp(16)) {
    out += toHotspot(node, r)          // copy fields only; never retain the node
  }
  for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
}
```

- Service config: `canRetrieveWindowContent`, `canPerformGestures`, `canTakeScreenshot`, `flagReportViewIds`, `flagIncludeNotImportantViews`, `flagRetrieveInteractiveWindows`.
- Use `getRootInActiveWindow(FLAG_PREFETCH_DESCENDANTS_HYBRID)` on API 33+.
- Run the walk off the main thread; bound depth and node count.
- **Never store `AccessibilityNodeInfo` references in a snapshot.** Store selectors and bounds; re-resolve on every action.

**Numbering:**

1. Filter visible, non-empty, ≥16 dp, on-screen, not status/nav bars, not our own overlay.
2. Dedupe by IoU >0.9, preferring clickable+labeled nodes.
3. Rank: editable > clickable+text > clickable > checkable > scrollable.
4. Sort top-to-bottom, left-to-right (row buckets of 48 dp).
5. Cap at ~40; offer "show all."
6. **Match by selector hash across snapshots and reuse numbers.** Only new elements get new numbers. This is important for the assist console.

**Overlay rendering is in the browser** (raw image + JSON). Optional server-side burn-in for exports.

### 3.5 Actions

| Intent | Mechanism |
|---|---|
| Tap | `ACTION_CLICK` → selector re-resolve → `dispatchGesture` at bounds center |
| Long press | `ACTION_LONG_CLICK` or 700 ms gesture |
| Text | `ACTION_FOCUS` + `ACTION_SET_TEXT`; tap-to-focus first |
| Scroll | `ACTION_SCROLL_FORWARD/BACKWARD`; swipe fallback |
| Back/Home/Recents | `performGlobalAction` |
| Enter/IME action | `ACTION_IME_ENTER` on focused node |
| Launch | `getLaunchIntentForPackage`; deep link via `Intent.URI_INTENT_SCHEME` if provided |

**Direct API first.** Use the most direct mechanism available: intent/API → accessibility action → UI automation → coordinate tap. For settings, prefer `Settings.Panel`, `DevicePolicyManager`, or explicit intents over tapping the Settings UI. For own apps, prefer deep links/test hooks over UI automation where a macro step allows it.

**Verification:** after every mutating action, take a fresh snapshot and return it. If the expected post-condition is not met, mark the step `unverified` or `failed` with the before/after images and the visible tree.

### 3.6 OTP pipeline

OTPs arrive from SMS or notifications; human input is the fallback.

**Notification listener (default for third-party apps):**

```kotlin
class OtpNotificationListener : NotificationListenerService() {
  override fun onNotificationPosted(sbn: StatusBarNotification) {
    if (sbn.packageName !in allowlist) return
    val text = listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
      .mapNotNull { sbn.notification.extras.getCharSequence(it)?.toString() }
      .joinToString(" ")
    Regex("\\b(\\d{4,8})\\b").find(text)?.let { OtpBuffer.push(it.groupValues[1], OTP_NOTIFICATION) }
  }
}
```

- Requires notification access (user grant). Allowlist by package and sender.
- Keep an in-memory ring buffer (max 5, TTL 5 minutes) so a step that starts slightly late still finds the code.

**SMS:**

- **Own apps:** use the **SMS Retriever API** — no `READ_SMS` permission. The SMS must be ≤140 bytes, contain the 11-character app-signature hash, and arrive within 5 minutes. This is the cleanest path because you control the sender.
- **Fallback:** `READ_SMS` with a sender allowlist. Internal-only; avoid if possible.

**Human fallback:**

- A `prompt` step, kind `otp`. The run pauses; the web panel shows "Enter OTP for `<device>`"; the operator types it; it is relayed over WSS, used once, and discarded.
- Never write the value to the database, logs, or `steps_json`.

**Rules:**

- Do **not** screenshot secret-entry steps by default; if a screenshot is required, mask the field.
- Redact `setText` values when the target is a password/OTP field.
- Send only `otpReceived` metadata (source, timestamp) to the server, never the value.
- Treat OTPs as credentials: memory only, single use, short TTL.

---

## 4. Macro engine

### 4.1 Model

```jsonc
{
  "id": "m_bank_transfer",
  "name": "Bank — transfer to saved payee",
  "appPackage": "com.acme.bank",
  "appVersionCode": 4200,
  "params": {
    "payee":   { "type": "string", "required": true },
    "amount":  { "type": "number", "required": true },
    "note":    { "type": "string", "required": false }
  },
  "steps": [
    { "op": "launch",  "package": "com.acme.bank" },
    { "op": "waitFor", "selector": { "text": "Pay & Transfer" }, "timeoutMs": 8000 },
    { "op": "tap",     "selector": { "text": "Pay & Transfer" },
      "expect": { "selector": { "text": "Transfer" }, "exists": true } },

    { "op": "tap",     "selector": { "text": "Transfer" } },
    { "op": "tapIfExists", "selector": { "text": "Skip" } },          // ads/onboarding
    { "op": "tap",     "selector": { "text": "{{payee}}" }, "timeoutMs": 8000 },
    { "op": "setText", "selector": { "viewId": "com.acme.bank:id/amount" }, "text": "{{amount}}" },
    { "op": "tap",     "selector": { "text": "Continue" } },

    { "op": "waitForOtp", "source": "any", "timeoutMs": 60000 },
    { "op": "setText",    "selector": { "viewId": "com.acme.bank:id/otp" }, "text": "{{otp}}",
      "secret": true },
    { "op": "tap",        "selector": { "text": "Confirm" }, "requiresConfirmation": true },

    { "op": "waitFor",  "selector": { "text": "Transfer successful" }, "timeoutMs": 30000 },
    { "op": "screenshot", "label": "success" },
    { "op": "assert",   "selector": { "text": "Transfer successful" }, "exists": true }
  ]
}
```

### 4.2 Steps

| Op | Purpose |
|---|---|
| `launch` | Start an app or open a deep link/URI |
| `waitFor` | Poll until a selector appears |
| `waitForIdle` | Wait until no content-change events for N ms |
| `tap` | Click a selector |
| `tapIfExists` | Optional click (ads, permission dialogs, "Skip") |
| `longPress` | Long click |
| `setText` | Focus + set text; `secret: true` redacts |
| `scroll` / `scrollUntil` | Scroll container / scroll until selector visible |
| `swipe` | Raw gesture for sliders/carousels |
| `key` | back / home / recents / enter |
| `waitForOtp` | Wait for an OTP from SMS/notification |
| `prompt` | Pause and ask the operator for input |
| `screenshot` | Explicit checkpoint |
| `assert` | Post-condition |
| `api` | Call an allowlisted official API (future third-party) |
| `confirm` | Human approval gate (optional) |

Every mutating step should have an `expect`. If it has none, the engine marks it `unverified` rather than assuming success.

### 4.3 Selectors

Priority: `viewId` → `text`/`textContains`/`regex` → `contentDescription` → `hint` → relative position → bounds (last resort) → OCR (not in v1).

**For your own apps:** add stable test tags. For Compose, `Modifier.testTag("login.submit")` plus `Modifier.semantics { testTagsAsResourceId = true }` exposes them as `viewIdResourceName`. For Views, set `android:id` or `View.setTag(R.id.test_tag, ...)`. This single change makes macros dramatically more reliable.

### 4.4 Execution

- Run on-device; the server sends `runId` (idempotency key) and the resolved params.
- Preflight before step 1.
- Emit a step event per step; buffer events on-device and sync on reconnect.
- Stop on failure; capture screenshot + tree excerpt; return the failed selector and the visible tree.
- **Max runtime** per run (default 5 min) and a **kill switch** in the panel and on the notification.
- One run per device at a time (lease). A second admin must explicitly take over.
- `dryRun`: resolve every selector and screenshot, but do not tap.

### 4.5 Recording

Record a human performing the task, then edit the draft.

| Signal | Recorded as |
|---|---|
| `TYPE_VIEW_CLICKED` | `tap` with the clicked node's selector |
| `TYPE_VIEW_TEXT_CHANGED` | `setText` (debounced, final value) |
| `TYPE_VIEW_SCROLLED` | `scroll` |
| `TYPE_WINDOW_STATE_CHANGED` | `waitFor` + package/activity context |
| `onKeyEvent` (hardware keys) | `key` / `setText`, when key filtering is enabled |

**Hardware keys:** declare `android:canRequestFilterKeyEvents="true"` and `flagRequestFilterKeyEvents`; enable filtering only while recording. The service receives events before the app, may not see them in secure fields, and should return `false` to let them through. This is a sensitive capability — show a clear indicator while recording.

**Limits:** raw swipe traces are not observable through accessibility. Record scrolls where `TYPE_VIEW_SCROLLED` fires; otherwise add swipes manually. A transparent overlay could capture touches but would consume them, so it is not used.

**Recorder output:** a timeline → draft macro with `waitFor` and assertions inserted automatically → review/edit UI → dry run → save.

---

## 5. Backend

For 1–5 devices, keep it boring:

- **One Node/TypeScript process.** Fastify or NestJS. TypeScript shared with the web panel.
- **SQLite** (better-sqlite3) is sufficient; Postgres if you prefer. No Redis.
- **Presence** in memory + `devices.last_seen`.
- **Queue:** a `runs` table with `status = queued` and a worker loop. No BullMQ/Redis.
- **Snapshots:** local disk (`/data/snapshots/<device>/<run>/<step>.webp`), with an `expires_at` and a daily cleanup job.
- **Secrets:** encrypted at rest with a master key from env/KMS; `secretRef` in macros, never values.
- **Deploy:** Docker Compose on one VPS, or a small internal machine over Tailscale.

### Data model (minimum)

```
devices(id, name, model, android, agent_version, protocol_version, last_seen,
        status, token_hash, state_json)
apps(device_id, package, label, version_name, version_code, icon_path,
     system, enabled, updated_at)                       -- PK(device_id, package)

snapshots(id, device_id, ts, w, h, rotation, package, activity,
          image_path, elements_json, bytes, expires_at)
actions(id, device_id, snapshot_id, admin_id, type, payload_json,
        ok, after_snapshot_id, ts)

macros(id, name, app_package, app_version_code, current_rev, params_json,
       requires_confirmation, tags, created_by)
macro_revs(macro_id, rev, steps_json, created_by, created_at)

macro_runs(id, macro_id, device_id, admin_id, status, params_json,
           queued_at, started_at, finished_at, error)
macro_run_steps(run_id, i, op, status, ms, snapshot_id, error, expect_ok)

secrets(id, name, ciphertext, created_by, rotated_at)
otp_events(id, device_id, source, received_at, used_at)  -- never the value
admins(id, email, password_hash, totp_secret, role)
audit(id, admin_id, device_id, action, detail_json, ts)
```

Run statuses: `queued`, `running`, `success`, `failed`, `unverified`, `cancelled`, `preflight_failed`, `device_offline`.

### Run report (the actual product)

- Per step: op, selector, status, duration, checkpoint screenshot, error, tree excerpt.
- Summary: success/failed/unverified, total time, device state at start/end.
- Failure: the failed selector, a screenshot of the screen, and the visible tree, so the macro can be repaired.
- Optional: notify on failure via webhook (Slack/Telegram/email).

Store screenshots at checkpoints and failures, not every step, unless a step is explicitly `screenshot`.

---

## 6. Web panel

**Primary screens**

1. **Devices** — online, screen on/locked, battery, accessibility/notification status, last seen.
2. **Apps** — inventory per device, with macros per app.
3. **Run** — pick macro, fill parameters, run; live step list; cancel.
4. **Runs** — history, status, filters; open a run report.
5. **Macro editor** — visual step list + JSON; record; dry run; parameter editor.
6. **Secrets** — named references, rotation, grants; values never displayed after creation.

**Assist console (secondary)**

- Raw snapshot with numbered hotspots.
- Click a number → tap → fresh screenshot.
- Type text on editable elements; scroll; global actions.
- Grid fallback (6×12 cells) for screens without a tree.
- "Pause auto-refresh on hover"; stable numbering.
- Used to debug a failed run and repair the selector.

---

## 7. Protocol

One WebSocket. JSON control; binary frames for images (or an HTTP URL). Every command has a `cmdId`; every device event has a monotonically increasing `seq` for resume.

```jsonc
// device → server
{ "t": "hello", "deviceId": "…", "protocolVersion": 1, "agent": "1.0.0",
  "android": 34, "capabilities": ["screenshot","otp.notification","otp.sms","keyfilter"] }
{ "t": "state", "seq": 41, "online": true, "screenOn": true, "locked": false,
  "secureLock": false, "battery": 87, "charging": true,
  "a11y": true, "notifAccess": true, "foregroundApp": "com.acme.bank" }
{ "t": "apps", "apps": [ { "pkg": "com.acme.bank", "label": "Acme Bank",
  "version": "4.2", "code": 4200, "system": false, "icon": "/icons/…" } ] }
{ "t": "snapshot", "id": "s_8f21", "seq": 42, "ts": 1730000000000,
  "w": 1080, "h": 2400, "rotation": 0, "pkg": "com.acme.bank", "activity": ".Main",
  "image": "/snapshots/s_8f21.webp",
  "elements": [ { "i": 1, "id": "e_3a91", "b": [0.05,0.10,0.45,0.14],
    "text": "Transfer", "vid": "com.acme.bank:id/transfer",
    "cls": "android.widget.Button", "acts": ["click"] } ] }
{ "t": "otp", "runId": "r_1", "source": "notification", "ts": 1730000000000 }  // no value
{ "t": "step", "runId": "r_1", "i": 3, "op": "tap", "status": "ok",
  "ms": 90, "snapshot": "s_9" }
{ "t": "runFinished", "runId": "r_1", "status": "success", "ms": 2100 }
{ "t": "ack", "cmdId": "c_123", "ok": true }

// server → device
{ "t": "runMacro", "cmdId": "c_123", "runId": "r_1", "macroId": "m_1",
  "params": { "payee": "Mom", "amount": 10 }, "dryRun": false }
{ "t": "cancelRun", "cmdId": "c_124", "runId": "r_1" }
{ "t": "tap", "cmdId": "c_125", "snapshot": "s_8f21", "element": "e_3a91", "index": 1 }
{ "t": "setText", "cmdId": "c_126", "snapshot": "s_8f21", "element": "e_4b02", "text": "10" }
{ "t": "snapshot", "cmdId": "c_127", "reason": "manual" }
{ "t": "promptResult", "cmdId": "c_128", "runId": "r_1", "i": 7, "value": "123456" }
{ "t": "ping" } / { "t": "pong" }
```

Rules:

- `runId` is generated by the server; the device dedupes and rejects a duplicate.
- Commands are idempotent by `cmdId`; ack every command.
- Reconnect: send `hello` with the last processed `seq`; the device replays buffered events.
- Images are HTTP URLs with cache headers once volume grows; small snapshots may be binary frames.

---

## 8. Security and privacy

Even internal, this system can move money and read OTPs.

- **Network:** Tailscale/WireGuard; WSS with TLS. No public exposure unless necessary.
- **Device enrollment:** install → server URL + one-time enrollment code (or QR) → device appears as `pending` → admin approves → token stored in `EncryptedSharedPreferences`. Tokens rotate on re-enroll.
- **Admin auth:** password + TOTP; roles `viewer` / `operator` / `admin`.
- **Concurrency:** per-device lease; explicit takeover.
- **Secrets:** AES-GCM at rest with a master key; `secretRef` only; audit every read; rotate; never log values.
- **OTP:** memory only, TTL 5 min, single use; no DB, no logs, no screenshots on secret steps.
- **Screenshots:** may contain PII/balances; encrypt at rest, restrict access, retention (e.g., 30 days), delete on request.
- **Audit:** who ran what on which device, params (redacted), result, screenshots.
- **Package allowlist:** only bank/messaging/settings packages can be controlled.
- **Kill switch:** panel + notification; stops the run and disconnects.
- **No hidden mode:** persistent notification while connected; "remote control active" indicator.
- **Sideload caveat (Android 13+):** enable Accessibility via *App info → ⋮ → Allow restricted settings*; document it in onboarding.

---

## 9. Limitations and failure modes

| Limitation | Impact | Mitigation |
|---|---|---|
| Own apps must not set `FLAG_SECURE` / `accessibilityDataSensitive` in the test build | Screenshots/tree empty | Make it a build-config requirement; test with the exact build |
| Third-party banking hides UI / blocks screenshots | Cannot control | Out of scope; official APIs later |
| `accessibilityDataSensitive` (Android 14+) | Empty tree, blocked `dispatchGesture` on 16+ | Use only builds without the flag |
| Screen locked / asleep | Screenshot fails, input stops | Stay-awake + no lock; Device Owner `setKeyguardDisabled`; preflight |
| Secure PIN pads / biometrics | Cannot automate | Human step or app test hook |
| Empty tree (games/canvas) | No hotspots | Grid fallback; not a v1 target |
| OTP latency / listener misses | Run times out | Ring buffer, `waitForOtp` timeout, human fallback |
| SMS Retriever constraints | Only ≤140-byte, hash-tagged SMS within 5 min | Own apps format the SMS; otherwise notification listener |
| Notification access revoked | OTP unavailable | Preflight check; human fallback |
| Macro breaks on app update | Failed run | Stable test tags, `appVersionCode` pin, assertions, dry run, repair in assist console |
| Recorder can't capture raw swipes | Draft missing gestures | Record scrolls; add swipes manually |
| Hardware key filtering may miss secure fields | Incomplete key recording | Record taps/text; edit manually |
| OEM battery killers | Agent dies | Battery-optimization exemption, `specialUse` FGS, MDM |
| Device offline mid-run | Lost report | On-device buffer + `seq` resume; idempotent `runId` |
| Multi-window | Ambiguous coords | Capture full display; document single-window |
| IME appears over the app | Hotspot mismatch | Wait for idle; treat IME as its own window |
| Messaging UI automation | ToS/ban risk | Prefer official APIs; keep as best-effort |
| Work profiles | Inventory/tree differences | `LauncherApps`; separate listener per profile |

---

## 10. Roadmap

| Phase | Deliverable | Effort (1–2 devs) |
|---|---|---|
| **0. Spike** | Inventory, launch, snapshot + hotspot dump, device-state banner on 2 devices | 3–5 days |
| **1. Console** | WSS relay + run dashboard shell + numbered snapshot + tap/text/scroll/back + grid | 2 weeks |
| **2. Engine** | Macro schema, runner, preflight, assertions, `tapIfExists`/`waitForIdle`, run report, offline buffer | 2–3 weeks |
| **3. OTP + record** | Notification/SMS OTP, human fallback, secrets vault, recorder + editor, parameters | 2 weeks |
| **4. Harden** | Tailscale, enrollment, retention, alerts, OEM matrix, CI, observability | 1–2 weeks |

**Total: ~7–9 weeks** for a solid v1 on 1–5 devices.

**Phase 0 exit criteria:** on two devices, list apps, launch one, capture a settled screenshot, and number ≥80% of visible controls correctly. If that works, the rest is engineering.

---

## 11. Repository layout

```
RC-app/
├── android/
│   ├── app/                  # onboarding, enrollment, status
│   ├── accessibility/        # service, tree walker, screenshots, actions
│   ├── inventory/            # LauncherApps, icons
│   ├── macros/               # engine, selectors, preflight
│   ├── recorder/             # event timeline, key filtering
│   ├── otp/                  # notification listener, SMS Retriever, buffer
│   └── transport/            # WSS client, reconnect, buffer
├── server/                   # Node/TS: devices, runs, macros, secrets, queue
├── web/                      # Next.js: dashboard, report, editor, assist
├── shared/                   # JSON Schema + generated TS/Kotlin models
├── infra/                    # docker-compose, Tailscale notes, retention job
└── docs/
```

---

## 12. Open questions

1. **Own apps:** Compose or Views? Can the app teams add stable `testTag`/`viewId` values? (This is the single biggest reliability lever.)
2. **Environment:** sandbox/test accounts and test OTPs available? (Strongly recommended over real money.)
3. **OTP:** what format do the SMS and notifications use? Can the apps expose a known test OTP or notification?
4. **Device control:** is Device Owner acceptable if needed for unlock/keep-awake, or do you prefer physically unlocked, charging devices?
5. **Recording scope:** taps/text/scroll only, or also hardware key events (requires key filtering)?
6. **Schedule:** on-demand only, or scheduled/recurring runs?
7. **Failure notifications:** email, Slack, Telegram, or just the dashboard?
8. **Retention:** how long should run screenshots be kept?

---

## 13. Summary

- **Own apps + Android 13+ + 1–5 devices** turns this into a tractable internal tool.
- **Fully automatic** makes the **macro engine + run report** the product; the numbered console is the debugger.
- **OTP** is the riskiest integration: prefer test hooks, then notification listener, then SMS Retriever, then human fallback.
- **Keep the devices unlocked and awake** before writing more code; treat lock/sleep as the top operational failure.
- **Stable test tags in your own apps** will do more for reliability than any clever selector heuristic.
