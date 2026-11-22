# Step-Based Remote Control — Design (v2 scope)

**Project:** `RC-app`
**Supersedes:** the live-streaming assumptions in `docs/remote-control-report.md`
**Date:** 2026-11-22

---

## 0. What changed

**Old scope:** continuous screen streaming + real-time touch injection (mini AnyDesk).

**New scope:** *step-based control.*

1. Admin sees a list of installed apps.
2. Admin triggers a **predefined macro** (or a single action).
3. Device executes it, takes a **screenshot**, and sends it back.
4. Admin verifies the screenshot, then sends the next step.
5. In manual mode, the screenshot is annotated with **numbered hotspots** — admin replies with a number, device taps that element.

This is a **remote UI automation console**, not a screen-sharing app. It is simpler, cheaper, more auditable, and works on bad networks. It is also a better fit for macros, because macros and numbered hotspots use the same underlying primitive: *the accessibility tree*.

### Why this is the right call

| Dimension | Live streaming (v1 plan) | Step-based (v2) |
|---|---|---|
| Bandwidth | 2–6 Mbps continuous | ~80–300 KB per step |
| Latency | 50–300 ms | 0.5–2 s per step (fine for macros) |
| Battery / CPU | High (encoder always on) | Low (encode only on demand) |
| Android consent | MediaProjection prompt **per session** | Accessibility enabled **once** |
| Works on poor mobile networks | No | Yes |
| Determinism / audit | Weak | Strong (every step has a screenshot) |
| Macro support | Hard (coordinates + timing) | Native (selectors + checkpoints) |
| Games / video / drag-heavy apps | Yes | No |
| Implementation effort | High (WebRTC, codecs, TURN) | Medium |

**Trade-off accepted:** no real-time interaction, no games/video, no free-form drawing. For "control my apps and verify each step", that is acceptable.

---

## 1. Two ways to deliver it

The admin panel and protocol are identical. Only the **device-side executor** differs.

### Variant A — On-device agent (recommended, works anywhere)

A Kotlin app with an `AccessibilityService` that:

- lists launchable packages,
- launches apps,
- captures screenshots (`takeScreenshot`, API 30+),
- walks the accessibility tree and returns clickable elements,
- performs taps / text / scroll / global actions.

**Pros:** works on any device, including remote/field phones; no ADB; no cable.
**Cons:** user must enable Accessibility once; OEM battery managers; some sensitive apps hide their UI (see §11).

### Variant B — ADB lab controller (optional, great for a device bench)

A server-side agent that talks to devices over ADB:

```
pm list packages -3            # installed apps
am start -n pkg/activity       # launch
exec-out screencap -p          # screenshot
shell uiautomator dump         # full UI hierarchy XML with bounds
input tap x y                  # tap
input text "..."               # text
input keyevent KEYCODE_BACK    # keys
```

**Pros:** zero app install, no accessibility toggle, full package management (uninstall, clear data, grant permissions), very robust hierarchy, screenshots at full speed, input injection as shell.
**Cons:** needs USB or wireless ADB; not viable for devices in the field.

> **Recommendation:** build the protocol and admin UI once, then implement Variant A as the product path. Add Variant B only if you have a lab/bench — it is a 2–3 day spike and makes an excellent test harness for the admin panel before the Android app is ready.

---

## 2. System architecture

```
┌──────────────────────────┐        WSS (outbound)        ┌─────────────────────┐
│ Android agent            │◄────────────────────────────►│ Backend             │
│                          │                              │                     │
│ PackageManager ──────────┼─ app inventory ─────────────►│ devices, apps       │
│ AccessibilityService     │                              │ snapshots, actions  │
│  ├ getRootInActiveWindow │◄─ tap / text / scroll ───────│ macros, runs        │
│  ├ takeScreenshot        │── snapshot (img + hotspots) ►│ audit log           │
│  └ dispatchGesture       │                              │                     │
│ Macro engine             │◄─ runMacro ──────────────────│                     │
│ Foreground service       │── step results ─────────────►│                     │
└──────────────────────────┘                              └──────────┬──────────┘
                                                                     │ WSS
                                                          ┌──────────▼──────────┐
                                                          │ Web admin panel     │
                                                          │  app list           │
                                                          │  macro buttons      │
                                                          │  numbered snapshot  │
                                                          │  step log + audit   │
                                                          └─────────────────────┘
```

