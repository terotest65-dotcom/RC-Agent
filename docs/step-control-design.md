# Step-Based Remote Control — Design (v3)

**Project:** `RC-app`
**Status:** decisions locked — implementation design
**Date:** 2026-11-22
**Supersedes:** `docs/remote-control-report.md` (streaming exploration) and v2 of this doc

---

## 0. Locked decisions

| Decision | Choice |
|---|---|
| Target apps (v1) | **WhatsApp, Google Pay, PhonePe, Paytm, Chrome, Settings** |
| App admission | Capability probe per app/version; add more only when `controllable` |
| Own-app test tags | Not available; selectors rely on `viewId`/text/description where exposed |
| Payments | Navigate only; **no real transactions**; stop before the final confirm |
| Third-party APIs | Later, through an `api` step |
| Device location | Remote / field phones (no ADB) |
| Autonomy | **Fully automatic**; human fallback only for OTPs and macro repair |
| Scheduling | **On-demand only**; no cron or queue |
| App management | Launch + control only |
| OTP source | Real OTPs on dedicated test accounts; SMS/notification capture + human fallback |
| Device state | Unlocked + charging; Device Owner **optional** (QR or ADB), with a no-DO mode |
| Admin surface | Web panel only |
| Live view | Screenshot per step; no video |
| Scale | 1–5 devices |
| Android | **13+ (API 33+)** |
| Recorder | Taps, text, scroll, and screen/app context; no hardware-key capture in v1 |

**Product shape:** choose device → choose app → run macro → read report. The numbered-screenshot console is the **debug/repair** tool, not the primary UI.

---

## 1. Why this scope works

- **A capability probe decides the app set.** We measure screenshot access, tree richness, and IDs per app/version; we do not assume.
- **Android 13+** removes most legacy branches: `takeScreenshot` (API 30), prefetching tree API (33), notification listener, modern FGS rules.
- **1–5 devices** means one small server, SQLite or Postgres, no Redis, no queue cluster, no scheduler.
- **Fully automatic, on demand** means the macro engine and the run report are the product. The manual console exists to debug failed runs and repair selectors.
- **No video** means no MediaProjection consent, no codecs, no TURN.

### What we deliberately do not support

- Apps that hide their UI (`accessibilityDataSensitive`) or block screenshots (`FLAG_SECURE`).
- Real monetary transactions in v1; test navigation only, and stop before the final confirm.
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
│  ├ takeScreenshot          │── snapshot + step events ─────►│ runs, audit          │
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

**v1 setup (no Device Owner needed):**

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
| Secure PIN with a stored credential | `AccessibilityService.dispatchGesture()` taps the keypad (OEM-dependent) |
| Secure password with a stored credential | `ACTION_SET_TEXT` on the bouncer's focused `EditText`, then `ACTION_IME_ENTER` |
| Secure pattern with a stored credential | `dispatchGesture()` traces the 3×3 dots from the pattern view bounds |
| Secure PIN/password/pattern without a stored credential | **Cannot** be automated by a normal app |
| Biometric | **Cannot** be automated by a normal app |
| Device Owner | `setKeyguardDisabled(admin, true)` only when no PIN/pattern/password is set |

**Credential entry is a consented accessibility capability, not a sandbox
bypass.** The device user must enable the accessibility service, and the PIN,
password, or pattern is provisioned from the panel or enrolled locally and
stored encrypted with an Android Keystore key. It is never returned to the
browser, written to macros/secrets, or logged. `MediaProjection` cannot be used
to watch the lock screen (per-session consent that cannot be approved while
locked, and Android 15+ stops capture on lock), so the agent uses the
accessibility tree instead.

The three paths need different mechanics:

- **PIN** — locate the digit keys by accessibility label and tap them with
  `dispatchGesture`. The numeric keypad has no usable `ACTION_SET_TEXT` target.
- **Password** — the bouncer exposes a focused `EditText`; `ACTION_SET_TEXT`
  fills the whole credential (letters, digits, symbols) in one call, then
  `ACTION_IME_ENTER` submits it. This is more reliable than driving the IME and
  was verified on API 36.
