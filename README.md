# RC-app — Remote Control for Android

An internal tool to run predefined macros on Android devices from a web panel:
launch an app, execute a macro, and get a report with checkpoint screenshots.
A numbered-screenshot console is available for debugging and repair.

> **Status:** design locked. See
> [`docs/step-control-design.md`](docs/step-control-design.md).

## Scope (v1)

- **Targets:** our own banking/payment apps that permit screenshots and control,
  messaging/social apps via UI automation, and device settings.
- **Devices:** 1–5 remote phones, Android 13+.
- **Mode:** fully automatic; human fallback only for OTPs and macro repair.
- **OTP:** SMS or notification, with manual input fallback.
- **Admin:** web panel only; no live video (screenshot per step).

## Approach

| Layer | Plan |
|---|---|
| Android agent | Kotlin — `AccessibilityService` tree + `takeScreenshot` + actions |
| Execution | On-device macro engine with preflight, assertions, offline buffering |
| Recording | Capture user taps/text/scroll/window changes; hardware keys where possible |
| Transport | Single WebSocket relay (WSS); `specialUse` foreground service |
| Backend | Node.js/TypeScript + SQLite (or Postgres); one process |
| Admin | Next.js — run dashboard, report, macro editor, assist console |

Optional later: an ADB-based lab controller for devices on a bench.

## Documents

- [**Design (current)**](docs/step-control-design.md) — decisions, architecture,
  macros, OTP, recorder, protocol, roadmap.
- [Original options report](docs/remote-control-report.md) — the earlier
  live-streaming exploration; kept for reference.

## Getting started

Nothing is scaffolded yet. Start with **Phase 0**: list apps, launch one, and
capture a settled screenshot with a hotspot map on two real devices. The
biggest reliability lever is adding stable `testTag`/`viewId` values to our own
apps.
