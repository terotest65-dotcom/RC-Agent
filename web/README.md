# RC Admin Panel (Phase 1)

Vite + React console for the relay.

## Dev

```bash
npm install
npm run dev
# open http://localhost:5173/?token=dev
```

Vite proxies `/ws` and `/api` to `localhost:8787`, so the relay must be running.

## Build

```bash
npm run build
```

The relay serves `dist/` at <http://localhost:8787/>.

## Token

The token comes from `?token=...`, then `localStorage.rc_token`, then `dev`.
It is sent in the WebSocket `hello` message, not as a header.

## Phase 1 surface

- device list with online/offline state
- device state chips (accessibility, screen, battery, Device Owner)
- app inventory and per-app probe
- `Probe v1 apps`
- snapshot with numbered hotspot overlay
- tap a hotspot, tap/type/scroll from the element list
- Back / Home / Recents
- event log

## Not yet

- macro editor / runner
- run history and reports
- OTP prompt
- auth UI, TLS, per-device tokens
- grid fallback and OCR