No WebRTC, no TURN, no media server. A single WebSocket relay plus a database is enough. If devices are on a private network, put the backend on Tailscale/WireGuard and skip public exposure entirely.

---

## 3. Feature 1 — App inventory

### Listing

```kotlin
// User-launchable apps only (what the admin actually wants)
val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
val apps = packageManager.queryIntentActivities(intent, 0)
    .map { it.activityInfo.applicationInfo }
    .distinctBy { it.packageName }
```

- Add `<queries><intent><action android:name="android.intent.action.MAIN"/><category android:name="android.intent.category.LAUNCHER"/></intent></queries>` to the manifest. This makes launcher apps visible on Android 11+ **without** `QUERY_ALL_PACKAGES`.
- For *all* packages (including non-launchable), add `QUERY_ALL_PACKAGES` — acceptable for an internal app, not for Play.
- Per app: `label`, `packageName`, `versionName`, `versionCode`, `firstInstallTime`, `lastUpdateTime`, `isSystem`, and a cached icon (PNG, keyed by package + version).

### Launching

```kotlin
val launch = packageManager.getLaunchIntentForPackage(pkg)
    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
launch?.let { startActivity(it) }
```

Background activity launch is restricted since Android 10, **but accessibility services are an exemption**. Still: Xiaomi/Oppo/Vivo may block it unless "display pop-up windows while running in background" is allowed. Mitigations: keep a foreground service, request the OEM permission during onboarding, or provision as Device Owner.

### What you can and cannot do

| Action | Normal app | Device Owner | ADB |
|---|---|---|---|
| List apps | ✅ | ✅ | ✅ |
| Launch app | ✅ (a11y) | ✅ | ✅ |
| Force-stop other app | ❌ | ✅ | ✅ |
| Uninstall / clear data | ❌ | ✅ | ✅ |
| Grant runtime permission | ❌ | ✅ | ✅ |
| Read "running apps" | ❌ (restricted since 5.1) | ⚠️ | ✅ |

For v1, show **launch + control**. For "manage" operations, either navigate the Settings UI via a macro, or add Device Owner later.

---

## 4. Feature 2 — The snapshot: screenshot + numbered hotspots

This is the core primitive. A **snapshot** = one screenshot + one list of interactive elements with normalized bounds.

### 4.1 Capture

```kotlin
// AccessibilityService, API 30+
takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
  object : TakeScreenshotCallback {
    override fun onSuccess(s: ScreenshotResult) {
      val bmp = Bitmap.wrapHardwareBuffer(s.hardwareBuffer, s.colorSpace)
      s.hardwareBuffer.close()
      // downscale + compress on a background thread
    }
    override fun onFailure(code: Int) { /* see table below */ }
  })
```

Requires `android:canTakeScreenshot="true"` in the service config.

| Error code | Meaning | Handling |
|---|---|---|
| `INTERVAL_TIME_SHORT` (3) | Called within 333 ms of the last shot | Coalesce/queue requests; retry after 350 ms |
| `SECURE_WINDOW` (6) | `FLAG_SECURE` window | Return "protected screen", fall back to tree-only |
| `NO_ACCESSIBILITY_ACCESS` (2) | Service not enabled/connected | Re-prompt onboarding |
| `INVALID_DISPLAY` (4) | Bad display id | Use `Display.DEFAULT_DISPLAY` |
| `INTERNAL_ERROR` (1) | Transient | Retry once |

**Rate limit:** the platform throttles to **one screenshot per 333 ms** (~3 fps). That is plenty for step mode. Do not build a 30 fps viewer on this API; if you ever need live view, use MediaProjection (accepting the consent dialog).

**Encoding:** downscale to max 1280 px long edge, WebP/JPEG q75 → typically 80–150 KB. Send as binary WebSocket frame + JSON metadata, or base64 inside JSON at low volume.

### 4.2 Building the hotspot map

Walk the active window's node tree and keep *interactive* nodes:

