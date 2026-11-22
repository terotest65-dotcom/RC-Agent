# Production notes — VPS + Coolify

This is an internal remote-control system. It can read SMS, place calls, open
apps, and control the screen. Treat the relay and its data as sensitive.

## Before exposing the relay

The current relay has **no authentication**: any client that can reach `/ws`
can connect as an admin or a device. A public VPS without an auth layer is not
safe. Do these before opening port 443 to the internet:

1. Put the admin UI behind **Coolify Basic Auth**, **Cloudflare Access**, or an
   identity-aware proxy. Enable 2FA on the Coolify dashboard itself.
2. Add **device authentication**. The strongest option is a per-device
   enrollment token stored in Android Keystore and revocable from the admin.
   A shared device token in the APK is a weaker stopgap.
3. Only expose ports **80/443**; keep SSH key-only and firewalled.
4. Use a real domain and Let's Encrypt; the Android release must use `wss://`.

The Docker image below is a deployment baseline, not an auth layer.

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
   ```

   The server refuses to start in production without a 32+ character
   `RC_SECRET_KEY`.
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
3. Set the release relay URL via `BuildConfig` or a flavor:
   `wss://rc.example.com/ws`. Debug can stay on `ws://10.0.2.2:8787/ws`.
4. Remove `android:usesCleartextTraffic="true"` from release builds. Use a
   network security config that permits cleartext only in debug.
5. Build and distribute the signed APK through an internal authenticated link
   or file share. Keep the keystore safe; all updates must use the same key.
6. Add an update check endpoint (versionCode + APK URL) so the team knows when
   to update.
7. For unattended devices: provision Device Owner, set screen lock to None,
   disable battery optimization, and verify auto-start/reboot behavior.

## Recommended service hardening

- Validate WebSocket `Origin` and add connection/message rate limits.
- Add an audit log of admin actions and run starts.
- Add per-device revocation and let admins remove a device.
- Use structured logs with rotation and an uptime monitor.
- Keep Node, the base image, and npm dependencies patched.
- Consider an IP allowlist or Cloudflare Tunnel in front of the admin UI.

## Local Docker test

```bash
RC_SECRET_KEY="$(openssl rand -base64 48)" docker compose up --build
# http://localhost:8787/
```

The admin UI is still unauthenticated in this local test.
