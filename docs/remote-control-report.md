# Remote Control Android App — Options & Implementation Report

**Project:** `RC-app` — a lightweight, AnyDesk-style remote control system for Android
**Status:** Draft / decision document
**Date:** 2026-11-22
**Audience:** Project owner + implementation team

---

## 0. TL;DR

There is exactly one way to build a real, installable "mini AnyDesk" without root, ADB, or a custom ROM:

> **Screen out:** `MediaProjection` + `MediaCodec`/WebRTC
> **Input in:** `AccessibilityService.dispatchGesture()` + accessibility global actions
> **Transport:** WebRTC (internet) or WebSocket + H.264 (LAN/VPN)
> **Admin side:** web panel first, native admin app later

Everything else (scrcpy/ADB, Device Owner, privileged system app, root) is a variation that trades user friction for capability. The report below lays out all of them, compares them, and recommends a phased build.

**Recommended MVP:** app-based agent (MediaProjection + Accessibility) → WebSocket/H.264 on LAN → WebRTC + TURN for internet → web admin panel. Roughly **3–5 months for two developers** to reach a solid v1, with a working LAN demo in **4–6 weeks**.

**Biggest risk is not technical — it is distribution policy.** Google Play treats `AccessibilityService` remote-control apps as high-scrutiny. Plan for sideload / enterprise / MDM distribution for v1 and treat Play as a later, carefully-framed goal.

---

## 1. Goal and scope

Build a system where an operator can, from a browser (or an admin app), see and control an Android device:

| Capability | MVP | Later |
|---|---|---|
| Live screen view | ✅ | |
| Touch / tap / swipe / drag | ✅ | |
| Keyboard text input | ✅ | |
| System actions (Home, Back, Recents, Notifications, Lock) | ✅ | |
| Device list, pairing code, approve/deny | ✅ | |
| Clipboard sync | | ✅ |
| File transfer | | ✅ |
| Multi-device dashboard | | ✅ |
| Session recording / audit log | | ✅ |
| Audio forwarding | | ⚠️ hard, see §6.1 |
| Unattended access (password) | | ✅ |
| Camera / mic | ❌ out of scope | |

Non-goals for v1: root, ADB dependency, iOS, DRM-protected content capture, silent/hidden operation.

---

## 2. The constraint that shapes every decision

Android is a multi-tenant OS with a strict app sandbox. A normal installed app **cannot**:

- read the screen of other apps without an explicit, per-session user consent dialog (`MediaProjection`);
- inject touch events into other apps without `INJECT_EVENTS` (signature permission) — the sanctioned workaround is an enabled `AccessibilityService`;
- inject arbitrary hardware key events globally;
- capture other apps' audio (only opt-in `AudioPlaybackCapture`);
- keep a screen-capture session alive indefinitely (Android 15+ stops capture on screen lock).

This is why "just build an app" does not get you AnyDesk. AnyDesk/TeamViewer/RustDesk all ship a **device-side agent** that combines MediaProjection (view) with an AccessibilityService (control), and show a persistent system indicator. That is the ceiling for a non-privileged app, and it is enough for a good product.

### Platform changes to design for (Android 13 → 17)

| Version | Change | Impact |
|---|---|---|
| 13 (API 33) | `POST_NOTIFICATIONS` runtime permission | Must ask before FGS notification shows |
| 14 (API 34) | `foregroundServiceType="mediaProjection"` + `FOREGROUND_SERVICE_MEDIA_PROJECTION` mandatory; MediaProjection token is **one-shot**; `createVirtualDisplay()` once per token; `registerCallback()` required before `createVirtualDisplay()` | Capture service must be structured around a single session; re-consent for every new session |
| 15 (API 35) | Capture **stops when the screen locks**; status-bar chip lets the user kill it; `BOOT_COMPLETED` cannot start a mediaProjection FGS | Unattended access must prompt the user to unlock and re-approve; no auto-start capture |
| 16 (API 36) | Large-screen orientation/resize restrictions relaxed | Capture dimensions change; handle `onCapturedContentResize()` |
| 17 (API 37) | `ACCESS_LOCAL_NETWORK` runtime permission for apps targeting 37; stricter background audio | LAN discovery/pairing needs the new permission; audio cues may stop in background |