```kotlin
data class Hotspot(
  val id: String,          // stable within snapshot: hash(selector)
  val bounds: RectF,       // normalized 0..1
  val text: String?,
  val desc: String?,
  val viewId: String?,     // requires flagReportViewIds
  val cls: String,
  val actions: List<String>,
  val editable: Boolean,
  val scrollable: Boolean
)

fun collect(node: AccessibilityNodeInfo?, out: MutableList<Hotspot>) {
  if (node == null) return
  val r = Rect().also { node.getBoundsInScreen(it) }
  val interactive = node.isClickable || node.isEditable || node.isScrollable ||
      node.isCheckable || node.isLongClickable ||
      node.actionList.any { it.id in INTERESTING_ACTIONS }
  if (node.isVisibleToUser && !r.isEmpty && interactive &&
      r.width() >= dp(16) && r.height() >= dp(16)) {
    out += toHotspot(node, r)
  }
  for (i in 0 until node.childCount) collect(node.getChild(i), out)
}
```

Service config that makes this work well:

```xml
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:description="@string/a11y_description"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
    android:accessibilityFlags="flagReportViewIds|flagIncludeNotImportantViews|flagRetrieveInteractiveWindows"
    android:canRetrieveWindowContent="true"
    android:canPerformGestures="true"
    android:canTakeScreenshot="true"
    android:notificationTimeout="100" />
```

- `flagReportViewIds` → gives `viewIdResourceName`, the most stable selector for macros.
- `flagIncludeNotImportantViews` → many more nodes (important for custom UIs).
- `flagRetrieveInteractiveWindows` → `getWindows()` for multi-window cases.

### 4.3 Cleaning and numbering

Raw trees are messy: nested clickables, full-screen containers, duplicate labels. Pipeline:

1. **Filter** — visible, non-empty, ≥ 16 dp, within screen bounds, not the status/nav bar, not our own overlay.
2. **Dedupe** — drop a hotspot if its bounds overlap an already-kept hotspot by IoU > 0.9. Prefer the one with a label or a specific action.
3. **Rank** — editable > clickable-with-text > clickable > checkable > scrollable.
4. **Sort** — top-to-bottom, then left-to-right (row bucketing by `y / 48dp` so numbers read naturally).
5. **Cap** — at ~40 numbers per screen. If more, keep the top-ranked and offer "show all".
6. **Assign** — 1..N.

**Render the overlay in the browser, not on the device.** Send the raw screenshot plus JSON. Why:

- crisp at any zoom / responsive layout,
- hover tooltips with the element label,
- no re-encoding of the image,
- the same raw image can be stored for audit,
- you can toggle "all elements" vs "top N" without a new capture.

Optionally, the server can *burn* numbers into a copy for export/email ("here is the annotated screen").

### 4.4 Click resolution — numbers are a UX, IDs are the contract

The admin clicks **#7**, but the wire message should carry both:

```jsonc
{ "t": "tap", "snapshot": "snap_8f21", "element": "e_3a91", "index": 7 }
```

On the device:

1. Look up the snapshot's element by `id`.
2. If the node reference is still alive and `refresh()` succeeds → `performAction(ACTION_CLICK)`.
3. Else re-resolve by **selector** (viewId → text → desc → class → relative position).
4. Else fall back to a `dispatchGesture` tap at the stored bounds' center.
5. **Verify**: take a fresh snapshot and return it. If nothing changed, report `"mayHaveFailed": true`.

`ACTION_CLICK` is better than a coordinate tap: it respects scroll position, works on partially visible rows, and is not affected by DPI. Use coordinate taps only as a fallback (and for non-accessible apps).

### 4.5 Fallbacks for non-accessible apps

Some apps expose little or no tree:

- **Flutter** — needs semantics enabled; often sparse.
- **Games / SurfaceView / canvas** — effectively empty.
- **Sensitive screens** — Android 14+ can hide nodes from non-accessibility-tool services (see §11).

Fallback ladder:

| Level | Method | Granularity |
|---|---|---|
| 1 | Accessibility hotspots | Exact elements |
| 2 | **Grid overlay** — divide the screenshot into ~6×12 numbered cells | Coarse taps |
| 3 | **OCR** — ML Kit Text Recognition on-device, number the text blocks | Text regions |
| 4 | Manual coordinates / MediaProjection live view | Pixel |

