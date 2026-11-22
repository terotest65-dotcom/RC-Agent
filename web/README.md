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

## Phase 2 surface

- **Macros** tab: JSON editor, save/delete, per-macro parameter form, Run.
- **Recorder** tab: start/stop; live feed of text, taps, scrolls, and window
  changes; a **Text only** toggle to hide everything except typed text; a
  **Typed text** list with every field's final value and **Copy all**; masked
  bullet fields are cleaned and concatenated.
- **Runs** tab: run history with per-step status, timing, error, and checkpoint
  screenshot.
- Sample macro `Settings — open Network & internet` is seeded by the relay.

## Phase 3 surface

- **Secrets** tab: add/delete named secrets; values are write-only and encrypted
  at rest. Use `"secretRef": "name"` in a `setText` step.
- **Grid** toggle in the snapshot panel: 6×10 numbered cells, sends `tapXY`.
- **Draft macro** in the recorder: converts recorded taps/text into an editable
  macro draft.
- **Per-app recording:** click **Record** on any app row, then open the app on
  the device yourself. The panel listens while you use it; leaving the app or
  clicking **Stop & save** saves the macro automatically (launch, taps, text,
  scrolls, screenshot; masked text becomes a secret step).
- OTP prompt banner: when a `waitForOtp` step needs a human, enter the code in
  the panel and submit it.
- Recorder feeds now include app labels and view IDs for better drafts.

## Not yet

- OCR fallback
- auth UI, TLS, per-device tokens
- persistent run history