Also: `FLAG_SECURE` windows (banking, DRM video) render **black** in the capture — this is expected and cannot be bypassed.

---

## 3. Capability options at a glance

| Approach | Screen capture | Input injection | User friction | Play Store viable | Notes |
|---|---|---|---|---|---|
| **A. App: MediaProjection + Accessibility** | ✅ per-session consent | ✅ gestures + global actions | Low (2 toggles) | ⚠️ high scrutiny | The "real product" path |
| **B. scrcpy / ADB-based** | ✅ via shell | ✅ via shell (`input`, `INJECT_EVENTS` as shell) | High (enable USB/wireless debugging) | ❌ not a consumer app | Best for internal/dev/kiosk |
| **C. Device Owner / MDM** | ✅ (still consent, unless privileged) | ✅ accessibility can be pre-provisioned in some setups | Medium (provisioning) | ✅ enterprise | Managed/fleet use |
| **D. Privileged / system app or custom ROM** | ✅ silent | ✅ full | None | ❌ not distributable | OEM / dedicated hardware only |
| **E. Root** | ✅ | ✅ | Very high | ❌ | Avoid |

---

## 4. Architecture options in detail

### Option A — Device agent app (MediaProjection + Accessibility) ⭐ recommended

```
┌─────────────────────┐         ┌──────────────────────┐         ┌──────────────────┐
│  Android device     │         │  Backend             │         │  Admin web panel │
│                     │         │                      │         │                  │
│ MediaProjection ────┼─ H.264 ─┤ Signaling / Relay    ├─ WebRTC ┼─ <video> + input │
│ VirtualDisplay      │  (RTP)  │ Auth, presence, TURN │         │  canvas / cursor │
│                     │         │ Audit, push (FCM)    │         │                  │
│ AccessibilityService│◄─ input ┤                      │◄─ input ┤  mouse / keyboard│
│ dispatchGesture()   │  (DC/WS)│                      │         │                  │
└─────────────────────┘         └──────────────────────┘         └──────────────────┘
```

**How it works**

1. Agent registers with backend, gets a device ID, shows a 9-digit session code (AnyDesk style) or is bound to an account.
2. Admin opens the web panel, selects the device, sends a connection request.
3. Device shows an in-app approval prompt (and the mandatory MediaProjection consent dialog on first session). User taps **Allow**.
4. Agent starts a `mediaProjection` foreground service, creates a `VirtualDisplay` at screen resolution, encodes with `MediaCodec` (H.264) or WebRTC's `ScreenCapturerAndroid`, and streams.
5. Admin input events arrive over a WebRTC DataChannel or WebSocket; agent converts them to `GestureDescription` / `ACTION_SET_TEXT` / global actions.
6. On disconnect, lock, or user revoke → tear down, notify both sides, require fresh consent next time.

**Pros**
- No root, no ADB, no special hardware; installable APK.
- Works over the internet with WebRTC + TURN.
- Matches how commercial products work; auditable consent UX.
- Same codebase can later be provisioned as a Device Owner agent (Option C).

**Cons**
- User must enable Accessibility manually (Settings → Accessibility) and re-approve capture per session.
- Android 15+ kills capture on lock → unattended access is awkward.
- Play Store policy risk (see §10).
- Input fidelity is good but not perfect (see §6.2).
- No system audio for a normal app (see §6.1).

**Best for:** the product you actually want to ship.

---

### Option B — scrcpy/ADB-based (no on-device app)

```
Browser ──WS──► Node/Go server ──ADB──► device (scrcpy-server.jar, runs as shell)
```

