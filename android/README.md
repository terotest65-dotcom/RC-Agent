# RC Agent — Android (Phase 0)

The device-side agent. Phase 0 proves the core primitive: list apps, launch one,
capture a settled screenshot, extract numbered hotspots from the accessibility
tree, and classify each app with a capability probe.

## Requirements

- JDK 17+ (JDK 21 works)
- Android SDK platform 35 and build-tools 35
- A device or emulator on **Android 13+ (API 33+)**
- `adb` on `PATH`, or use `~/Android/Sdk/platform-tools/adb`

## Build

```bash
cd android
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.acme.rc/.MainActivity
```

## Enable the agent

1. Open **Settings → Accessibility → RC Agent control** and enable it.
   - On Android 13+, a sideloaded APK may show **Restricted setting**. Open
     **App info → ⋮ → Allow restricted settings**, then enable Accessibility.
2. Optional: **Settings → Notification access → RC Agent OTP reader**.
3. Grant SMS and phone permissions when prompted: read/send SMS, phone state,
   phone numbers, and calls. These enable the admin **Phone** tab.
4. Optional: keep the device unlocked and charging. For a headless fleet,
   provision Device Owner and call `setKeyguardDisabled`.

### Enable via ADB (development only)

```bash
adb shell settings put secure enabled_accessibility_services \
  com.acme.rc/com.acme.rc.accessibility.RcAccessibilityService
adb shell settings put secure accessibility_enabled 1

# Telephony permissions (the runtime dialog may be hidden for sideloaded apps)
for p in READ_SMS SEND_SMS READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE; do
  adb shell pm grant com.acme.rc android.permission.$p
done
```

### Device Owner (optional)

The device must have **no accounts** (factory reset), then:

```bash
adb shell dpm set-device-owner com.acme.rc/.admin.RcDeviceAdminReceiver
```

Without Device Owner the app still works; it only loses the automatic
keyguard disable and keep-awake helpers.

## First-run enrollment

The first launch shows a local WebView (`assets/onboarding.html`) with the
agent description, operator name, phone number, and a required consent
checkbox. On submit, the details are saved in `SharedPreferences` and sent to
the relay as a `profile` message; the admin panel displays them under the
device name. **Edit profile** in the app reopens the prefilled form.

After enrollment, the app asks for the optional SMS/phone permissions and
shows an **Enable accessibility** dialog if the service is off. Use **Open
settings** to jump to the accessibility screen. The app first tries
`android.settings.ACCESSIBILITY_DETAILS_SETTINGS` with the service component
(works on many builds) and falls back to the standard accessibility page. On
On most phones the service is under **Downloaded apps**, **Installed apps**,
or **Installed services**; the label varies by manufacturer. If the app was
sideloaded, allow restricted settings from **App info → ⋮** first.

## Wake and unlock

- **Wake** uses a bright-screen wake lock with `ACQUIRE_CAUSES_WAKEUP`
  (`WAKE_LOCK` permission) and works while the screen is off.
- **Unlock** opens a transparent activity over the keyguard and calls
  `KeyguardManager.requestDismissKeyguard`:
  - A **swipe/None** lock is dismissed without interaction.
  - A **PIN/pattern/password** lock shows the normal lock screen; the person
    must enter it. This is an Android security boundary and a normal app
    cannot bypass it.
  - On a **Device Owner** device with the screen lock set to None, the agent
    can remove the keyguard with `setKeyguardDisabled`; a secure lock is not
    bypassable.
- For unattended runs, provision a dedicated device with no secure lock (and
  ideally Device Owner). Otherwise add a human unlock step to the workflow.

## Telephony: SIM, SMS, call forwarding

The agent exposes phone capabilities to the admin **Phone** tab:

- **Lines** — active subscriptions with carrier, slot, phone number, country,
  MCC/MNC, SIM state, signal (dBm/level), data state, roaming, and a masked
  ICCID. Numbers depend on `READ_PHONE_NUMBERS` and whether the carrier wrote
  the number to the SIM; many SIMs return nothing.
- **Messages** — the 50 newest inbox messages (`READ_SMS`), address, body,
  timestamp, and SIM slot.
- **Send SMS** — direct `SmsManager.sendMultipartTextMessage` on the selected
  subscription (`SEND_SMS`). Results are queued, not delivery-confirmed.
- **Call forwarding** — carrier USSD through `TelephonyManager.sendUssdRequest`
  (`CALL_PHONE`), with presets for all calls, no answer, busy, and unreachable,
  plus a raw USSD field. The carrier decides whether the code is accepted and
  what it returns. Some carriers show a confirmation dialog on the device.
  Codes follow the standard GSM MMI scheme (`*21*<number>#`, `#21#`,
  `*#21#`, and the 61/62/67 variants). The agent times out after 20 seconds.
  No hidden `MODIFY_PHONE_STATE` APIs are used.

Everything is best-effort: the admin shows each permission state, and missing
permissions produce a readable error instead of a silent failure. The emulator
may report a fake T-Mobile line; sending an SMS to that same number loops the
message back into the inbox.

## What Phase 0 does

- Lists launcher apps via `LauncherApps`.
- Starts an app with a foreground wait, then captures a settled screenshot.
- Walks the active window's accessibility tree and extracts interactive nodes.
- Renders a numbered overlay and a readable element list.
- Probes each app and classifies it `controllable` / `partial` / `blocked`.
- "Probe v1 apps" runs the probe over the six target apps that are installed.
- "Tap" replays a best-effort tap: relaunch the app, tap the stored element,
  capture the result.

## Known Phase 0 limits

- Hotspot grouping is heuristic. Some clickable containers still group adjacent
  rows; the labels are correct, but box bounds can be coarse.
- Chrome exposes very few nodes on its first-run screen (`partial`); Settings is
  a good `controllable` reference.
- Replay taps use the current app layout; if the app opens on a different screen,
  the coordinate may miss. The real controller taps while the target app is
  already foreground.
- Network control now lives in the relay; the local console remains useful for
  onboarding and firmware checks.
- The app UI and the target app cannot both be foreground on the same device;
  the real tap path runs from the backend while the target app is foreground.

## Next (Phase 1)

- Backend WebSocket relay and run model.
- Web admin panel: device list, app list, snapshot console, run report.
- Server-driven tap/text/scroll/back commands with a per-device lease.
- Grid fallback for `partial` apps.