- **Pattern** — the bouncer exposes a single square `lockPatternView` with no
  per-dot nodes. Derive the 3×3 dot centers from the view bounds (cell centers)
  and trace the stored 1–9 sequence in one continuous gesture. OEM padding and
  view IDs vary; test on each model.

Android escalates failed unlock attempts; the agent makes **one** attempt per
unlock command and never brute-forces. Blind keypad tapping is opt-in and only
applies to numeric credentials.

**Enrollment.** The credential can be provisioned from the panel or entered
locally on the device. After the setup flow completes on a secure device with
no stored credential, the local credentials activity opens automatically. It is
also offered by a one-time notification after the first manual unlock, by the
app's status sheet, and by the admin panel's **Open enrollment** button. The
local activity stores the value with the same Keystore key and never sends it
through the relay. Clearing the credential re-arms the notification and the
automatic open.

The accessibility service classifies the visible bouncer as a PIN keypad, a
pattern view, or a password field and caches that type. The enrollment screen
shows the detected input in the centre; the other types remain as faint links at
the bottom. On a fresh setup the cache can be empty until the device is locked
and unlocked once; the page re-checks on resume, and the operator can choose a
type at the bottom at any time.

**App-level locks.** The `appUnlock` command targets the foreground app; the
`{ "op": "appUnlock", "package": "..." }` macro step runs the same flow. App
locks come in two shapes:

- A system app-lock window that offers fingerprint and a “Use
  password/PIN/pattern/screen lock” fallback (MIUI/POCO App lock, Samsung).
  The agent taps the fallback, then enters the device unlock credential.
- An in-app lock with its own PIN/password/pattern. The agent uses a per-app
  credential stored from the panel, keyed by package and encrypted with a
  separate Keystore key. If none is stored, it falls back to the device
  credential.

The credential screen is detected in the foreground window (pattern view,
3-column keypad, or editable field). The action makes one attempt and verifies
the credential UI disappeared, or that a system app-lock window handed control
to the target package. Biometric-only locks and canvas keypads without
accessible digits cannot be automated. The panel shows the foreground app and
whether a per-app credential exists.

**Preflight must check:** `KeyguardManager.isKeyguardLocked`, `isDeviceSecure`, screen on, battery/charging, accessibility enabled, notification access (if OTP), target app installed and version. If a check fails, return `preflight_failed` with reasons and do not start the run.

**Note:** `GLOBAL_ACTION_LOCK_SCREEN` backs the panel's **Lock** button; there is no global "unlock" action.

**Device Owner (optional — both provisioning paths supported):**

| Path | How | Notes |
|---|---|---|
| QR provisioning | Factory-reset device → scan the admin QR during setup | Standard managed-device flow |
| ADB one-time | `adb shell dpm set-device-owner com.acme.rc/.AdminReceiver` | Device must have no accounts; fine for a small fleet |

With Device Owner:

- `setKeyguardDisabled(admin, true)` — remove the lock screen;
- keep the device awake while charging (developer setting, or `setGlobalSetting` where permitted);
- grant runtime permissions if needed.

**No-Device-Owner mode (must work):** feature-detect with `DevicePolicyManager.isDeviceOwnerApp(packageName)`.

- If `false`: never call DO APIs; require the manual setup (no lock, stay awake) and show a dashboard warning.
- Use the 1×1 `FLAG_KEEP_SCREEN_ON` overlay fallback if the device-level setting is not possible.
- Preflight reports `deviceOwner: false`; runs still work on an unlocked, charging device.

Accessibility and notification access are never grantable by Device Owner; they remain user toggles.

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

### 3.3.1 Capability probe (how the app list is decided)

"We support apps that allow control" is not a specification until it is measured. For every candidate app on every device, record a capability profile.

Probe procedure:

1. Launch the app; wait for the screen to settle.
2. Take a screenshot; record success or `SECURE_WINDOW`.
3. Read the active window tree; count nodes, clickable nodes, nodes with a `viewId`, and max depth.
4. Record package, `versionCode`, activity, and elapsed time.
5. Do **not** inject input during the probe; a broken tree is enough to classify it.

Store:

