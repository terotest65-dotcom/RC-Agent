# RC Relay

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

Open <http://localhost:8787/> and sign in.

Development defaults: admin password `admin`, device token `dev-device`.
Production refuses to start without strong values.

Environment:

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8787` | HTTP + WebSocket port |
| `RC_ADMIN_PASSWORD` | `admin` (dev) | Shared admin password; required in production |
| `RC_DEVICE_TOKEN` | `dev-device` (dev) | Shared device token; required in production |
| `RC_PUBLIC_ORIGIN` | unset | Exact public origin, e.g. `https://rc.example.com` |
| `RC_SECRET_KEY` | dev key | AES key for secrets; required 32+ chars in production |
| `WEB_DIST` | `../web/dist` | Static web build |
| `DATA_DIR` | `../data` | Macros/secrets storage; use `/data` in Docker |

## Authentication

- **Admin:** `POST /api/login` with the shared password sets an HttpOnly,
  SameSite cookie. `/api/*` (except health/login/session) and admin WebSocket
  connections require that session. The admin UI is at `/`; static assets are
  public but contain no data.
- **Device:** the agent sends the shared `RC_DEVICE_TOKEN` in its `hello`
  message. A device without the token is rejected.
- **Origins:** admin WebSocket connections must match `RC_PUBLIC_ORIGIN` when
  set, otherwise the request host.
- Login attempts are rate-limited in memory. Sessions expire after 12 hours.

## Device URL

- Emulator/debug: `ws://10.0.2.2:8787/ws`
- Production: `wss://rc.example.com/ws` behind Coolify/TLS. Do not use `ws://`
  over the internet.

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
| `POST /api/login` | shared-password login, sets session cookie |
| `POST /api/logout` | clear the session |
| `GET /api/session` | session check |
| `GET /api/health` | status + counts (public for Docker healthcheck) |
| `GET /api/devices` | device summaries (auth required) |
| `GET /api/macros` | saved macros (auth required) |
| `GET /api/runs` | last 20 run reports (auth required) |
| `GET /api/secrets` | secret names/metadata only, never values (auth required) |
| `WS /ws` | device and admin socket |
| `GET /` | web panel / login page |

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
{ "t": "hello", "role": "device", "token": "<RC_DEVICE_TOKEN>",
  "deviceId": "and_…", "name": "Pixel 7", "model": "Pixel 7",
  "android": 35, "agent": "1.0.0" }
```

Device → relay: `apps`, `state`, `snapshot`, `probe`, `actionResult`,
`profile`, `telephony`, `smsList`, `smsSendResult`, `ussdResult`.
Admin → relay: `cmd` (`snapshot`, `launch`, `tap`, `tapXY`, `setText`,
`inputText`, `scroll`, `globalAction`, `probe`, `probeTargets`, `listApps`,
`telephonyInfo`, `smsList`, `smsSend`, `callForward`, `wake`, `unlock`,
`setDevicePin`, `clearDevicePin`, `setAppUnlock`, `clearAppUnlock`, `appUnlock`).
`setDevicePin` takes `{ pin, fallback }`; the value may be a numeric PIN,
an alphanumeric password, or a pattern dot sequence (1–9 row-major). The agent
stores it encrypted on the device and reports only `devicePin: true`/`pinFallback`
in `state`. The relay never persists it. `unlock` uses that credential for a
secure keyguard, then falls back to `requestDismissKeyguard` when none is
stored.
`setAppUnlock` takes `{ package, secret }`; `clearAppUnlock` takes `{ package }`.
`appUnlock` targets the agent's current foreground package by default (an
optional `{ package }` overrides it), taps the app lock's
Use password/PIN/pattern option, and enters the per-app credential or the
device credential. Per-app values are encrypted on the device and the relay
never persists them; `state.appUnlockPackages` lists only the package names.
`inputText` takes `{ text, submit }`: the agent focuses the editable field (or
the first editable node on screen), applies `ACTION_SET_TEXT`, optionally
presses IME enter, then sends a fresh snapshot.

`profile` carries `{ name, phone, consentAt, termsVersion }` from the agent's
name/phone setup form (`consentAt` is the registration timestamp). The relay caches it on the device summary and broadcasts it to
admins.

Telephony commands:

- `telephonyInfo` → agent replies with `telephony`
  (`sims`, `permissions`, `error`). The relay caches it on the device summary.
- `smsList` with `limit` → agent replies with `smsList`
  (`messages`, `error`). Inbox only. The agent also pushes an unsolicited
  `smsList` after an incoming SMS, which keeps the admin inbox live.
- `smsSend` with `{ to, body, subId }` → agent replies with `smsSendResult`
  and `actionResult`. `subId = -1` uses the default line.
- `callForward` with `{ code, subId }` → agent sends the USSD code and replies
  with `ussdResult` when the network responds (20 s timeout). Standard GSM
  forwarding codes: `*21*<number>#` all calls, `*61*` no answer, `*67*` busy,
  `*62*` unreachable; `#21#` disables, `*#21#` checks status.

Snapshots carry a base64 WebP image in Phase 1. Move to binary frames or HTTP
URLs when the image volume grows.

## Security

- Shared admin password and shared device token only. Use strong random values,
  terminate TLS at Coolify, and set `RC_PUBLIC_ORIGIN`.
- The device token is embedded in the APK and can be extracted. Per-device
  enrollment tokens are the next hardening step for stronger identity and
  revocation.
- Telephony snapshots (phone numbers, SMS bodies, USSD responses) are held in
  memory and broadcast to admins. Treat the relay and its network as sensitive.
- Run history and device profiles are in memory; persist and back them up for
  production.
- Before broader exposure: per-device credentials, admin accounts + TOTP,
  audit logs, and a reverse proxy/access layer.
