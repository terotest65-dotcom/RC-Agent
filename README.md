# RC-app — Remote Control for Android

An internal tool to control Android devices from a web panel: list installed
apps, run predefined macros, and verify each step with a screenshot. Manual
control uses a screenshot annotated with numbered hotspots — the admin replies
with a number and the device taps that element.

> **Status:** planning. The design is in
> [`docs/step-control-design.md`](docs/step-control-design.md).

## Approach

| Layer | Plan |
|---|---|
| Android agent | Kotlin — `AccessibilityService` tree + `takeScreenshot` + gesture/text actions |
| Control model | Step-based: action → screenshot → verify (no live video) |
| Macros | JSON step lists with selectors, checkpoints, assertions |
| Transport | Single WebSocket relay (WSS); no WebRTC/TURN |
| Backend | Node.js/TypeScript, Postgres |
| Admin | Next.js web panel |

Optional: an ADB-based lab controller for devices on a bench (no app install).

## Documents

- [**Step-based design (current)**](docs/step-control-design.md) — app inventory,
  numbered snapshots, macros, protocol, roadmap.
- [Original options report](docs/remote-control-report.md) — the earlier
  live-streaming exploration; kept for reference.

## Getting started

Nothing is scaffolded yet. The recommended first step is **Phase 0** in the
design doc: list apps, launch one, and capture a screenshot with a hotspot map
on two real devices.
