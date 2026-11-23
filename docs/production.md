# Production notes — VPS + Coolify

For the complete step-by-step deployment and APK build, see
[`../deployment_guide.md`](../deployment_guide.md).

This is an internal remote-control system. It can read SMS, place calls, open
apps, and control the screen. Treat the relay and its data as sensitive.

## Before exposing the relay

The relay now has a **shared admin password** and a **shared device token**.
That is enough to stop random internet scans, but both are shared secrets: one
password for all admins and one device token embedded in every APK.

1. Use strong random values for `RC_ADMIN_PASSWORD`, `RC_DEVICE_TOKEN`, and
   `RC_SECRET_KEY`. Do not use the development defaults.
2. Terminate TLS at Coolify and set `RC_PUBLIC_ORIGIN=https://your-domain`.
   The release APK must use `wss://`.
3. Enable 2FA on the Coolify dashboard and restrict SSH to keys.
4. Only expose ports **80/443**; keep 8787 internal.
5. Strongly consider per-device enrollment tokens next: the shared device token
   is extractable from the APK and cannot revoke one device. The current design
   intentionally accepts that tradeoff for simplicity.

## Coolify deployment

1. Create a DNS record, e.g. `rc.example.com`, pointing at the VPS.
2. In Coolify, create a resource from this Git repository using the
   **Dockerfile** at the repository root.
3. Set the exposed container port to **8787**.
4. Add the domain `rc.example.com` and enable HTTPS/Let's Encrypt. Coolify's
   proxy handles WebSocket upgrades by default.
5. Set environment variables:

   ```text
   NODE_ENV=production
   PORT=8787
   DATA_DIR=/data
   RC_SECRET_KEY=<openssl rand -base64 48>
   RC_ADMIN_PASSWORD=<strong shared admin password>
   RC_DEVICE_TOKEN=<strong shared device token>
   RC_PUBLIC_ORIGIN=https://rc.example.com
   ```

   The server refuses to start in production without a 32+ character
   `RC_SECRET_KEY`, a 10+ character admin password, and a 24+ character device
   token. Keep the admin password and device token in a password manager.
6. Add a persistent volume mapping `/data`. It contains `macros.json` and
   `secrets.json`; future SQLite data should live there too.
7. Deploy and confirm `https://rc.example.com/api/health`.
8. Immediately restrict the app with Coolify Basic Auth or Cloudflare Access
   until application-level admin login is implemented.

## Data and backups

- Back up `/data` regularly and encrypt the backups. Losing `RC_SECRET_KEY`
  makes encrypted secrets unrecoverable.
- Limit retention of SMS bodies and run screenshots; they can contain
  financial and personal data.
- Move run history and profiles from memory to SQLite/Postgres for restarts
  and auditing.
- Do not log message bodies, OTPs, or secret values.

## Android release build

The debug APK is not suitable for distribution.

1. Create a release keystore once and keep it outside the repository:

   ```bash
   keytool -genkeypair -v -keystore rc-release.jks -alias rc \
     -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Configure a `release` signing config, enable R8
   (`isMinifyEnabled = true`, `isShrinkResources = true`), and set a real
   `versionCode`/`versionName`.
3. Build release with the public relay and shared token hardcoded:

   ```bash
   ./gradlew :app:assembleRelease \
     -PrcRelayUrl=wss://rc.example.com/ws \
     -PrcDeviceToken='<RC_DEVICE_TOKEN>'
   ```

   Release builds fail if the URL is not `wss://` or the token is still the dev
   default. Debug uses `ws://10.0.2.2:8787/ws` and `dev-device`.
4. Cleartext is disabled; the network security config allows it only for the
   emulator/localhost.
5. Build and distribute the signed APK through an internal authenticated link
   or file share. Keep the keystore safe; all updates must use the same key.
6. Add an update check endpoint (versionCode + APK URL) so the team knows when
   to update.
7. For unattended devices: provision Device Owner, set screen lock to None,
   disable battery optimization, and verify auto-start/reboot behavior.
8. The device enrollment token is in the APK; treat the APK as sensitive.

## Recommended service hardening

- Validate WebSocket `Origin` and add connection/message rate limits.
- Add an audit log of admin actions and run starts.
- Add per-device revocation and let admins remove a device.
- Use structured logs with rotation and an uptime monitor.
- Keep Node, the base image, and npm dependencies patched.
- Consider an IP allowlist or Cloudflare Tunnel in front of the admin UI.

## Local Docker test

```bash
RC_SECRET_KEY="$(openssl rand -base64 48)" \
RC_ADMIN_PASSWORD="change-this-password" \
RC_DEVICE_TOKEN="change-this-device-token" \
docker compose up --build
# http://localhost:8787/ — sign in with the admin password
```
