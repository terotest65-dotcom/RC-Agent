# RC Relay (Phase 1)

A single Node/TypeScript process that:

- accepts device WebSocket connections and keeps their inventory/state,
- accepts admin WebSocket connections and broadcasts device events,
- forwards admin commands to devices,
- serves the built web panel from `../web/dist`.

No database yet: device state is in memory for Phase 1. In-memory is enough for
1–5 devices; SQLite comes with the macro runner.

## Run

```bash
# from the repo root
cd web && npm install && npm run build && cd ../server
npm install
npm start
```

Open <http://localhost:8787/?token=dev>.

Environment:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | HTTP + WebSocket port |
| `RC_TOKEN` | `dev` | Shared device/admin token |
| `WEB_DIST` | `../web/dist` | Static web build |

## Device URL

- Emulator: `ws://10.0.2.2:8787/ws`
- Real phone on the same network: `ws://<host-ip>:8787/ws`
- Put it behind Tailscale/WireGuard for remote devices; do not expose `ws://`
  or the `dev` token to the internet.

## Endpoints

| Path | Purpose |
|---|---|
| `GET /api/health` | `{ok, devices, online, admins, uptime}` |
| `GET /api/devices` | Device summaries (apps, state, probes) |
| `WS /ws` | Device and admin socket |
| `GET /` | Web panel (SPA) |

## Protocol

Hello:

```json
{ "t": "hello", "role": "device", "token": "dev", "deviceId": "and_…",
  "name": "Pixel 7", "model": "Pixel 7", "android": 35, "agent": "1.0.0" }
```

Device → relay: `apps`, `state`, `snapshot`, `probe`, `actionResult`.
Admin → relay: `cmd` (`snapshot`, `launch`, `tap`, `tapXY`, `setText`, `scroll`,
`globalAction`, `probe`, `probeTargets`, `listApps`).

Snapshots carry a base64 WebP image in Phase 1. Move to binary frames or HTTP
URLs when the image volume grows.

## Security

- Shared token only, cleartext `ws://`, no TLS yet.
- Local/Tailscale use only.
- The web panel has no login screen; `?token=` is the only admin credential.
- Before any real deployment: TLS, per-device tokens, admin accounts + TOTP,
  and a reverse proxy.
