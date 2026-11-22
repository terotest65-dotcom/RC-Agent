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
3. Optional: keep the device unlocked and charging. For a headless fleet,
   provision Device Owner and call `setKeyguardDisabled`.

### Enable via ADB (development only)

```bash
adb shell settings put secure enabled_accessibility_services \
  com.acme.rc/com.acme.rc.accessibility.RcAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

### Device Owner (optional)

The device must have **no accounts** (factory reset), then:

```bash
adb shell dpm set-device-owner com.acme.rc/.admin.RcDeviceAdminReceiver
```

Without Device Owner the app still works; it only loses the automatic
keyguard disable and keep-awake helpers.

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
- No networking yet: this is a local debug console, not the remote control plane.
- The app UI and the target app cannot both be foreground on the same device;
  the real tap path runs from the backend while the target app is foreground.

## Next (Phase 1)

- Backend WebSocket relay and run model.
- Web admin panel: device list, app list, snapshot console, run report.
- Server-driven tap/text/scroll/back commands with a per-device lease.
- Grid fallback for `partial` apps.