```
app_capabilities(device_id, package, version_code, screenshot_ok, secure_window,
                 node_count, clickable_count, view_id_count, depth, verdict, probed_at)
```

Verdict:

| Verdict | Criteria | Use |
|---|---|---|
| `controllable` | Screenshot works, ≥5 clickable nodes, at least some labels/IDs | Full macro support |
| `partial` | Screenshot works, tree sparse or mostly unlabeled | Grid fallback; manual only |
| `blocked` | `SECURE_WINDOW` or empty tree | Excluded from v1 |

Show the verdict as a badge in the app list; re-probe when `versionCode` changes. This is what makes "common apps that allow control" concrete.

**V1 probe set** — these six, nothing else until they are classified:

| App | Package | Category | Hypothesis | Notes |
|---|---|---|---|---|
| WhatsApp | `com.whatsapp` | Messaging | `controllable` | Dedicated test number; no bulk messaging |
| Google Pay | `com.google.android.apps.nbu.paisa.user` | Payments | `partial`/`blocked` likely | Navigate only; stop before confirm |
| PhonePe | `com.phonepe.app` | Payments | `partial`/`blocked` likely | Navigate only; stop before confirm |
| Paytm | `net.one97.paytm` | Payments | `partial`/`blocked` likely | Navigate only; stop before confirm |
| Chrome | `com.android.chrome` | Utility | `controllable` | URL/search/navigation macros |
| Settings | `com.android.settings` | System | `controllable` | Prefer intents/APIs over UI taps |

Package names are for the probe config; verify them on the device. "Hypothesis" is not a decision — the probe decides.

### 3.3.2 Device and account profile

The admin controls a **device**, not an account. The device reports an optional, redacted account label (e.g., `test-01`, last 4 digits) so the operator knows which session is active. Macros never hardcode an account, phone number, or UPI ID; they operate on whatever is already logged in.

Keep one dedicated test account/number per app where possible. The agent does not need credentials — the device stays logged in, and the OTP flow only handles the challenge at login time.

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
- With no test tags, selectors fall back to `viewId` (when the app exposes one), `text`, `contentDescription`, and bounds. Prefer text/description over bounds; pin macros to `appVersionCode`.

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

- **SMS Retriever API** — only works for your own apps (the SMS must contain your app-signature hash, be ≤140 bytes, and arrive within 5 minutes). Not usable for third-party apps.
- **Fallback:** `READ_SMS` with a sender allowlist. Internal-only; avoid if possible.

**Human fallback:**

- A `prompt` step, kind `otp`. The run pauses; the web panel shows "Enter OTP for `<device>`"; the operator types it; it is relayed over WSS, used once, and discarded.
- Never write the value to the database, logs, or `steps_json`.

**Rules:**

- Do **not** screenshot secret-entry steps by default; if a screenshot is required, mask the field.
- Redact `setText` values when the target is a password/OTP field.
- Send only `otpReceived` metadata (source, timestamp) to the server, never the value.
- Treat OTPs as credentials: memory only, single use, short TTL.

**v1 policy:** real OTPs are acceptable on **dedicated test accounts**. Capture from notifications (default) or SMS; use the human fallback when capture fails. Never store the value; send only metadata to the server. Do not automate OTPs for accounts you do not own.

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
| `appUnlock` | Run the app-lock flow for a package; no-op when already unlocked |
| `waitFor` | Poll until a selector appears |
| `waitForIdle` | Wait until no content-change events for N ms |
| `tap` | Click a selector |
| `tapIfExists` | Optional click (ads, permission dialogs, "Skip") |
| `longPress` | Long click |
| `setText` | Focus + set text; `secret: true` redacts |
| `enterPin` | Enter a PIN/password into a labeled app keypad or text field; use `secretRef` |
| `scroll` / `scrollUntil` | Scroll container / scroll until selector visible |
| `swipe` | Raw gesture for sliders/carousels |
| `key` | back / home / recents / enter |
| `waitForOtp` | Wait for an OTP from SMS/notification |
| `prompt` | Pause and ask the operator for input |
| `screenshot` | Explicit checkpoint |
| `assert` | Post-condition |
| `api` | Call an allowlisted official API (future third-party) |

