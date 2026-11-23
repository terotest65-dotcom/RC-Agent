# RC Agent — Android

The device-side agent. The app UI is a local WebView for enrollment and
permission status; all remote control happens from the admin panel. A
foreground service maintains the WebSocket and executes commands.

## Requirements

- JDK 17+ (JDK 21 works)
- Android SDK platform 35 and build-tools 35
- A device or emulator on **Android 13+ (API 33+)**
- `adb` on `PATH`, or use `~/Android/Sdk/platform-tools/adb`

## Build

Debug (uses `ws://10.0.2.2:8787/ws` and the dev token):

```bash
cd android
./gradlew :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

Release (hardcoded public relay and shared device token):

```bash
export RC_RELAY_URL='wss://rc.example.com/ws'
export RC_DEVICE_TOKEN='<the same RC_DEVICE_TOKEN as the server>'
../scripts/build-release.sh
```

`android/keystore.properties` (gitignored) points at the release keystore; when
present, `assembleRelease` produces the signed
`app/build/outputs/apk/release/app-release.apk`. Copy
`keystore.properties.example` on a new machine. Release builds fail if the URL
is not `wss://` or the token is still the dev default. See
[`../deployment_guide.md`](../deployment_guide.md) and
[`../docs/production.md`](../docs/production.md).

`android:usesCleartextTraffic` is disabled. The network security config allows
cleartext only for `10.0.2.2`/`localhost` in development.

## Install and enroll

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.acme.rc/.MainActivity
```

The foreground service starts immediately on launch and connects with the
hardcoded relay URL and shared device token. The device appears automatically
in the admin device list. There is no relay URL field and no approval step.

The WebView shows status and a **Permissions** panel. Operator **name and phone
number are optional**: they only label the device in the admin panel and can be
added later from the **Operator details** card.

The top-right **Permissions** chip opens the status panel:

- Relay connection
- Accessibility (required for screen control)
- Notification access (OTP/notification reads)
- SMS and phone permissions (Phone tab, OTP fallback)
- Battery/stay-awake status
- Device Owner (optional)

The app starts the service on launch when a profile exists, and `BootReceiver`
attempts to start it after reboot. Android may restrict boot-time foreground
service starts; opening the app retries.

## Enable accessibility

Use **Permissions → Accessibility → Open**. The app tries
`android.settings.ACCESSIBILITY_DETAILS_SETTINGS` with the service component and
falls back to the standard accessibility page. The exact list label varies:
**Downloaded apps**, **Installed apps**, or **Installed services**. On Android
13+, a sideloaded app may need **App info → ⋮ → Allow restricted settings**
first.

ADB development shortcut:

```bash
adb shell settings put secure enabled_accessibility_services \
  com.acme.rc/com.acme.rc.accessibility.RcAccessibilityService
adb shell settings put secure accessibility_enabled 1

for p in READ_SMS SEND_SMS READ_PHONE_STATE READ_PHONE_NUMBERS CALL_PHONE; do
  adb shell pm grant com.acme.rc android.permission.$p
done
adb shell cmd notification allow_listener \
  com.acme.rc/com.acme.rc.otp.RcNotificationListener
```

## Device Owner (optional)

The device must have no accounts (factory reset), then:

```bash
adb shell dpm set-device-owner com.acme.rc/.admin.RcDeviceAdminReceiver
```

For unattended runs, use Device Owner, set the screen lock to **None**, keep the
device charging, and disable battery optimization.

## Wake and unlock

- **Wake** uses a bright-screen wake lock with `ACQUIRE_CAUSES_WAKEUP` and works
  while the display is asleep.
- **Unlock** opens an invisible activity over the keyguard and calls
  `KeyguardManager.requestDismissKeyguard`.
  - Swipe/None locks dismiss without interaction.
  - A PIN/pattern/password shows the normal lock screen; the person must enter
    it. A normal app cannot bypass a secure lock.
  - Device Owner with screen lock None can remove the keyguard.
- For unattended runs, use no secure screen lock.

## Telephony

- **Lines** — carrier, slot, number, country, MCC/MNC, state, signal, data,
  roaming, masked ICCID.
- **Messages** — newest 50 inbox messages; the SMS receiver pushes a fresh
  inbox to the admin on every incoming SMS.
- **Send SMS** — direct `SmsManager` send on the selected subscription.
- **Call forwarding** — carrier USSD through `TelephonyManager.sendUssdRequest`
  with presets and a raw code. Carrier-dependent; 20-second timeout.
- **Money** parsing happens in the admin panel, not on the device.

## Architecture

- `MainActivity` hosts `assets/agent.html`; there is no native Compose console.
- A JavaScript bridge exposes status, profile save, runtime permission request,
  accessibility/notification/battery settings, and app info.
- `AgentService` runs the WebSocket, macro engine, recorder, OTP capture, SMS,
  telephony, wake/unlock, and foreground notification.
- Relay URL and shared device token come from `BuildConfig` (`rcRelayUrl`,
  `rcDeviceToken` Gradle properties or `RC_RELAY_URL` / `RC_DEVICE_TOKEN`
  environment variables).
- No persistence beyond `SharedPreferences` for the operator profile.
