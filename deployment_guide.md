# Deployment Guide

This guide deploys the relay/admin panel to a public VPS with **Coolify** and
builds the signed Android APK that connects to it.

> **Coolify version:** tested against the current **v4.7.6** release
> (2026-11-19). That release includes critical dependency security fixes; update
> Coolify before deploying. The UI labels below follow the v4.7.x docs
> (`Build pack`, `Ports Exposes`, `Persistent Storage`, `Environment Variables`,
> `Healthcheck`, `Domains`).

> **Important:** this is a remote-control system with SMS and phone access. Use
> a strong admin password, a strong shared device token, HTTPS/WSS, and restrict
> who can log in. The shared device token is embedded in the APK and can be
> extracted; per-device tokens are the recommended next step for a larger team.

---

## 1. Architecture

- **VPS**: runs Coolify and the `rc` Docker container.
- **Coolify proxy**: terminates TLS and routes
  `https://rc.example.com` → container port `8787`.
- **Admin panel**: `https://rc.example.com/` → login → device picker → one-device
  dashboard.
- **Android agents**: connect to `wss://rc.example.com/ws` with the shared
  `RC_DEVICE_TOKEN` and appear automatically.

Only ports **80/443** need to be public. Do not publish `8787` directly.

---

## 2. Prerequisites

- A VPS with a public IPv4/IPv6 address.
- A domain or subdomain, e.g. `rc.example.com`.
- DNS access to point the subdomain at the VPS.
- A Git repository containing this project (public, deploy key, or Git app).
- Coolify installed on the VPS.
- Locally: JDK 17+ and Android SDK 35 to build the APK.
- `openssl` for generating secrets.

### Generate secrets now

```bash
# AES key for stored secrets (32+ chars, production requires this)
openssl rand -base64 48

# Shared device token (24+ chars; embedded in the APK)
openssl rand -hex 32
```

Choose a long admin password (10+ chars, ideally 20+). Store all three values
in a password manager. Do not commit them.

---

## 3. Coolify setup

### 3.1 Update Coolify

Update Coolify to **v4.7.6 or newer** before deploying. Back up Coolify's own
data first. The v4.7.6 release fixes critical upstream PHP/nginx/Node CVEs.

Enable 2FA on the Coolify account and use a strong dashboard password.

### 3.2 DNS

Create:

| Type | Name | Value |
|---|---|---|
| `A` | `rc` | VPS IPv4 |
| `AAAA` (optional) | `rc` | VPS IPv6 |

Wait for DNS to resolve. Coolify's Let's Encrypt issuance needs it.

### 3.3 Create the application

1. In Coolify, open the target **Project** and **Environment** (e.g.
   `production`).
2. Select **+ New**.
3. Choose your Git source:
   - **Public repository** for a public repo.
   - **Private repository (deploy key)** or a Git App for a private repo.
4. Select **Build pack: Dockerfile**.
5. Set:
   - **Base Directory**: `/`
   - **Dockerfile Location**: `/Dockerfile`
6. Set **Ports Exposes** to `8787`.
7. Create/save the application.

Coolify builds the root `Dockerfile`, which:

- builds the web panel,
- installs production server dependencies,
- runs as a non-root user,
- serves the relay on `0.0.0.0:8787`,
- exposes `/api/health` for health checks.

### 3.4 Domain and HTTPS

1. Open the application → **Domains**.
2. Add `https://rc.example.com`.
3. Save and enable HTTPS / Let's Encrypt.
4. If Coolify asks for an internal port suffix, use
   `https://rc.example.com:8787` — **only the internal port goes in the
   domain**, public traffic still uses 443. With `Ports Exposes=8787`, the plain
   domain usually works.

The admin WebSocket uses the same domain; Coolify's proxy supports WebSocket
upgrades. Use `wss://rc.example.com/ws` from the Android build.

### 3.5 Environment variables

Open **Environment Variables**. Add these as **runtime-only** variables. The
secret values should be locked secrets where Coolify offers it.

| Variable | Example / source | Notes |
|---|---|---|
| `NODE_ENV` | `production` | Required |
| `PORT` | `8787` | Internal port |
| `DATA_DIR` | `/data` | Must match the persistent mount |
| `RC_SECRET_KEY` | `openssl rand -base64 48` | 32+ chars required in production |
| `RC_ADMIN_PASSWORD` | your long password | Shared admin login |
| `RC_DEVICE_TOKEN` | `openssl rand -hex 32` | Must match the APK build |
| `RC_PUBLIC_ORIGIN` | `https://rc.example.com` | No trailing slash |

`RC_SECRET_KEY`, `RC_ADMIN_PASSWORD`, and `RC_DEVICE_TOKEN` are secrets. Do not
set them as build-time variables, and do not put them in the Dockerfile.
`RC_PUBLIC_ORIGIN` is used to validate admin WebSocket origins.

The server refuses to start in production if:

- `RC_SECRET_KEY` is missing or shorter than 32 characters,
- `RC_ADMIN_PASSWORD` is missing or shorter than 10 characters,
- `RC_DEVICE_TOKEN` is missing or shorter than 24 characters.