Add `"optional": true` to a step to turn its failure into `skipped` and continue. Use it for best-effort waits, onboarding dialogs, and app-specific result labels that may not exist in every version.

**App-level locks.** App PIN/password screens (banking apps, third-party app
lockers) are ordinary app windows, not the keyguard, so the system lock rules do
not apply. Use `enterPin` with a `secretRef` from the Secrets tab:

```json
{ "op": "enterPin", "secretRef": "bank-pin", "submit": true,
  "selector": { "viewId": "com.acme.bank:id/pin_pad" },
  "expect": { "text": "Accounts" } }
```

`enterPin` taps a labeled 3-column digit keypad, or fills the focused editable
field with `ACTION_SET_TEXT` when the lock screen is a text field. The optional
`selector` scopes the keypad search to a container, and `submit` (default true)
presses the keypad's Enter/check key or the IME action. The secret is never
included in the run report.

For a numeric secret, `enterPin` prefers the keypad over a hidden editable field
and waits up to 10 seconds for the keypad to appear. With `submit: true` it looks
for a button labeled submit/verify/continue/check, then a view id ending in one
of those words, then a bottom-right check target on the PIN screen. If none is
found, the step fails with `PIN entered, but no submit/check button was found`
instead of silently leaving the PIN unsubmitted.

App-lock prompts are handled inside the macro before navigation:

```json
{ "op": "appUnlock", "package": "com.phonepe.app" }
```

`appUnlock` runs the same flow as the panel action and is a **no-op success**
when no app-lock screen is showing, so it is safe to place after `launch` even
if the app was already unlocked. Use `enterPin` for the UPI/app PIN that
follows the lock.

`FLAG_SECURE` apps still block screenshots but do not block accessibility input;
custom canvas keypads that expose no digit nodes still need raw `tap` steps.
Biometric-only app locks cannot be automated.
| `confirm` | Human approval gate (optional) |

Every mutating step should have an `expect`. If it has none, the engine marks it `unverified` rather than assuming success.

### 4.3 Selectors

Priority: `viewId` → `text`/`textContains`/`regex` → `contentDescription` → `hint` → relative position → bounds (last resort) → OCR (not in v1).

A selector can be a combination of `viewId`, `text`, `contains`, `desc`, `descContains`, and `className`. Text matching collapses line breaks and repeated spaces, so a label rendered over two lines still matches its single-line form. Use `contains` for visible text and `descContains` for content descriptions; set `"ignoreCase": true` to match either without regard to case.

`index` picks the Nth match in accessibility-tree order, **1-based**; the default is the first match. It is resolved through macro params, so a macro can tap the first or second bank without hard-coding a name:

```json
{ "op": "tap", "selector": { "contains": "Bank Account", "index": "{{bank}}" } }
```

Keep `index` selectors narrow (`contains` or `className` plus a distinctive label). Two matches mean the next one after an app update, so re-record after major app upgrades.

**If/when own apps are added:** stable test tags are the biggest reliability lever. For Compose, `Modifier.testTag("login.submit")` plus `Modifier.semantics { testTagsAsResourceId = true }` exposes them as `viewIdResourceName`. For Views, set `android:id` or a test tag.

**For v1's third-party apps:** there are no test tags. Rely on `viewId` where the app exposes one, then `text`/`contentDescription`, then bounds. Pin macros to `appVersionCode`; re-probe and dry-run after updates.

### 4.4 Execution

- Run on-device; the server sends `runId` (idempotency key) and the resolved params.
- Preflight before step 1.
- Emit a step event per step; buffer events on-device and sync on reconnect.
- Stop on failure; capture screenshot + tree excerpt; return the failed selector and the visible tree.
- **Max runtime** per run (default 5 min) and a **kill switch** in the panel and on the notification.
- One run per device at a time (lease). A second admin must explicitly take over.
- `dryRun`: resolve every selector and screenshot, but do not tap.
- **No real transactions in v1.** For GPay/PhonePe/Paytm, stop at the review screen and require `requiresConfirmation`; never press the final "Pay" button. Test against a sandbox if one becomes available.

### 4.5 Recording

Record a human performing the task, then edit the draft.