The grid is cheap to implement and immediately makes *every* screen controllable, even games. Ship it in v1.

---

## 5. Feature 3 — Macros

A macro is a versioned, named sequence of steps for one app. This is the primary control surface; the numbered snapshot is the manual/assisted surface and the verification mechanism.

### 5.1 Model

```jsonc
{
  "id": "m_whatsapp_send",
  "name": "WhatsApp — open a chat and send a message",
  "appPackage": "com.whatsapp",
  "appVersionCode": 2400000,          // invalidate when the app updates
  "requiresConfirmation": false,
  "steps": [
    { "op": "launch",  "package": "com.whatsapp" },
    { "op": "waitFor", "selector": { "text": "Chats" }, "timeoutMs": 5000 },
    { "op": "tap",     "selector": { "text": "Chats" } },
    { "op": "waitFor", "selector": { "viewId": "com.whatsapp:id/search" } },
    { "op": "tap",     "selector": { "viewId": "com.whatsapp:id/search" } },
    { "op": "setText", "selector": { "viewId": "com.whatsapp:id/search" }, "text": "Mom" },
    { "op": "tap",     "selector": { "text": "Mom" } },
    { "op": "screenshot", "label": "chat-open" },
    { "op": "setText", "selector": { "viewId": "com.whatsapp:id/entry" }, "text": "On my way" },
    { "op": "tap",     "selector": { "desc": "Send" } },
    { "op": "assert",  "selector": { "text": "On my way" }, "timeoutMs": 3000 },
    { "op": "screenshot", "label": "sent" }
  ]
}
```

### 5.2 Step types

| Op | Purpose |
|---|---|
| `launch` | Start an app |
| `waitFor` | Poll until a selector appears (or timeout) |
| `tap` | `ACTION_CLICK` on a selector |
| `longPress` | `ACTION_LONG_CLICK` or 700 ms gesture |
| `setText` | `ACTION_SET_TEXT` (optionally clear first) |
| `scroll` | `ACTION_SCROLL_FORWARD/BACKWARD` or swipe |
| `swipe` | Raw gesture (for sliders, carousels) |
| `key` | `back` / `home` / `recents` / `enter` |
| `sleep` | Fixed delay (discouraged; prefer `waitFor`) |
| `screenshot` | Explicit checkpoint |
| `assert` | Fail the run if a condition is not met |
| `confirm` | Pause and wait for admin approval (human gate) |

Add `if` / `repeat` later; keep v1 linear and predictable.

### 5.3 Selectors (priority order)

1. `viewId` (resource id) + `class`
2. `text` exact / `textContains` / `regex`
3. `contentDescription`
4. `hint`
5. relative position (index path, "the button below X")
6. normalized bounds — last resort, breaks on layout change
7. OCR text — only for canvas apps

Never rely on coordinates as the primary selector. They break on font size, DPI, rotation, and app updates.

### 5.4 Engine behavior

- Execute on-device (no round trip per step) with a per-step timeout.
- Emit a progress event per step: `{runId, index, op, status, durationMs, snapshotId, error}`.
- **Checkpoint after every mutating step**: capture a screenshot, attach it to the run log.
- On failure: stop, capture a screenshot, return the failed selector + the visible tree so the admin can repair the macro.
- **Retries**: `waitFor` polls (250 ms) instead of sleeping; a failed tap can retry once after a 500 ms settle.
- **Dry run**: resolve every selector and screenshot, but do not tap. Essential for authoring.
- **Confirmation gates**: mark destructive steps (`requiresConfirmation`) so a human must approve before send/delete/pay.
- **Idempotency**: macros should be safe to re-run; prefer "open chat" over "tap first row".

### 5.5 Recording macros

Capture accessibility events while a human uses the device:

- `TYPE_VIEW_CLICKED` → `tap` with the clicked node's selector,
- `TYPE_VIEW_TEXT_CHANGED` → `setText` (debounced),
- `TYPE_WINDOW_STATE_CHANGED` → `waitFor` + app/activity context.

This produces a rough draft that a human edits. Swipes and scrolls are not directly observable without a touch overlay; record them as `scroll` if `TYPE_VIEW_SCROLLED` fires, otherwise add manually.