You push `scrcpy-server.jar` to `/data/local/tmp`, forward a socket, and relay H.264 + control messages. Open-source references: **scrcpy**, **ws-scrcpy**, **scrhub**, **scws**, **OpenDroid Remote** (WebUSB + ADB in the browser).

**Pros**
- Best-in-class latency (35–70 ms on LAN), 30–120 fps, audio forwarding, clipboard, file push.
- Nothing installed on the device; no Accessibility toggle; no Play policy.
- Extremely fast to prototype a web viewer.

**Cons**
- Requires **USB debugging** or **wireless debugging pairing** (Android 11+), which is a developer workflow, not a consumer one.
- Wireless ADB drops on reboot; pairing needs a code; many corporate devices disable it.
- Not distributable as a consumer app; Google Play will not accept an app whose purpose is to drive ADB.
- Security: ADB over TCP is unauthenticated/unencrypted unless tunneled.

**Best for:** internal tooling, QA labs, device farms, demos, a "power user" mode in your own product, and **proving the web panel UI** before the real agent exists.

> Practical tip: build the web viewer against the scrcpy protocol first (a weekend with `ws-scrcpy`), then swap the transport for your own agent. The UI work is reusable.

---

### Option C — Device Owner / MDM (enterprise fleet)

Provision the agent as **Device Owner** via QR / zero-touch / `adb dpm set-device-owner`:

- Pre-grant runtime permissions (`CAMERA`, `POST_NOTIFICATIONS`, etc.) with `setPermissionGrantState`.
- Keep the app alive, pin it, enforce kiosk/lock-task mode.
- Control network, disable uninstall, push config.
- Still **cannot** silently grant MediaProjection consent or enable Accessibility in the general case — those remain user actions (or require a privileged/system app). Verify per Android version and OEM; some DPCs use `setSecureSetting` only for a narrow allowlist, not accessibility.

**Pros:** legitimate enterprise distribution, no Play review friction, strong device control.
**Cons:** provisioning ceremony, only makes sense for managed fleets, not "send a link to grandma".

**Best for:** company-owned tablets, kiosks, field devices, support fleets.

---

### Option D — Privileged / system app or custom ROM (OEM path)

With platform signing keys you get `CAPTURE_VIDEO_OUTPUT`, `INJECT_EVENTS`, `CAPTURE_AUDIO_OUTPUT`, silent capture, full key injection, and no consent dialogs.

**Pros:** the "real AnyDesk" experience, zero friction, audio, true mouse/keyboard.
**Cons:** requires AOSP build or OEM partnership; cannot be installed on retail phones; huge compliance burden.

**Best for:** hardware vendors, digital-signage/kiosk products, Android TV boxes, custom devices.

---

### Option E — Root

`su` + `input`/`sendevent`, `screenrecord`, `SurfaceFlinger` capture. Works, but:
- Rooting is unacceptable for most users and voids warranties.
- Root detection breaks banking/DRM apps.
- Distribution is effectively impossible.

**Verdict:** support it as an optional "power mode" if you ever need it; never make it the baseline.

---

## 5. Comparison and recommendation

Scoring 1–5 (5 = best):

| Criterion | A. App agent | B. scrcpy/ADB | C. Device Owner | D. Privileged | E. Root |
|---|---|---|---|---|---|
| Install/onboarding ease | 4 | 1 | 2 | 5 | 1 |
| Input fidelity | 3 | 5 | 3 | 5 | 5 |
| Latency | 4 | 5 | 4 | 5 | 5 |
| Works over internet | 5 | 2 | 5 | 5 | 5 |
| Play Store viability | 2 | 1 | 4 | 1 | 1 |
| Engineering effort (5 = least) | 3 | 5 | 3 | 1 | 2 |
| Audio support | 2 | 4 | 2 | 5 | 4 |
| **Fit for "mini AnyDesk"** | **✅** | dev only | enterprise | no | no |