| Signal | Recorded as |
|---|---|
| `TYPE_VIEW_CLICKED` | `tap` with the clicked node's selector |
| `TYPE_VIEW_TEXT_CHANGED` | `setText` (debounced, final value) |
| `TYPE_VIEW_SCROLLED` | `scroll` |
| `TYPE_WINDOW_STATE_CHANGED` | `waitFor` + package/activity context |
| Screen/app context | package, activity, screen size, and timestamp attached to each step |

**v1 scope:** taps, text, scroll, and screen/app context. Hardware-key capture (`canRequestFilterKeyEvents`) is deferred: it is a sensitive capability, may miss secure fields, and is not needed for the first version.

**Limits:** raw swipe traces are not observable through accessibility. Record scrolls where `TYPE_VIEW_SCROLLED` fires; otherwise add swipes manually. A transparent overlay could capture touches but would consume them, so it is not used.

**Recorder output:** a timeline → draft macro with `waitFor` and assertions inserted automatically → review/edit UI → dry run → save.

### 4.6 V1 example macros

| App | First macro | Stops before |
|---|---|---|
| WhatsApp | Open chat → type message → send | — (dedicated test number) |
| Chrome | Open URL → wait for page → screenshot | — |
| Settings | Open Wi-Fi panel → screenshot | Changing a toggle (manual confirm) |
| GPay | Open → select payee → enter amount → reach review | **Pay** (never pressed) |
| PhonePe | Open → select payee → enter amount → reach review | **Pay** (never pressed) |
| Paytm | Open → select payee → enter amount → reach review | **Pay** (never pressed) |

Start with read-only/navigation macros. Add write actions only after the app's probe is `controllable` and the macro has passed a dry run.

---

## 5. Backend

For 1–5 devices, keep it boring:

- **One Node/TypeScript process.** Fastify or NestJS. TypeScript shared with the web panel.
- **SQLite** (better-sqlite3) is sufficient; Postgres if you prefer. No Redis.
- **Presence** in memory + `devices.last_seen`.
- **On-demand only:** the admin starts a run; if the device is offline, reject with `device_offline` (no queue or cron in v1). Keep the `runs` table for history.
- **Snapshots:** local disk (`/data/snapshots/<device>/<run>/<step>.webp`), with an `expires_at` and a daily cleanup job.
- **Secrets:** encrypted at rest with a master key from env/KMS; `secretRef` in macros, never values.
- **Deploy:** Docker Compose on one VPS, or a small internal machine over Tailscale.

### Data model (minimum)

```
devices(id, name, model, android, agent_version, protocol_version, last_seen,
        status, token_hash, account_label, device_owner, state_json)
apps(device_id, package, label, version_name, version_code, icon_path,
     system, enabled, updated_at)                       -- PK(device_id, package)
app_capabilities(device_id, package, version_code, screenshot_ok, secure_window,
                 node_count, clickable_count, view_id_count, depth, verdict, probed_at)

snapshots(id, device_id, ts, w, h, rotation, package, activity,
          image_path, elements_json, bytes, expires_at)
actions(id, device_id, snapshot_id, admin_id, type, payload_json,
        ok, after_snapshot_id, ts)

macros(id, name, app_package, app_version_code, current_rev, params_json,
       requires_confirmation, tags, created_by)
macro_revs(macro_id, rev, steps_json, created_by, created_at)

macro_runs(id, macro_id, device_id, admin_id, status, params_json,
           requested_at, started_at, finished_at, error)
macro_run_steps(run_id, i, op, status, ms, snapshot_id, error, expect_ok)

secrets(id, name, ciphertext, created_by, rotated_at)
otp_events(id, device_id, source, received_at, used_at)  -- never the value
admins(id, email, password_hash, totp_secret, role)
audit(id, admin_id, device_id, action, detail_json, ts)
```

Run statuses: `running`, `success`, `failed`, `unverified`, `cancelled`, `preflight_failed`, `device_offline`.

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

- Raw snapshot with numbered hotspots; app inventory with per-app passive
  recording (no app launch).