After saving variables, redeploy or restart.

### 3.6 Persistent storage

The relay writes macros and encrypted secrets to `DATA_DIR`.

1. Open **Persistent Storage**.
2. Add a **Volume Mount**.
3. Destination Path: `/data`.
4. Source: either let Coolify create a volume, or use a fixed name such as
   `rc-data`.
5. Save, then redeploy/restart.

The container runs as the non-root `rc` user. A named volume created by Docker
normally inherits the correct ownership from the image. If you use a
**Directory Mount** instead, prepare the host directory with the same UID:

```bash
sudo mkdir -p /srv/rc-data
sudo chown -R 100:100 /srv/rc-data
```

Check inside the container if writes fail:

```bash
ls -ld /data
```

### 3.7 Healthcheck

The image contains a Dockerfile `HEALTHCHECK` that requests
`http://127.0.0.1:8787/api/health` with `wget`.

In Coolify v4.7.x, an enabled dashboard **Healthcheck** overrides the Dockerfile
check. Either:

- leave the dashboard healthcheck disabled and use the image check, or
- enable an HTTP check:
  - Method: `GET`
  - Scheme: `http`
  - Host: `localhost`
  - Port: `8787`
  - Path: `/api/health`
  - Expected code: `200`

Do not set a healthcheck path behind the login; `/api/health` is public for
this purpose and only returns counts.

### 3.8 Deploy and verify

1. Click **Deploy**.
2. Watch the build log; the first build installs npm dependencies and builds
   the web panel.
3. Watch runtime logs for:
   ```text
   RC relay listening on http://0.0.0.0:8787
   Serving web build from /app/web/dist
   ```
4. Verify:
   ```bash
   curl -i https://rc.example.com/api/health
   # 200 and {"ok":true,...}

   curl -i https://rc.example.com/api/session
   # 401 until you log in

   curl -I https://rc.example.com/
   # 200, login page
   ```
5. Open `https://rc.example.com/`, sign in with `RC_ADMIN_PASSWORD`, and verify
   the **Choose a device** page. It will be empty until an agent connects.

### 3.9 Firewall

```bash
sudo ufw allow 22/tcp
sudo ufw allow 80/tcp
sudo ufw allow 443/tcp
sudo ufw enable
```

Do **not** open `8787`. Only the Coolify proxy should reach it.

### 3.10 Coolify access

The app has its own shared-password login, so Coolify Basic Auth is optional.
Keep the Coolify dashboard itself locked down with 2FA and a strong password.
If you enable Coolify Basic Auth as an extra layer, make sure it does not
interfere with WebSocket upgrades.

### 3.11 Updates and rollbacks

- Push to the tracked Git branch; Coolify's webhook deploys the new commit.
- Runtime env changes require **Restart**; image/build changes require
  **Redeploy/Deploy**.
- The `/data` volume survives container replacement.
- Roll back by redeploying a previous commit or image.
- Rotating `RC_ADMIN_PASSWORD` invalidates new logins; existing sessions remain
  until expiry or a server restart. Restart after rotation if you want to force
  everyone out.
- Rotating `RC_DEVICE_TOKEN` requires rebuilding the APK with the new token and
  updating all devices.

### 3.12 Backups

- Back up the `/data` volume regularly and encrypt the backup.
- Back up `RC_SECRET_KEY`; losing it makes `secrets.json` unrecoverable.
- The current build keeps **devices, runs, and profiles in memory**. A relay
  restart clears the device list until agents reconnect. Macro and secret files
  persist in `/data`.
- Persistent storage is not a backup. Test a restore.

---

## 4. Local Docker test (optional)

Coolify uses the Dockerfile. For a quick local check:

```bash
cp .env.example .env
# edit .env with strong values, then set:
#   RC_PUBLIC_ORIGIN=http://localhost:8787
docker compose up --build
# open http://localhost:8787/
```

The compose file binds to `127.0.0.1:8787` only. It is not the public
deployment path. Coolify should use the repository Dockerfile.

---

## 5. Build the Android APK

The debug APK uses the emulator URL and development token. Do not distribute
it.

### 5.1 Build configuration

The release build requires two Gradle properties:

- `rcRelayUrl`: must start with `wss://`.
- `rcDeviceToken`: the same value as `RC_DEVICE_TOKEN` on the server.

They become `BuildConfig.RELAY_URL` and `BuildConfig.DEVICE_TOKEN`. There is no
relay URL field in the app; the URL and token are compiled into the APK.

Debug defaults:

```text
ws://10.0.2.2:8787/ws
dev-device
```

Release guard: `assembleRelease` fails if the URL is not `wss://` or the token
is still `dev-device`.

### 5.2 Build

```bash
cd android

./gradlew :app:assembleRelease \
  -PrcRelayUrl=wss://rc.example.com/ws \
  -PrcDeviceToken='<RC_DEVICE_TOKEN>'
```

Output:

```text
app/build/outputs/apk/release/app-release-unsigned.apk
```