**Recommendation**

1. **Build Option A as the product.**
2. **Use Option B as an internal spike** to validate the web UI and measure latency expectations — do not ship it as the main path.
3. **Add Option C provisioning later** if you target fleets.
4. Keep the transport abstracted so a privileged build (Option D) can reuse 90% of the code.

---

## 6. Subsystem design

### 6.1 Screen capture & encoding

**Pipeline**

```
Display ──► MediaProjection ──► VirtualDisplay ──► Surface
                                                    │
                                          MediaCodec (H.264/H.265/AV1)
                                                    │
                                          NAL units ──► WebRTC / WebSocket
```

**Key implementation points**

- Create the foreground service **before** `getMediaProjection()` (Android 14+ throws `SecurityException` otherwise).
- `MediaProjection` tokens are **single-use**; one `createVirtualDisplay()` per token. Design a session object that owns exactly one token.
- Register `MediaProjection.Callback` **before** `createVirtualDisplay()` (`IllegalStateException` otherwise).
- Handle `onStop()` (user revoke, lock, status-bar chip) → stop encoder, close sockets, tell the admin, show "session ended".
- Handle `onCapturedContentResize()` / `onCapturedContentVisibilityChanged()` (Android 14+) → reconfigure encoder, send new dimensions to the viewer.
- Use `WindowMetrics` (API 30+) for the real capture size; scale to a target (e.g. max 1080p long edge) to control bandwidth.
- Encode with `MediaCodec` in surface mode; request a keyframe on connect and after loss.
- Recommended defaults: **720p–1080p, 15–30 fps, 1.5–4 Mbps H.264 baseline/main**, adaptive down to 360p/5 fps on poor links.
- `FLAG_SECURE` and DRM surfaces capture as black — detect and warn the operator instead of showing a frozen black screen.

**Audio (why it is deferred)**

A normal app can only capture audio from apps that opt in to `AudioPlaybackCapture` (API 29+, `allowAudioPlaybackCapture`), which most media apps disable. Full system audio needs `CAPTURE_AUDIO_OUTPUT` (privileged) or the `REMOTE_SUBMIX` route scrcpy uses as shell. **Plan audio as a later, "best effort" feature**, and never promise it in marketing for the app-based build.

**Alternative for low-end devices / very low bandwidth:** `ImageReader` → JPEG frames over WebSocket at 3–8 fps. Simple, no codec negotiation, but 5–10× the bandwidth per frame. Good fallback, not the default.

---

### 6.2 Input injection

**Touch and gestures — `AccessibilityService.dispatchGesture()`**

- Requires `android:canPerformGestures="true"` in the service config and the user enabling the service.
- A gesture is a set of `StrokeDescription`s (each a `Path` + start time + duration). Multiple strokes = multi-touch.
- `willContinue=true` + `continueStroke()` gives a held pointer for drags; the next gesture continues with `ACTION_MOVE`.
- Coordinate mapping: viewer sends **normalized 0–1** coordinates; agent maps to current display pixels (handles rotation/resolution changes).
- Only **one gesture at a time**; a real finger touch cancels the injected gesture. Do not fight the local user — detect local touch and pause remote control.
- Known platform bug: injected touches can leave a "stuck pointer" if a local multi-touch overlaps; recover by sending an `ACTION_CANCEL` / re-dispatching a clean tap. Test on multiple OEMs.

**Keyboard**

- Text: find the focused editable node (`AccessibilityNodeInfo.isEditable()`) and perform `ACTION_SET_TEXT` with a `Bundle`. This is the reliable path; it does not require per-key events.
- Enter / IME actions: `ACTION_IME_ENTER` (API 30+) on the focused node.
- Hardware keycodes (volume, media, back) cannot be injected globally by a normal app. Use:
  - `performGlobalAction(GLOBAL_ACTION_BACK / HOME / RECENTS / NOTIFICATIONS / QUICK_SETTINGS / LOCK_SCREEN / TAKE_SCREENSHOT / POWER_DIALOG)`.
  - For volume, the only non-privileged option is a visible on-screen volume control or `AudioManager.adjustStreamVolume` (changes volume, not a key event).
