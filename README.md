# RC-app — Remote Control for Android

An internal tool to run predefined macros on Android devices from a web panel:
launch an app, execute a macro, and get a report with checkpoint screenshots.
A numbered-screenshot console is available for debugging and repair.

> **Status:** design locked. See
> [`docs/step-control-design.md`](docs/step-control-design.md).

## Scope (v1)

- **Targets:** WhatsApp, Google Pay, PhonePe, Paytm, Chrome, Settings — each
  admitted only after the **capability probe**. No real payments.
- **Devices:** 1–5 remote phones, Android 13+. Device Owner optional (QR or ADB),
  and everything must also work without it.
- **Mode:** fully automatic and on demand; human fallback for OTPs and macro repair.
- **OTP:** real OTPs on dedicated test accounts; SMS/notification capture + manual fallback.
- **Admin:** web panel only; no live video (screenshot per step).

## Approach

| Layer | Plan |
|---|---|
| Android agent | Kotlin — `AccessibilityService` tree + `takeScreenshot` + actions |
| Execution | On-device macro engine with preflight, assertions, offline buffering |
| Recording | Capture taps/text/scroll + screen/app context; no hardware keys in v1 |
| Transport | Single WebSocket relay (WSS); `specialUse` foreground service |
| Backend | Node.js/TypeScript relay; JSON macros/secrets, in-memory device state |
| Admin | Vite + React dashboard — login, device picker, macro editor, reports |

Optional later: an ADB-based lab controller for devices on a bench.

## Documents

- [**Design (current)**](docs/step-control-design.md) — decisions, architecture,
  macros, OTP, recorder, protocol, roadmap.
- [Original options report](docs/remote-control-report.md) — the earlier
  live-streaming exploration; kept for reference.

## Getting started

The Android agent scaffold is in [`android/`](android/). Build it with:

```bash
cd android
./gradlew :app:assembleDebug
```

See [`android/README.md`](android/README.md) for install, enrollment,
accessibility, release builds, and Device Owner setup. The app is a WebView
shell with a foreground service; remote control happens in the admin panel.

### Device enrollment

The app is WebView-only. The foreground agent starts immediately and connects
without waiting for a profile. Operator name and phone number are optional
labels that can be added later. The device appears in the admin list without
approval. The app opens on the operator page; the top-right **Status** indicator
shows the agent connection state and opens relay, accessibility, notification,
SMS/phone, battery, and Device Owner status.

### Phase 1 relay + web console

```bash
cd web && npm install && npm run build
cd ../server && npm install && npm start
# open http://localhost:8787/
```

Open <http://localhost:8787/> and sign in with `RC_ADMIN_PASSWORD` (dev default
`admin`). The agent uses a hardcoded relay URL and shared `RC_DEVICE_TOKEN`;
debug builds default to the emulator, release builds require
`-PrcRelayUrl=wss://…/ws -PrcDeviceToken=…`. See
[`server/README.md`](server/README.md), [`web/README.md`](web/README.md), and
[`android/README.md`](android/README.md).

### Phase 2: macros, runs, recorder

The web panel is a clean light dashboard — a sidebar with section navigation
and device cards, a sticky topbar, readable status cards, large buttons, and a
right-side activity drawer:

- **Control** — apps, snapshot with hotspots, tap/type/scroll, back/home/
  recents, a composer that types into the focused field on the device
  (`Insert` = set text, `Insert + Enter` = set text and press the IME enter key),
  plus **Wake** and **Unlock** actions. Wake works while the screen is off.
  Unlock dismisses a swipe/no-security keyguard; a PIN/pattern/password still
  requires the person to enter it, unless the device is provisioned with no
  secure lock and Device Owner.
- **Phone** — SIM details and numbers, inbox messages with a compact **Money**
  band for the latest debit/credit/balance SMS, direct SMS sending, and
  carrier-USSD call forwarding (all calls, no answer, busy, unreachable, or a
  raw code). Requires the phone/SMS permissions on the agent.
- **Macros** — JSON macro editor, parameters, save/delete, run.
- **Recorder** — passive per-app recording: click **Record** on an app, then
  open that app on the device yourself. Leaving the app or pressing
  **Stop & save** saves the macro. Live text/tap/scroll feed, text-only
  filter, typed-text list with copy-all.
- **Runs** — run history with per-step status, timing, errors, and checkpoint
  screenshots.
- **Secrets** — write-only encrypted values for `secretRef` steps.

A sample macro **Settings — open Network & internet** is seeded by the relay.
Macros persist in `server/data/macros.json`.

### Phase 3: OTP, secrets, grid, drafts

- **OTP:** notification and SMS OTPs are captured on-device; `waitForOtp` consumes
  one. If none arrives, the admin panel shows an OTP prompt and the operator can
  type it.
- **Secrets:** add named secrets in the **Secrets** tab; use
  `"secretRef": "name"` in a `setText` step. Values are encrypted at rest and
  never returned to the browser.
- **Grid:** toggle **Grid** in the snapshot panel for a 6×10 coordinate grid.
- **Draft macro:** record a session in **Recorder**, then click **Draft macro**
  to get an editable macro skeleton.
- **Per-app recording:** click **Record** on an app row, then open the app on
  the device yourself and use it. Leaving the app or clicking **Stop & save**
  saves the macro automatically to **Macros**.


## Deployment

Full VPS/Coolify and signed-APK instructions are in
[**deployment_guide.md**](deployment_guide.md). Additional hardening notes are
in [`docs/production.md`](docs/production.md). The relay now has a shared admin
password and shared device token; use strong values and TLS. Per-device tokens
are still the recommended next hardening step.

```bash
# local container check
RC_SECRET_KEY="$(openssl rand -base64 48)" \
RC_ADMIN_PASSWORD="a-long-password" \
RC_DEVICE_TOKEN="a-long-shared-device-token" \
docker compose up --build
```