### 5.6 Versioning and safety

- Pin `appVersionCode`; if the installed app differs, mark the macro **stale** and require a dry run.
- Store macro revisions; never overwrite silently.
- Allowlist packages per device; block macros that touch system settings unless explicitly allowed.
- Log every run with before/after screenshots and the acting admin.
- Rate-limit runs; require a second admin for destructive macros if you want four-eyes control.

---

## 6. The two consoles

### 6.1 Macro console (primary)

```
Device: Pixel 7 — online
┌─────────────────────────────────────────────┐
│ Apps                                        │
│  [icon] WhatsApp        v2.24   [Macros ▾]  │
│  [icon] Settings        v14     [Macros ▾]  │
│  [icon] Chrome          v131    [Macros ▾]  │
└─────────────────────────────────────────────┘

Run: "WhatsApp — send message"
  ✓ launch           120 ms
  ✓ waitFor Chats    340 ms
  ✓ tap Chats         90 ms   [screenshot]
  ✓ setText "Mom"    110 ms
  ...
  ✓ assert sent       80 ms   [screenshot]
Result: SUCCESS (2.1 s)  [view screenshots]
```

### 6.2 Assisted console (manual / repair)

```
┌──────────────────────────────────────────────┐
│  <screenshot with numbered boxes>            │
│                                              │
│  ┌───┐ 1 Chats            ┌───┐ 2 Search    │
│  └───┘                    └───┘              │
│  ┌───┐ 3 Camera           ┌───┐ 4 New chat  │
│  └───┘                    └───┘              │
│                                              │
│  [1..N] [grid] [OCR]     Home Back Recents   │
│  Type text: ____________________  [Send]     │
│  [Take fresh screenshot]  [Auto-refresh 1s]  │
└──────────────────────────────────────────────┘
```

- Click a number → tap → new screenshot arrives automatically.
- Click an editable hotspot → opens a text box → `setText`.
- Click a scrollable hotspot → scroll up/down.
- "Auto-refresh" polls at 1 s for *watching* (effectively 1 fps), not for interaction.
- Every action is appended to the session log with before/after snapshots.

This console doubles as the **macro debugger**: when a macro step fails, drop into assisted mode at that screen, fix the selector, save the repaired step.

---

## 7. Protocol

One WebSocket, JSON control + binary image frames. Suggested messages:

```jsonc
// device → server
{ "t": "hello", "deviceId": "…", "name": "Pixel 7", "android": 36, "agent": "1.0.0" }
{ "t": "apps", "apps": [ { "pkg": "com.whatsapp", "label": "WhatsApp",
    "version": "2.24", "code": 2400000, "system": false, "icon": "<base64|url>" } ] }

{ "t": "snapshot", "id": "snap_8f21", "ts": 1730000000000,
  "w": 1080, "h": 2400, "rotation": 0, "pkg": "com.whatsapp", "activity": ".Main",
  "image": "<binary frame follows | url>",
  "elements": [
    { "i": 1, "id": "e_3a91", "b": [0.05,0.10,0.45,0.14], "text": "Chats",
      "desc": "", "vid": "com.whatsapp:id/chats", "cls": "android.widget.TextView",
      "acts": ["click"], "editable": false, "scrollable": false }
  ] }

{ "t": "actionResult", "id": "act_9", "ok": true, "snapshot": "snap_8f22" }
{ "t": "macroStep", "runId": "run_1", "i": 3, "op": "tap", "status": "ok",
  "ms": 90, "snapshot": "snap_8f30" }
{ "t": "macroDone", "runId": "run_1", "status": "success", "ms": 2100 }

// server → device
{ "t": "listApps" }
{ "t": "launch", "pkg": "com.whatsapp" }
{ "t": "snapshot", "reason": "manual" }
{ "t": "tap", "snapshot": "snap_8f21", "element": "e_3a91", "index": 1 }
{ "t": "tapXY", "snapshot": "snap_8f21", "x": 0.5, "y": 0.5 }   // grid fallback
{ "t": "setText", "snapshot": "snap_8f21", "element": "e_4b02", "text": "Mom" }
{ "t": "scroll", "snapshot": "snap_8f21", "element": "e_5c13", "dir": "down" }
{ "t": "globalAction", "action": "back" }
{ "t": "runMacro", "macroId": "m_whatsapp_send", "dryRun": false }
{ "t": "approveStep", "runId": "run_1", "i": 7, "approved": true }
```