- Unicode/IME: prefer clipboard + paste for complex scripts if `ACTION_SET_TEXT` is rejected by the field.

**Mouse**

Android has no system cursor for a normal app. Two designs:

1. **Direct touch mode (default):** mouse/trackpad maps to absolute taps and drags — like a touchscreen. Simplest, works everywhere.
2. **Virtual cursor mode:** draw a pointer overlay in the agent, move it with relative mouse deltas, tap on click. More "desktop", more work, and the overlay must not intercept touches (`FLAG_NOT_TOUCHABLE`).

**Global actions available (subset):** `BACK`, `HOME`, `RECENTS`, `NOTIFICATIONS`, `QUICK_SETTINGS`, `POWER_DIALOG`, `LOCK_SCREEN`, `TAKE_SCREENSHOT`, `ACCESSIBILITY_SHORTCUT`, `DPAD_*` (via key events on focused node, not global).

**Privileged fallback (later):** `INJECT_EVENTS` + `InputManager.injectInputEvent()` for true mouse/keyboard and continuous pointers.

---

### 6.3 Transport

Two credible choices:

#### WebRTC (recommended for internet)

- **Why:** built-in DTLS-SRTP encryption, ICE/STUN/TURN NAT traversal, congestion control (GCC/transport-cc), adaptive bitrate, jitter buffer, DataChannels.
- **Android:** `io.github.webrtc-sdk:android` (maintained fork) or `org.webrtc:google-webrtc` (older). `ScreenCapturerAndroid` wraps MediaProjection.
- **Web:** native `RTCPeerConnection`; `<video>` for the stream.
- **Server:** signaling WebSocket + **coturn** for TURN. Optional SFU (LiveKit, mediasoup, Janus) if you need many viewers per device.
- **Control channel:** `RTCDataChannel` — use **unordered/unreliable** for pointer moves, **ordered/reliable** for commands and text.
- **Latency:** ~100–300 ms over the internet; ~50–120 ms on LAN.

#### WebSocket + H.264 (recommended for LAN/VPN and for the first milestone)

- **Why:** dramatically simpler; no ICE/TURN/SDP; easy to debug; works through any proxy.
- **Video:** binary WS frames containing NAL units; browser decodes with **WebCodecs** (Chromium) or MSE (broader support).
- **Control:** JSON or MessagePack on the same socket (or a second socket).
- **Server:** Node.js (`ws`/`uWebSockets.js`) or Go; can be a dumb relay.
- **Cons:** you build reliability, congestion control, reconnection, and encryption (X25519 + AES-GCM) yourself; no NAT traversal — needs a relay or VPN.
- **Latency:** ~50–150 ms on LAN; degrades badly on lossy mobile networks.

**Recommended hybrid**

- MVP: WebSocket + H.264 (fast to build, great demo).
- v1: WebRTC for media, keep a WebSocket for signaling/commands/fallback.
- Abstract behind a `Transport` interface so the UI does not care.

**Protocol sketch (control)**

```jsonc
{ "t": "hello",   "v": 1, "deviceId": "…", "token": "…" }
{ "t": "tap",     "x": 0.42, "y": 0.61 }
{ "t": "swipe",   "x1": 0.1, "y1": 0.8, "x2": 0.9, "y2": 0.2, "ms": 300 }
{ "t": "down",    "id": 0, "x": 0.5, "y": 0.5 }
{ "t": "move",    "id": 0, "x": 0.6, "y": 0.5 }
{ "t": "up",      "id": 0 }
{ "t": "text",    "s": "hello" }
{ "t": "action",  "name": "home" }
{ "t": "clipboard", "dir": "toDevice", "text": "…" }
{ "t": "ping",    "ts": 1730000000000 }
```

