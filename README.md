# RC-app — Remote Control for Android

An internal tool to run predefined macros on Android devices from a web panel:
launch an app, execute a macro, and get a report with checkpoint screenshots.
A numbered-screenshot console is available for debugging and repair.

> **Status:** design locked. See
> [`docs/step-control-design.md`](docs/step-control-design.md).

## Scope (v1)

- **Targets:** a curated set of common Indian apps that pass a **capability probe**
  (screenshots work + a usable accessibility tree). No real payments in v1.
- **Devices:** 1–5 remote phones, Android 13+.
- **Mode:** fully automatic and on demand; human fallback for OTPs and macro repair.
- **OTP:** SMS or notification, with manual input fallback.
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

Nothing is scaffolded yet. Start with **Phase 0**: list apps, launch one, capture
a settled screenshot with a hotspot map, and run the **capability probe** over a
shortlist of candidate apps on two real devices. The probe tells you which apps
are worth automating — don't guess from the app store listing.
