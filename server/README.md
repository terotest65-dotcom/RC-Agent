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

Open <http://localhost:8787/>.

Environment:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | HTTP + WebSocket port |
| `WEB_DIST` | `../web/dist` | Static web build |

## Device URL

- Emulator: `ws://10.0.2.2:8787/ws`
- Real phone on the same network: `ws://<host-ip>:8787/ws`
- Put it behind Tailscale/WireGuard for remote devices; do not expose the
  relay to the internet.

## Phase 3: OTP, secrets, grid

- **OTP:** the agent's notification listener and SMS receiver push codes into an
  in-memory 5-minute buffer. A `waitForOtp` macro step consumes the code; if
  none arrives, the agent sends a `prompt` and the web panel asks the operator.
  The reply is sent as a `promptResult` command.
- **Secrets:** values are AES-256-GCM encrypted in `secrets.json` and never
  returned by the API. A `setText` step can use `"secretRef": "name"`; the relay
  decrypts and injects the value only for that run. Set `RC_SECRET_KEY` before
  first use; the default is development-only.
- **Grid:** the web panel can overlay a 6×10 numbered grid and send `tapXY`.
- **Draft macro:** the recorder can turn recorded taps/text into a macro draft.
- **Per-app recording:** click **Record** on an app row, then open the app on
  the device yourself. The relay listens while you use it; **Stop & save** or
  leaving the app stops and saves the macro automatically. The relay builds the
  macro with launch, taps, text (masked text becomes a `secret` step), scrolls,
  and a final screenshot. Events are filtered to the target app plus related
  packages (for example Settings and its search provider).

## Endpoints

| Path | Purpose |
|---|---|
| `GET /api/health` | status + counts |
| `GET /api/devices` | device summaries (apps, state, probes) |
| `GET /api/macros` | saved macros |
| `GET /api/runs` | last 20 run reports |
| `GET /api/secrets` | secret names/metadata only, never values |
| `WS /ws` | device and admin socket |
| `GET /` | web panel (SPA) |

## Phase 2: macros, runs, recorder

- Macros are stored in `server/data/macros.json`. A sample
  `Settings — open Network & internet` macro is seeded on first start.
- The admin sends `macroSave` / `macroDelete` / `macroList`; the server
  broadcasts `macros` and `macroSaved`.
- A `cmd` with `action: "runMacro"` gets a server-generated `runId`; the
  server forwards the macro definition and params to the device, then relays
  `runStarted`, `runStep`, and `runFinished` events.
- Run steps include checkpoint snapshots (base64 WebP) and are kept in memory
  for the last 20 runs.
- Recorder commands (`startRecording` / `stopRecording`) make the agent stream
  `recordEvent` messages: text changes, taps, scrolls, and window changes.
  Text events include the app, current text, and the inserted delta; password
  fields are redacted.

## Protocol

Hello:

```json
{ "t": "hello", "role": "device", "deviceId": "and_…",
  "name": "Pixel 7", "model": "Pixel 7", "android": 35, "agent": "1.0.0" }
```

Device → relay: `apps`, `state`, `snapshot`, `probe`, `actionResult`,
`telephony`, `smsList`, `smsSendResult`, `ussdResult`.
Admin → relay: `cmd` (`snapshot`, `launch`, `tap`, `tapXY`, `setText`,
`inputText`, `scroll`, `globalAction`, `probe`, `probeTargets`, `listApps`,
`telephonyInfo`, `smsList`, `smsSend`, `callForward`, `wake`, `unlock`).
`inputText` takes `{ text, submit }`: the agent focuses the editable field (or
the first editable node on screen), applies `ACTION_SET_TEXT`, optionally
presses IME enter, then sends a fresh snapshot.

Telephony commands:

- `telephonyInfo` → agent replies with `telephony`
  (`sims`, `permissions`, `error`). The relay caches it on the device summary.
- `smsList` with `limit` → agent replies with `smsList`
  (`messages`, `error`). Inbox only.
- `smsSend` with `{ to, body, subId }` → agent replies with `smsSendResult`
  and `actionResult`. `subId = -1` uses the default line.
- `callForward` with `{ code, subId }` → agent sends the USSD code and replies
  with `ussdResult` when the network responds (20 s timeout). Standard GSM
  forwarding codes: `*21*<number>#` all calls, `*61*` no answer, `*67*` busy,
  `*62*` unreachable; `#21#` disables, `*#21#` checks status.

Snapshots carry a base64 WebP image in Phase 1. Move to binary frames or HTTP
URLs when the image volume grows.

## Security

- No token or login: the relay trusts whoever can reach the WebSocket. Device
  identity is the generated device ID (`and_…`) shown in the agent.
- Telephony snapshots (phone numbers, SMS bodies, USSD responses) are held in
  memory and broadcast to admins. Treat the relay and its network as sensitive.
- Keep it on localhost, the LAN, or Tailscale/WireGuard; do not expose it.
- Before any real deployment: TLS, per-device credentials, admin accounts +
  TOTP, and a reverse proxy.