Binary video frame header: `[1 byte type][1 byte flags][4 byte length][payload]`; keyframe flag in `flags`.

---

### 6.4 Session, pairing, auth, security

**Pairing models**

| Model | UX | Use case |
|---|---|---|
| **Session code** (9 digits, AnyDesk style) | Device shows code; admin types it | Ad-hoc support |
| **Account binding** | Device logs into account; appears in dashboard | Personal multi-device |
| **Invite link / QR** | Device scans QR or opens link | Consumer onboarding |
| **Unattended password** | Fixed password on device | Servers/kiosks (risky) |

**Connection flow (recommended)**

1. Device agent opens an **outbound** WebSocket to the backend and registers (device ID + public key). No inbound ports, works behind NAT.
2. Admin authenticates (email/password + TOTP), selects device, creates a **short-lived session token** (60–120 s, single use).
3. Backend notifies the device (WS or FCM push). Device shows **"<Admin name> wants to control this device"** with Allow/Deny.
4. On Allow, both sides exchange SDP/ICE (WebRTC) or open the media socket (WS).
5. Media is end-to-end encrypted (DTLS-SRTP, or app-layer AES-GCM for WS). TURN relays ciphertext only.
6. Session ends on disconnect, idle timeout, lock, or user tap on the persistent notification. Token is burned.

**Security checklist**

- Device approval on every session (no silent connect), visible persistent notification + status-bar indicator.
- Short-lived, single-use session tokens; JWT with `aud`/`exp`/`jti`; revoke on use.
- Device identity: per-device keypair, mTLS or signed challenge; pin public keys on the backend.
- E2EE media; TURN credentials via coturn's time-limited HMAC REST API; no plaintext fallback.
- Admin: TOTP/2FA, per-account device ACLs, IP allowlist option, rate limiting, lockout.
- Audit log: who connected to which device, when, duration, actions (at least session-level), recording flag.
- **Kill switch** on the device (notification action + in-app button) that immediately stops capture and input.
- Local-user priority: any real touch pauses remote input; show "remote control active" banner.
- Auto-expire unattended sessions; never allow hidden/background operation.
- Treat recordings as sensitive: encrypt at rest, retention policy, consent notice.

---

### 6.5 Backend and admin panel

**Backend (MVP)**

- Node.js + TypeScript (Fastify or NestJS) **or** Go (if you prefer one static binary).
- PostgreSQL: users, devices, sessions, audit log.
- Redis: presence, session tokens, pub/sub for signaling.
- `coturn` for TURN (when you move to WebRTC).
- S3-compatible storage for recordings/files.
- FCM for waking the device when the app is backgrounded (best effort; Android may throttle).
- Deploy: one VPS + Docker Compose is enough for MVP; split TURN to its own host/bandwidth later.

**Admin panel (web-first)**

- Next.js/React + TypeScript, Tailwind.
- Device grid with online/offline, last seen, platform/version.
- Session view: `<video>` (WebRTC) or canvas (WebCodecs), pointer/keyboard capture, toolbar (Home/Back/Recents/Lock, clipboard, file, quality).
- Connection request + approval status, latency indicator, quality selector.
- Session recording playback, audit log, user/role management.
- PWA install for a near-native feel; push notifications for connection requests.

**Native admin app (later)**

- Kotlin + WebRTC SDK, or a thin wrapper (React Native/Flutter) around the same web UI.
- Only worth it if you need background push, biometrics, or offline device management. **Do not build it first.**

---

## 7. Recommended MVP architecture