Rules:

- Every mutating command returns an `actionResult` **and** a fresh snapshot.
- Commands carry the `snapshot` id they were derived from; the device rejects stale ones (or re-resolves by selector).
- Binary images are separate frames with a small header, not base64, once volume grows.

---

## 8. Backend and data model

Minimal schema:

```
devices(id, name, model, android, agent_version, last_seen, status, token_hash)
apps(device_id, package, label, version_name, version_code, icon_url, system, updated_at)
snapshots(id, device_id, ts, w, h, rotation, package, activity, image_url, elements_json)
actions(id, device_id, snapshot_id, admin_id, type, payload_json, ok, after_snapshot_id, ts)
macros(id, name, app_package, app_version_code, current_rev, created_by, tags, requires_confirmation)
macro_revs(macro_id, rev, steps_json, created_by, created_at)
macro_runs(id, macro_id, device_id, admin_id, status, started_at, finished_at, error)
macro_run_steps(run_id, i, op, status, ms, snapshot_id, error)
admins(id, email, totp_secret, role)
audit(id, admin_id, device_id, action, detail_json, ts)
```

Backend: Node/TypeScript + Postgres, Redis for presence, S3-compatible storage for snapshots (or the DB for MVP). No media server needed.

Admin: Next.js + React. The snapshot viewer is an `<img>` plus an absolutely-positioned SVG overlay using normalized coordinates — simple and fast.

---

## 9. Security and privacy (still matters, even internal)

- **Transport:** WSS with TLS. If devices are on a private network, run the backend over Tailscale/WireGuard and don't expose it publicly at all.
- **Device auth:** per-device token (hashed at rest), rotate on re-enroll; bind to a device keypair if you want stronger identity.
- **Admin auth:** password + TOTP; role-based access (viewer / operator / admin).
- **Approval:** even internal, show a persistent on-device notification while a session is active, and a "Stop" action. This protects against accidental control of the wrong device.
- **Screenshots are sensitive:** they may contain OTPs, messages, account numbers. Encrypt at rest, set retention (e.g. 30 days), restrict access, and redact on export where possible.
- **Audit everything:** who ran which macro on which device, with before/after screenshots.
- **Package allowlist:** prevent macros from touching banking/settings apps unless explicitly enabled.
- **Rate limiting and replay protection** on the relay; single-use session tokens.
- **Sideload caveat (Android 13+):** apps installed outside an app store cannot enable Accessibility until the user opens *App info → ⋮ → Allow restricted settings*. Put this in onboarding. Distributing via MDM or a private store avoids the friction.

---

## 10. Limitations and mitigations

| Limitation | Impact | Mitigation |
|---|---|---|
| **`accessibilityDataSensitive` (Android 14+)** hides nodes from services without `isAccessibilityTool=true`; Android 16+ also blocks `dispatchGesture` on them | Banking/2FA/sensitive screens return empty trees and reject injected taps | Treat as unsupported; use MediaProjection/manual mode; only on fully-owned devices consider declaring the flag (see note) |
| Screenshot throttle 333 ms | Max ~3 fps | Step mode is fine; coalesce requests; MediaProjection only if live view is ever needed |
| `FLAG_SECURE` windows | Screenshot fails with `SECURE_WINDOW` | Show "protected screen"; allow tree-only actions if available |
| Empty tree (games, Flutter, canvas) | No numbered hotspots | Grid overlay → OCR → manual coordinates |
| Node staleness between snapshot and tap | Wrong element | Re-resolve by selector, verify with a new snapshot |
| Background activity launch blocked (OEM) | App won't launch | Accessibility exemption + foreground service + OEM allowlist; Device Owner as fallback |
| Macro breaks on app update | Failed runs | Pin `appVersionCode`, assertions, dry run, selector repair in assisted console |
| Device asleep / screen off | Black screenshot | Keep-screen-on (overlay `FLAG_KEEP_SCREEN_ON`), wake lock, Device Owner policy |
| OEM battery killers | Agent dies | Battery-optimization exemption, foreground service, MDM |
| Split screen / multi-window | Ambiguous coordinates | Capture the active window; document single-window support |
| Text input in some fields | `ACTION_SET_TEXT` rejected | Tap to focus, then `ACTION_SET_TEXT`; clipboard paste fallback |
| No "running apps" API | Can't show live state | Use accessibility foreground events; `UsageStatsManager` with special access if needed |