The release build has R8/minification/shrink enabled. The minified APK is small
(a few hundred KB), but it is **unsigned** until you sign it.

### 5.3 Create a signing key (once)

```bash
keytool -genkeypair -v \
  -keystore rc-release.jks \
  -alias rc \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Keep `rc-release.jks` and its passwords outside the repository and backed up.
Every future update must use the same key.

### 5.4 Sign the APK

```bash
$ANDROID_HOME/build-tools/35.0.0/apksigner sign \
  --ks rc-release.jks \
  --ks-key-alias rc \
  --out rc-agent-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk

$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose \
  rc-agent-release.apk
```

Distribute `rc-agent-release.apk` through your internal file share. Users must
allow installing from that source.

### 5.5 Install and enroll

```bash
adb install -r rc-agent-release.apk
# or tap the APK on the device
```

On first launch the agent starts and connects immediately; the device appears
automatically on the admin **Choose a device** page (no approval step).

Operator **name and phone number are optional** — they only label the device.
Add them later from the **Operator details** card if desired.

Tap the top-right **Permissions** chip:
   - **Accessibility** → enabled (required for screen control),
   - **Notification access** → optional but recommended,
   - **SMS & phone** → grant for the Phone tab,
   - **Battery** → disable optimization for reliability,
   - **Device Owner** → optional.

For Android 13+ sideloads, if the service is blocked, open
**App info → ⋮ → Allow restricted settings** first.

Accessibility path varies by phone: **Settings → Accessibility → Downloaded
apps / Installed apps / Installed services → RC Agent**.

The app starts the agent on launch and attempts to restart it after reboot.
Opening the app is the reliable fallback on OEMs that restrict boot-time
foreground services.

### 5.6 Update the APK

1. Increase `versionCode` and `versionName` in
   `android/app/build.gradle.kts`.
2. Rebuild and sign with the **same** keystore.
3. Install/update over the old APK. Existing profile and enrollment survive
   unless the app is uninstalled.
4. If `RC_DEVICE_TOKEN` changed, rebuild with the new token and update devices.

---

## 6. Post-deployment checklist

- [ ] Coolify updated to v4.7.6+ and dashboard 2FA enabled.
- [ ] DNS resolves; HTTPS certificate is valid.
- [ ] `https://rc.example.com/api/health` returns 200.
- [ ] `/api/session` returns 401 before login.
- [ ] Admin login works with `RC_ADMIN_PASSWORD`.
- [ ] Strong values set for `RC_SECRET_KEY`, `RC_ADMIN_PASSWORD`,
      `RC_DEVICE_TOKEN`.
- [ ] `/data` volume mounted and writable.
- [ ] Firewall allows only 22/80/443; port 8787 is not public.
- [ ] Release APK built with `wss://` and the matching device token.
- [ ] APK signed with the permanent keystore and installed on a test device.
- [ ] Device appears automatically after setup.
- [ ] Accessibility, notifications, SMS/phone, and battery permissions checked.
- [ ] Backups configured for `/data` and the signing key.

---

## 7. Troubleshooting

**Login page loads but sign-in fails**
- Check runtime logs.
- Confirm `RC_ADMIN_PASSWORD` is set and the container restarted after changing
  it.
- Check for rate limiting after repeated bad passwords.

**Login works, but WebSocket does not connect**
- `RC_PUBLIC_ORIGIN` must exactly match the browser origin:
  `https://rc.example.com` (no trailing slash).
- Ensure Coolify’s proxy supports WebSocket upgrades.
- The public URL must be HTTPS; production cookies are `Secure`.

**Device stays offline**
- The APK token must exactly match `RC_DEVICE_TOKEN`.
- The release URL must be `wss://rc.example.com/ws`.
- Check the device status page and runtime logs; a bad token is rejected.
- Confirm the agent service is running (foreground notification).

**`No available server` / 404 on the domain**
- Confirm container port `8787` is correct.
- Confirm the process listens on `0.0.0.0`, not `127.0.0.1`.
- Disable an overriding Coolify healthcheck or fix the `/api/health` check.
- Check runtime logs for a crash.

**Volume permission errors**
- Named volumes normally inherit the image ownership.
- For a directory mount, `chown` the host directory to the container UID
  (`100:100` for the default `rc` user), then restart.

**Accessibility cannot be enabled on a sideloaded app**
- Allow restricted settings from **App info → ⋮** first.
- Then open accessibility and select RC Agent.

**APK install fails with a signature error**
- All updates must be signed with the same keystore.
- Uninstalling loses the local profile; avoid unless necessary.

---

## 8. Security notes

- The admin password and device token are shared secrets. Use long random
  values and rotate on team/device changes.
- The shared device token is in the APK; anyone with the APK can extract it.
  Per-device enrollment tokens are recommended as the next step.
- Telephony and SMS data are sensitive; keep the admin panel access limited and
  use HTTPS everywhere.
- The current version keeps devices/runs/profiles in memory and has no audit-log
  persistence. Add persistence and audit logging before a larger rollout.
- See [`docs/production.md`](production.md) for additional hardening notes.