```
┌──────────────────────────────┐
│ Android agent (Kotlin)       │
│  • MediaProjection service   │
│  • MediaCodec H.264          │
│  • AccessibilityService      │
│  • WS client (v0) → WebRTC   │
│  • Pairing UI + approval     │
└──────────────┬───────────────┘
               │ outbound WSS / WebRTC
┌──────────────▼───────────────┐
│ Backend (Node/TS)            │
│  • Auth (JWT + TOTP)         │
│  • Device registry/presence  │
│  • Signaling + session broker│
│  • Audit log                 │
│  • coturn (TURN)             │
└──────────────┬───────────────┘
               │ WSS / WebRTC
┌──────────────▼───────────────┐
│ Web admin panel (Next.js)    │
│  • device list + connect     │
│  • video + input capture     │
│  • toolbar + quality         │
└──────────────────────────────┘
```

**Stack**

| Layer | Choice | Why |
|---|---|---|
| Agent | Kotlin, minSdk 26 (Android 8), target 36 | Modern APIs, broad coverage |
| Capture | MediaProjection + MediaCodec (surface) | Only viable non-root path |
| Input | AccessibilityService | Only viable non-root path |
| Transport v0 | WebSocket + H.264 + WebCodecs | Fastest path to a demo |
| Transport v1 | WebRTC + coturn | Internet-grade |
| Backend | Node.js + TypeScript + Postgres + Redis | Fast iteration, shared types |
| Admin | Next.js + React + Tailwind | Web-first, PWA |
| Infra | Docker Compose on one VPS | Cheap MVP |

---

## 8. Phased roadmap

| Phase | Deliverable | Effort (2 devs) | Exit criteria |
|---|---|---|---|
| **0. Spike** | Local capture + injection, no network | 1–2 weeks | Tap/swipe/text works on 2–3 devices; 15 fps preview |
| **1. LAN MVP** | Agent ↔ WS relay ↔ web viewer, session code | 4–6 weeks | <150 ms LAN latency, stable 10-min session, reconnect |
| **2. Internet v1** | WebRTC + TURN + auth + approval + audit | +6–10 weeks | Works on 4G/Wi-Fi, NAT traversal, 2FA, device prompt |
| **3. Product polish** | Clipboard, file transfer, multi-device, recording, quality controls | +4–6 weeks | Usable daily; crash-free >99% |
| **4. Distribution** | Enterprise/MDM packaging, policy docs, Play submission (optional) | +3–6 weeks | Signed release, privacy policy, accessibility declaration |

**Total to a credible v1: ~3–5 months.** The spike is the riskiest part — do it first, on real hardware, before committing to the full architecture.

---

## 9. Proposed repository layout

```
RC-app/
├── android/                     # Kotlin agent
│   ├── app/                     # UI, onboarding, pairing, approval
│   ├── capture/                 # MediaProjection + MediaCodec + WebRTC capturer
│   ├── input/                   # AccessibilityService + gesture mapper
│   ├── transport/               # WS + WebRTC clients behind an interface
│   └── protocol/                # Shared message models (Kotlin)
├── server/                      # Node/TS backend
│   ├── src/auth/
│   ├── src/devices/
│   ├── src/signaling/
│   ├── src/sessions/
│   └── src/audit/
├── web/                         # Next.js admin panel
│   ├── app/
│   ├── components/player/       # WebRTC / WebCodecs player
│   └── components/control/      # pointer + keyboard capture
├── shared/                      # Protocol schema (JSON Schema / TS types)
├── infra/                       # docker-compose, coturn, deploy
├── docs/                        # This report + ADRs
└── README.md
```

---

## 10. Risks, policy, and legal

### Google Play policy (highest risk)

- Apps using `AccessibilityService` must complete a **Play Console declaration** and, if not an accessibility tool, show a **prominent in-app disclosure** and obtain affirmative consent.
- `isAccessibilityTool=true` is reserved for apps whose core purpose is disability support — **do not use it** for a remote-control app.
- Policy forbids using the Accessibility API to "work around Android built-in platform security controls" and forbids autonomous action execution. A remote-control app is dual-use and will get **manual review**; expect friction, rejection, or removal.
- **Recommendation:** ship v1 via direct APK / enterprise / MDM; if you pursue Play, frame it as consent-based remote support with a visible indicator, no unattended silent mode, and a clear privacy policy. Budget time for review.

