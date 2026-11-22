# RC-app — Remote Control for Android

A lightweight, AnyDesk-style remote control system: view and control an Android
device from a web panel or admin app.

> **Status:** planning. The implementation options and recommended architecture
> are documented in [`docs/remote-control-report.md`](docs/remote-control-report.md).

## Quick summary

| Layer | Plan |
|---|---|
| Android agent | Kotlin — `MediaProjection` capture + `AccessibilityService` input |
| Transport | WebSocket + H.264 first, WebRTC + TURN for internet |
| Backend | Node.js/TypeScript, Postgres, Redis, coturn |
| Admin | Next.js web panel (PWA), native app later |

## Documents

- [Options & implementation report](docs/remote-control-report.md) — approaches,
  comparison, subsystem design, roadmap, risks.

## Getting started

Nothing is scaffolded yet. The recommended first step is **Phase 0** in the
report: a local spike that captures the screen and injects gestures on a real
device, before any networking work.
