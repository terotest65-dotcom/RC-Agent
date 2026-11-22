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
| Backend | Node.js/TypeScript + SQLite (or Postgres); one process, on demand |
| Admin | Next.js — run dashboard, report, macro editor, assist console |

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

See [`android/README.md`](android/README.md) for install, accessibility, and
Device Owner setup. Start with **Phase 0**: list apps, launch one, capture a
settled screenshot, and run the **capability probe** over the six v1 apps. The
probe tells you which apps are worth automating — don't guess from the app store
listing.

### Phase 1 relay + web console

```bash
cd web && npm install && npm run build
cd ../server && npm install && npm start
# open http://localhost:8787/
```

On the device, open **RC Agent**, keep the relay URL
(`ws://10.0.2.2:8787/ws` on an emulator, `ws://<host-ip>:8787/ws` on a phone),
and tap **Save & connect**. See [`server/README.md`](server/README.md) and
[`web/README.md`](web/README.md).

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
- **Phone** — SIM details and numbers, inbox messages, direct SMS sending, and
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