### Security / abuse

- Remote-control software is a malware category. Attackers will try to repackage it. Mitigate: code signing, Play Integrity / SafetyNet attestation, no hidden mode, visible session UI, device-side kill switch, published security contact.
- Never ship a default unattended password. Require the user to set one explicitly.
- Treat the backend as a high-value target: E2EE, minimal metadata, audit logs, least privilege.

### Legal

- Remote access requires **informed consent** of the device owner/user. Log consent.
- Recording sessions may trigger wiretapping/consent laws. Add a recording indicator and consent step.
- India: Digital Personal Data Protection Act (DPDP) 2023 — purpose limitation, notice, consent, breach reporting.
- EU: GDPR; employee monitoring rules. US: state consent laws.
- Add a clear privacy policy, data retention, and deletion path before any public release.

### Technical risks

- OEM variance: Xiaomi/Oppo/Vivo battery managers kill foreground services; ask for battery-optimization exemption.
- Accessibility services are sometimes disabled by OEM "security" features.
- Android 15 lock-stops-capture breaks unattended access; design the UX around re-approval.
- `FLAG_SECURE` black screens and DRM content will generate support tickets — document it.
- Network: mobile NAT, carrier-grade NAT, corporate firewalls → TURN is mandatory for v1.

---

## 11. Open decisions

1. **Distribution:** private APK / enterprise first, or attempt Play? (Recommend private first.)
2. **Transport:** start with WebSocket and add WebRTC later, or go WebRTC from day one? (Recommend WS first.)
3. **Admin surface:** web-only for v1, or web + native? (Recommend web-only.)
4. **Identity:** account-based, session-code, or both? (Recommend both: account for owned devices, code for ad-hoc.)
5. **Audio:** in scope for v1? (Recommend defer; best-effort later.)
6. **Unattended access:** support at all? (Recommend yes but opt-in, password + warning.)
7. **Multi-viewer:** one controller at a time, or observer mode? (Recommend one controller + optional read-only observers.)
8. **Backend language:** TypeScript or Go? (Recommend TS for shared types with the web panel.)
9. **Hosting:** single VPS MVP vs managed (LiveKit Cloud, Twilio) to avoid TURN ops? (Recommend VPS + coturn for cost; managed if time-to-market matters.)
10. **Branding/legal entity** and privacy policy owner before public release.

---

## 12. References

- Android media projection — https://developer.android.com/media/grow/media-projection
- Foreground service types (`mediaProjection`) — https://developer.android.com/develop/background-work/services/fgs/service-types
- MediaProjectionManager — https://developer.android.com/reference/android/media/projection/MediaProjectionManager
- AccessibilityService / `dispatchGesture` — https://developer.android.com/reference/android/accessibilityservice/AccessibilityService
- Create an accessibility service — https://developer.android.com/guide/topics/ui/accessibility/services
- Play policy: Accessibility API — https://support.google.com/googleplay/android-developer/answer/10964491
- Play Developer Program Policy — https://support.google.com/googleplay/android-developer/answer/17105854
- scrcpy (architecture) — https://github.com/Genymobile/scrcpy ; https://scrcpyapp.org/en/guides/develop
- ws-scrcpy (web client reference) — https://github.com/NetrisTV/ws-scrcpy
- scrhub (Go + WebCodecs reference) — https://github.com/Fubuki233/scrhub
- RustDesk (closest open-source analog) — https://github.com/rustdesk/rustdesk
- WebRTC Android SDK — https://github.com/webrtc-sdk/webrtc
- coturn — https://github.com/coturn/coturn