> **On `isAccessibilityTool=true`:** setting it makes the OS stop hiding sensitive views and stops blocking gesture injection. It is a deliberate opt-out of a security protection intended for assistive technology. Only consider it on devices you fully own, with explicit informed consent, and never in a distributed product. Default position: those screens are out of scope.

---

## 11. Revised roadmap

| Phase | Deliverable | Effort (1–2 devs) |
|---|---|---|
| **0. Spike** | Agent lists apps, launches one, captures a screenshot + dumps the hotspot JSON to a debug screen | 3–5 days |
| **1. Step console** | WS relay + admin app list + numbered snapshot + tap/text/scroll/back + auto-refresh | 2–3 weeks |
| **2. Macros** | Schema, on-device engine, server storage, run log with checkpoints, dry run | 2–3 weeks |
| **3. Robustness** | Grid + OCR fallbacks, selector repair, assertions, retries, multi-device | 2–3 weeks |
| **4. Ops** | Auth, audit, retention, Tailscale deployment, onboarding, packaging | 1–2 weeks |

**Total: ~8–12 weeks for a solid internal v1** — roughly half the streaming plan, with far less platform risk.

**Phase 0 exit criteria:** on two different OEM devices, you can list apps, launch one, and get a screenshot where at least 80% of visible buttons are numbered correctly. If that works, the rest is UI and plumbing.

---

## 12. Repository layout

```
RC-app/
├── android/                  # Kotlin agent (Variant A)
│   ├── app/                  # onboarding, pairing, status
│   ├── accessibility/        # service, tree walker, screenshots, actions
│   ├── inventory/            # package listing, icons
│   ├── macros/               # engine, selectors, recorder
│   └── transport/            # WS client
├── server/                   # Node/TS: devices, snapshots, macros, audit
├── web/                      # Next.js admin panel
│   ├── components/snapshot/  # image + numbered SVG overlay
│   ├── components/macros/    # editor, runner, run log
│   └── components/apps/      # inventory
├── shared/                   # JSON Schema → TS + Kotlin models
├── tools/adb-agent/          # optional Variant B (lab)
├── infra/                    # docker-compose, Tailscale notes
└── docs/
```

---

## 13. Decisions to make

1. **Variant A or B first?** (Recommend A; B only if you have a bench.)
2. **App scope:** launcher apps only, or all packages? (Recommend launcher apps.)
3. **Minimum Android:** `takeScreenshot` needs API 30. Accept Android 11+, or add a MediaProjection fallback? (Recommend 11+.)
4. **Grid fallback in v1?** (Recommend yes — cheap and unlocks games/canvas.)
5. **Macro authoring:** record-and-edit, or hand-written JSON first? (Recommend record + edit.)
6. **Human confirmation gates** for destructive macros? (Recommend yes.)
7. **Deployment:** public relay vs Tailscale-only? (Recommend Tailscale-only for internal.)
8. **Snapshot retention** and who can view them? (Recommend 30 days, operators only.)
9. **Device Owner provisioning** now or later? (Recommend later; only if you need uninstall/clear-data.)
10. **Do you need any live view at all?** (Recommend no for v1; 1 fps auto-refresh covers monitoring.)

---

## 14. Summary

Your instinct is right, and it simplifies the project substantially:

- **App list + macros** is the product.
- **Screenshot + numbered hotspots** is the universal manual fallback *and* the macro debugger.
- Both are built on one primitive: *the accessibility tree*, captured as a snapshot.
- No MediaProjection, no WebRTC, no TURN, no continuous encoding.
- The hard parts are selector robustness, OEM quirks, and the handful of screens Android deliberately hides — all of which are manageable and have documented fallbacks.

If Phase 0 works on two OEM devices, you can have a genuinely useful internal tool in about two months.
