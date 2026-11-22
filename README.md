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
# open http://localhost:8787/?token=dev
```

On the device, open **RC Agent**, keep the relay URL
(`ws://10.0.2.2:8787/ws` on an emulator, `ws://<host-ip>:8787/ws` on a phone),
and tap **Save & connect**. See [`server/README.md`](server/README.md) and
[`web/README.md`](web/README.md).

### Phase 2: macros, runs, recorder

The web panel now has four tabs:

- **Control** — apps, probe, snapshot, tap/type/scroll.
- **Macros** — JSON macro editor, parameters, save/delete, run.
- **Recorder** — start/stop recording and watch live text input, taps, scrolls,
  and window changes per app. Text is captured from accessibility text-change
  events, so typing `hi` in a chatbox shows the app and `hi`; password fields
  are redacted.
- **Runs** — run history with per-step status, timing, errors, and checkpoint
  screenshots.

A sample macro **Settings — open Network & internet** is seeded by the relay.
Macros persist in `server/data/macros.json`.