- Click a number → tap → fresh screenshot.
- Type text on editable elements; scroll; global actions.
- **Device composer:** type in the panel and insert into the focused field
  (`inputText`), optionally pressing IME enter; screenshots ignore the keyboard,
  so this is the reliable path for text entry.
- Grid fallback (6×12 cells) for screens without a tree.
- "Pause auto-refresh on hover"; stable numbering.
- Used to debug a failed run and repair the selector.

---

## 7. Protocol

One WebSocket. JSON control; binary frames for images (or an HTTP URL). Every command has a `cmdId`; every device event has a monotonically increasing `seq` for resume.

```jsonc
// device → server
{ "t": "hello", "deviceId": "…", "protocolVersion": 1, "agent": "1.0.0",
  "android": 34, "capabilities": ["screenshot","otp.notification","otp.sms"] }
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

Even internal, this system can read OTPs and drive sensitive apps.

- **Network:** Tailscale/WireGuard; WSS with TLS. No public exposure unless necessary.
- **Device enrollment:** install → server URL + one-time enrollment code (or QR) → device appears as `pending` → admin approves → token stored in `EncryptedSharedPreferences`. Tokens rotate on re-enroll.
- **Admin auth:** password + TOTP; roles `viewer` / `operator` / `admin`.
- **Concurrency:** per-device lease; explicit takeover.
- **Secrets:** AES-GCM at rest with a master key; `secretRef` only; audit every read; rotate; never log values.
- **OTP:** memory only, TTL 5 min, single use; no DB, no logs, no screenshots on secret steps.
- **Screenshots:** may contain PII/balances; encrypt at rest, restrict access, retention (e.g., 30 days), delete on request.
- **Audit:** who ran what on which device, params (redacted), result, screenshots.
- **Package allowlist:** only probed/allowlisted packages can be controlled.
- **Kill switch:** panel + notification; stops the run and disconnects.
- **No hidden mode:** persistent notification while connected; "remote control active" indicator.
- **Sideload caveat (Android 13+):** enable Accessibility via *App info → ⋮ → Allow restricted settings*; document it in onboarding.

---

## 9. Limitations and failure modes

| Limitation | Impact | Mitigation |
|---|---|---|
| App hides UI (`accessibilityDataSensitive`) or blocks screenshots (`FLAG_SECURE`) | Empty tree / `SECURE_WINDOW` | Probe first; exclude `blocked` apps |
| No test tags in third-party apps | Selectors are fragile | Prefer `viewId`/text/description; pin `appVersionCode`; dry-run after updates |
| Third-party apps change their UI | Macros break | Re-probe on update; assertions; repair in assist console |
| Real OTPs on test accounts | Login depends on SMS/notification capture | Dedicated test accounts; notification listener; human fallback; never store values |
| Payment confirmation | Risk of real money movement | Stop before the final confirm; `requiresConfirmation`; sandbox when available |
| Screen locked / asleep | Screenshot fails, input stops | Stay-awake + no lock; Device Owner `setKeyguardDisabled`; preflight |
| Secure PIN pads / biometrics | Cannot automate | Human step or app test hook |
| Empty tree (games/canvas) | No hotspots | Grid fallback; not a v1 target |
| OTP latency / listener misses | Run times out | Ring buffer, `waitForOtp` timeout, human fallback |
| SMS Retriever constraints | Only ≤140-byte, hash-tagged SMS within 5 min | Works only for own apps; otherwise notification listener |
| Notification access revoked | OTP unavailable | Preflight check; human fallback |
| Macro breaks on app update | Failed run | Re-probe, `appVersionCode` pin, assertions, dry run, repair in assist console |
| Recorder can't capture raw swipes | Draft missing gestures | Record scrolls; add swipes manually |
| OEM battery killers | Agent dies | Battery-optimization exemption, `specialUse` FGS, MDM |
| Device offline mid-run | Lost report | On-device buffer + `seq` resume; idempotent `runId` |
| Multi-window | Ambiguous coords | Capture full display; document single-window |
| IME appears over the app | Hotspot mismatch | Wait for idle; treat IME as its own window |
| Messaging UI automation | ToS/ban risk | Dedicated test number; no bulk messaging; official API later |
| Work profiles | Inventory/tree differences | `LauncherApps`; separate listener per profile |

---

## 10. Roadmap

| Phase | Deliverable | Effort (1–2 devs) |
|---|---|---|
| **0. Spike** | Inventory, launch, snapshot + hotspot dump, device-state banner, capability probe over the six v1 apps | 4–6 days |
| **1. Console** | WSS relay + run dashboard shell + numbered snapshot + tap/text/scroll/back + grid | 2 weeks |
| **2. Engine** | Macro schema, runner, preflight, assertions, `tapIfExists`/`waitForIdle`, run report, offline buffer | 2–3 weeks |
| **3. OTP + record** | Notification/SMS OTP, human fallback, secrets vault, recorder + editor, parameters | 2 weeks |
| **4. Harden** | Tailscale, enrollment, retention, alerts, OEM matrix, CI, observability | 1–2 weeks |

**Total: ~8–10 weeks** for a solid v1 on 1–5 devices.

> **Phase 0 status:** scaffolded in `android/` — inventory, launch, settled
> screenshot, hotspot extraction, device-state card, and the six-app probe are
> working. See `android/README.md` for build/install/enable steps.
>
> **Phase 1 status:** `server/` (WSS relay + static web) and `web/` (React
> console) are scaffolded; the agent has a foreground relay service that sends
> inventory/state/snapshots and accepts tap/text/scroll/back/probe commands.
> Run instructions in `server/README.md` and `web/README.md`.
>
> **Phase 2 status:** macros (JSON editor + parameters), on-device macro engine
> with `expect`/`assert`/`tapIfExists`, run reports with checkpoint screenshots,
> and a live recorder (text/tap/scroll/window events, password redaction) are
> implemented. Macros persist in `server/data/macros.json`.
>
> **Phase 3 status:** OTP capture (notification listener + SMS receiver) with
> `waitForOtp` and a human prompt fallback; AES-GCM secrets with `secretRef`;
> a 6×10 grid fallback; and recorder → macro drafts are implemented.
>
> **Per-app recording:** the web's app-row **Record** starts a passive session
> (no app launch); open the app yourself, and leaving the app or **Stop & save**
> auto-builds and saves a macro (launch, taps, text, scrolls, screenshot).

**Phase 0 exit criteria:** on two devices, list apps, launch one, capture a settled screenshot, and classify the six v1 apps as `controllable` / `partial` / `blocked`. If that works, the rest is engineering.

---

## 11. Repository layout

```
RC-app/
├── android/
│   ├── app/                  # onboarding, enrollment, status
│   ├── accessibility/        # service, tree walker, screenshots, actions
│   ├── inventory/            # LauncherApps, icons
│   ├── macros/               # engine, selectors, preflight
│   ├── recorder/             # event timeline, screen/app context
│   ├── otp/                  # notification listener, SMS Retriever, buffer
│   └── transport/            # WSS client, reconnect, buffer
├── server/                   # Node/TS: devices, runs, macros, secrets, audit
├── web/                      # Next.js: dashboard, report, editor, assist
├── shared/                   # JSON Schema + generated TS/Kotlin models
├── infra/                    # docker-compose, Tailscale notes, retention job
└── docs/
```

---

## 12. Open questions

1. **Failure alerts:** dashboard only, or email/Slack/Telegram?
2. **Retention:** how long should run screenshots be kept?
3. **First macro per app:** confirm the example table in §4.6, or name a different flow for WhatsApp/Chrome/Settings.
4. **Account labels:** what should the dashboard show (e.g., `test-01`, last 4 digits), and who can see it?

---

## 13. Summary

- **A probed app set + Android 13+ + 1–5 devices** turns this into a tractable internal tool.
- **Fully automatic, on demand** makes the **macro engine + run report** the product; the numbered console is the debugger.
- **OTP** is the riskiest integration: notification listener first, human fallback when capture fails; SMS Retriever only for your own apps.
- **Keep the devices unlocked and awake** before writing more code; treat lock/sleep as the top operational failure.
- **Without test tags, text/description selectors and `appVersionCode` pinning** are the reliability levers. The capability probe tells you which apps are worth automating.
