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

Clean light dashboard: a white sidebar with section navigation and device
cards, a sticky topbar with the page title and actions, a status-card row, and
white bordered cards on a soft gray background. Sections are **Control**,
**Phone**, **Macros**, **Recorder**, **Runs**, and **Secrets**. The event log
opens as a right-side **Activity** drawer. Layout adapts on narrow screens:
the sidebar becomes a two-row navigation with horizontally scrolling device
cards, and actions/buttons stretch for touch. Fonts are self-hosted Figtree
and IBM Plex Mono.

## Control surface

- snapshot with numbered hotspot overlay; tap any hotspot to send `tap`
- element list for tap/type/scroll plus Back / Home / Recents
- **Composer**: type text in the panel and press **Insert** to set it into the
  focused field on the device (`inputText`), or **Insert + Enter** to set it
  and press the IME enter key.
- **Wake / Unlock** in the topbar: Wake turns the screen on while asleep.
  Unlock dismisses a swipe/None keyguard; a secure PIN/pattern/password still
  needs to be entered on the device.
- app inventory with per-app **Record** and **Open** actions
- device state chips (accessibility, screen, battery, Device Owner)
- capability probing still exists in the agent and relay, but the panel no
  longer exposes a probe tab.

## Registration

The agent sends the operator name, phone, and consent time from its first-run
WebView form. The admin shows **Registered to <name> · <phone>** beneath the
device title. There is no separate login; the profile is informational.

## Phone surface

- **Lines** — SIM cards with number, carrier, slot, country/MCC-MNC, state,
  signal, data, roaming, and masked ICCID. Agent permission states are shown
  inline.
- **Messages** — inbox ledger with expandable bodies and SIM slot; **Refresh**
  re-reads the device.
- **Send SMS** — pick a line, enter a recipient and body, send directly from
  the device. Results appear next to the form.
- **Call forwarding** — pick a line and rule (all calls, no answer, busy,
  unreachable), enter the destination, then Enable / Disable / Status. A raw
  USSD field is available for carrier-specific codes. The network response is
  shown next to the form; exact behavior is carrier-dependent.

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
