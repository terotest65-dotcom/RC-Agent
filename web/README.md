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

## Login and flow

The panel opens on a shared-password login page (`RC_ADMIN_PASSWORD`; dev default
`admin`). After login you see every enrolled device, online or offline, with its
operator. Clicking a device opens its single-device dashboard; other devices are
not shown in the working view. **All devices** / **Switch device** returns to
the list. **Log out** clears the session cookie.

The agent connects with a shared `RC_DEVICE_TOKEN` and appears automatically
after it enrolls; there is no approval step.

## Layout

Login → device picker → one-device dashboard. The dashboard has a white
sidebar with section navigation and the current device, a sticky topbar with
the page title/actions, a status-card row, and white bordered cards on a soft
gray background. Sections are **Control**, **Phone**, **Macros**, **Recorder**,
**Runs**, and **Secrets**. The event log opens as a right-side **Activity**
drawer. The device picker shows all enrolled devices, online or offline. Layout
adapts on narrow screens: navigation wraps, device tiles stack, and buttons
stretch for touch. Fonts are self-hosted Figtree and IBM Plex Mono.

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

The agent sends the operator name and phone from its WebView setup form. The
admin shows **Registered to <name> · <phone>** beneath the device title and on
the device tile. There is no approval step or separate operator login; the
profile is informational.

## Phone surface

- **Lines** — SIM cards with number, carrier, slot, country/MCC-MNC, state,
  signal, data, roaming, and masked ICCID. Agent permission states are shown
  inline.
- **Call forwarding** — pick a line and rule (all calls, no answer, busy,
  unreachable), enter the destination, then Enable / Disable / Status. A raw
  USSD field is available for carrier-specific codes. The network response is
  shown next to the form; exact behavior is carrier-dependent.
- **Messages** — inbox ledger with expandable bodies and SIM slot. The agent
  pushes an updated inbox when a new SMS arrives, so the list, send composer,
  and count update without a manual refresh. A compact **Money** band above
  the list parses the latest bank/payment SMS into Debit, Credit, and Balance
  entries with source, account last-4, time, and the latest known balance.
  Parsing is heuristic and read-only.
- **Send SMS** — pick a line, enter a recipient and body, send directly from
  the device. Results appear next to the form.

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
