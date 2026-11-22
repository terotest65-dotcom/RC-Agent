# RC Admin Panel (Phase 1)

Vite + React console for the relay.

## Dev

```bash
npm install
npm run dev
# open http://localhost:5173/
```

Vite proxies `/ws` and `/api` to `localhost:8787`, so the relay must be running.

## Build

```bash
npm run build
```

The relay serves `dist/` at <http://localhost:8787/>.

## Identity

There is no token. The agent derives a stable device ID from `ANDROID_ID` and
sends it in the `hello` message; the admin panel connects without credentials
and selects a device by ID. Keep the relay on localhost, the LAN, or a private
tailnet.

## Layout

Field console: a warm paper, ink and vermilion interface with a device rail
on the left and an editorial workspace on the right. The header carries the
device name in a serif display face, a telemetry strip replaces status pills,
and tabs are numbered **Control**, **Macros**, **Recorder**, **Runs**, and
**Secrets**. The event log opens as a bottom **Activity** drawer. Layout
collapses to a single column on narrow screens; device cards scroll
horizontally and tabs/telemetry fade at the edge. Fonts are self-hosted
(Instrument Sans, Instrument Serif, IBM Plex Mono).

## Control surface

- snapshot with numbered hotspot overlay; tap any hotspot to send `tap`
- element list for tap/type/scroll plus Back / Home / Recents
- **Composer**: type text in the panel and press **Insert** to set it into the
  focused field on the device (`inputText`), or **Insert + Enter** to set it
  and press the IME enter key.
- app inventory with per-app **Record** and **Open** actions
- device state chips (accessibility, screen, battery, Device Owner)
- capability probing still exists in the agent and relay, but the panel no
  longer exposes a probe tab.

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
- auth UI, TLS, per-device credentials
- persistent run history
