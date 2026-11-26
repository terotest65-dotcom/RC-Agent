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

On first launch the app runs a short setup flow before the agent starts:

1. A dialog asks for **accessibility**, with an **Open settings** deeplink.
2. Once accessibility is enabled, the app requests **notification, SMS, phone,
   and call** permissions.
3. The foreground service then starts and connects with the hardcoded relay URL
   and shared device token.

The device appears automatically in the admin device list. There is no relay URL
field and no approval step.

After setup, the WebView opens directly on the **Operator details** card.
Operator **name and phone number are optional**: they only label the device in
the admin panel and can be added later.

The top-right **Status** indicator reflects the agent state and opens the status
and permissions panel:

- Relay connection
- Accessibility (required for screen control)
- Notification access (OTP/notification reads)
- SMS and phone permissions (Phone tab, OTP fallback)
- Battery/stay-awake status
- Device Owner (optional)

After setup, the app starts the service on every launch, and `BootReceiver`
attempts to start it after reboot. Android may restrict boot-time foreground
service starts; opening the app retries (and runs setup again if accessibility
was cleared by an update).

## Enable accessibility

Use **Status → Accessibility → Open**. The app tries
`android.settings.ACCESSIBILITY_DETAILS_SETTINGS` with the service component and
falls back to the standard accessibility page. The exact list label varies:
**Downloaded apps**, **Installed apps**, or **Installed services**. On Android
13+, a sideloaded app may need **App info → ⋮ → Allow restricted settings**
first.

> **After every APK install/update, Android turns the accessibility service off.**
> Re-enable **RC Agent control** in Settings → Accessibility, otherwise capture,
> taps, and recording will not work and the admin panel will show accessibility
> as off.

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
  - With a **Device unlock** credential stored from the admin panel or enrolled
    on the device, the agent wakes the screen and reveals the credential
    bouncer.
    - A numeric PIN on a keypad is tapped key by key with `dispatchGesture`.
    - A password bouncer exposes a focused `EditText`; the agent fills it with
      `ACTION_SET_TEXT`, which handles letters and symbols without driving the
      keyboard, then submits with `ACTION_IME_ENTER`.
    - A pattern bouncer exposes a square `lockPatternView` with no per-dot
      nodes; the agent derives the 3×3 dot centers from its bounds and traces
      the stored sequence as one continuous `dispatchGesture`. Patterns are
      stored as their dot numbers 1–9 in row-major order (e.g. `1478`).
    - The credential is encrypted with an Android Keystore key and is never
      returned to the panel or stored by the relay.
  - **On-device enrollment:** after the first manual unlock of a secure device
    with no stored credential, the agent posts a one-time notification. It
    opens a local screen that detects the lock type and shows the matching
    input: a numeric PIN field, a 3×3 pattern pad, or a password field. The
    operator can override the detected type. The credential is stored with the
    same Keystore key and never sent through the relay. The status sheet also
    has an **Enroll** button; clearing the credential re-arms the prompt.
  - Without a stored credential, a PIN/pattern/password shows the normal lock
    screen; the person must enter it. Device Owner does not bypass a set
    credential: `setKeyguardDisabled` has no effect while one is configured.
  - The keyguard is OEM-specific. For PINs, digit labels are the primary path;
    the opt-in cached layout is used only when labels disappear. Wrong taps
    count as failed unlock attempts, so test on each model before enabling it.
- For unattended runs, use no secure screen lock.

## App unlock

The **App unlock** panel targets the foreground app.

- App locks that use the device screen lock (MIUI/POCO App lock, Samsung, etc.)
  show a biometric prompt with a **Use password/PIN/pattern** option. The agent
  taps that option, then enters the device unlock credential.
- App locks with their own credential read a per-app value stored from the
  panel, keyed by package and encrypted with a separate Keystore key. Leave the
  per-app field empty to use the device credential.
- The credential screen is detected in the foreground window: a square pattern
  view, a 3-column digit keypad, or an editable field. One attempt.
- Biometric-only locks, custom canvas keypads with no accessible digits, and
  apps that block accessibility input still need manual handling.

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
