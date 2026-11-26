import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import {
  existsSync,
  mkdirSync,
  readFileSync,
  renameSync,
  statSync,
  writeFileSync
} from 'node:fs'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  randomUUID,
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  scryptSync,
  timingSafeEqual
} from 'node:crypto'
import { WebSocketServer, type WebSocket as WsSocket } from 'ws'
import type {
  AgentProfile,
  AppInfo,
  DeviceState,
  DeviceSummary,
  Macro,
  MacroStep,
  ProbeResult,
  RecordEvent,
  Role,
  RunRecord,
  RunStep,
  SecretInfo,
  ServerMessage,
  TelephonyInfo
} from '../../shared/protocol'

const here = fileURLToPath(new URL('.', import.meta.url))
const WEB_DIST = process.env.WEB_DIST ?? join(here, '../../web/dist')
const DATA_DIR = process.env.DATA_DIR ?? join(here, '../data')
const MACROS_FILE = join(DATA_DIR, 'macros.json')
const SECRETS_FILE = join(DATA_DIR, 'secrets.json')
const PORT = Number(process.env.PORT ?? 8787)
const IS_PRODUCTION = process.env.NODE_ENV === 'production'
const SECRET_KEY =
  process.env.RC_SECRET_KEY ??
  (IS_PRODUCTION ? '' : 'rc-dev-secret-key-change-me')

const ADMIN_PASSWORD =
  process.env.RC_ADMIN_PASSWORD ?? (IS_PRODUCTION ? '' : 'admin')
const DEVICE_TOKEN =
  process.env.RC_DEVICE_TOKEN ?? (IS_PRODUCTION ? '' : 'dev-device')
const PUBLIC_ORIGIN = (process.env.RC_PUBLIC_ORIGIN ?? '').replace(/\/$/, '')

if (IS_PRODUCTION && SECRET_KEY.length < 32) {
  console.error(
    'RC_SECRET_KEY must be set to at least 32 characters in production. ' +
      'Generate one with: openssl rand -base64 48'
  )
  process.exit(1)
}
if (IS_PRODUCTION && ADMIN_PASSWORD.length < 10) {
  console.error('RC_ADMIN_PASSWORD must be set to at least 10 characters in production.')
  process.exit(1)
}
if (IS_PRODUCTION && DEVICE_TOKEN.length < 24) {
  console.error('RC_DEVICE_TOKEN must be set to at least 24 characters in production.')
  process.exit(1)
}

const ADMIN_COOKIE = 'rc_admin'
const SESSION_TTL_MS = 12 * 60 * 60 * 1000
const sessions = new Map<string, number>()
const loginAttempts = new Map<string, { count: number; resetAt: number }>()

interface StoredSecret {
  id: string
  name: string
  createdAt: number
  iv: string
  tag: string
  data: string
}

interface DeviceRecord {
  ws: WsSocket
  id: string
  name: string
  model?: string
  android?: number
  agent?: string
  online: boolean
  lastSeen: number
  apps: AppInfo[]
  state?: DeviceState
  probes: Record<string, ProbeResult>
  telephony?: TelephonyInfo
  profile?: AgentProfile
}

interface RecordingSession {
  id: string
  deviceId: string
  appPackage?: string
  appLabel?: string
  name?: string
  startedAt: number
  events: RecordEvent[]
}

const devices = new Map<string, DeviceRecord>()
const admins = new Set<WsSocket>()
const macros = new Map<string, Macro>()
const runs = new Map<string, RunRecord>()
const secrets = new Map<string, StoredSecret>()

const BUILTIN_MACROS: Macro[] = [
  {
    id: 'sample-settings-network',
    name: 'Settings — open Network & internet',
    appPackage: 'com.android.settings',
    steps: [
      { op: 'launch', package: 'com.android.settings' },
      {
        op: 'waitFor',
        selector: { text: 'Network & internet' },
        timeoutMs: 8000
      },
      {
        op: 'tap',
        selector: { text: 'Network & internet' },
        expect: { text: 'Internet' }
      },
      { op: 'screenshot', label: 'network' }
    ]
  },
  {
    id: 'builtin-phonepe-balance',
    name: 'PhonePe — check first bank balance',
    appPackage: 'com.phonepe.app',
    params: {
      bank: { type: 'number', required: true, default: 1 }
    },
    steps: [
      { op: 'launch', package: 'com.phonepe.app' },
      { op: 'waitForIdle' },
      { op: 'appUnlock', package: 'com.phonepe.app' },
      { op: 'tapIfExists', selector: { desc: 'Back' } },
      { op: 'tapIfExists', selector: { text: 'Home' } },
      {
        op: 'waitFor',
        selector: { contains: 'Check Balance' },
        timeoutMs: 15000
      },
      { op: 'tap', selector: { contains: 'Check Balance' } },
      {
        op: 'waitFor',
        selector: { contains: 'Bank Account' },
        timeoutMs: 15000
      },
      {
        op: 'tap',
        selector: { contains: 'Bank Account', index: '{{bank}}' }
      },
      {
        op: 'enterPin',
        secretRef: 'phonepe-upi-pin',
        submit: false
      },
      { op: 'sleep', ms: 2500 },
      {
        op: 'waitFor',
        selector: { viewId: 'com.phonepe.app:id/rv_process_check_balance' },
        timeoutMs: 20000
      },
      { op: 'screenshot', label: 'balance' },
      { op: 'assert', selector: { contains: 'Balance' } },
      {
        op: 'scroll',
        selector: { viewId: 'com.phonepe.app:id/rv_process_check_balance' },
        dir: 'down'
      },
      {
        op: 'scroll',
        selector: { viewId: 'com.phonepe.app:id/rv_process_check_balance' },
        dir: 'down'
      },
      { op: 'screenshot', label: 'balance-details' },
      { op: 'tapIfExists', selector: { text: 'DONE' } }
    ]
  },
  {
    id: 'builtin-gpay-balance',
    name: 'GPay — check first bank balance',
    appPackage: 'com.google.android.apps.nbu.paisa.user',
    params: {
      bank: { type: 'number', required: true, default: 1 }
    },
    steps: [
      { op: 'launch', package: 'com.google.android.apps.nbu.paisa.user' },
      { op: 'waitForIdle' },
      { op: 'appUnlock', package: 'com.google.android.apps.nbu.paisa.user' },
      { op: 'tapIfExists', selector: { contains: 'Skip' } },
      { op: 'tapIfExists', selector: { desc: 'Back' } },
      { op: 'tapIfExists', selector: { text: 'Home' } },
      {
        op: 'waitFor',
        selector: { contains: 'Bank balance' },
        timeoutMs: 15000
      },
      { op: 'tap', selector: { contains: 'Bank balance' } },
      {
        op: 'waitFor',
        selector: { contains: 'Check balance' },
        timeoutMs: 15000
      },
      { op: 'tap', selector: { contains: 'Bank', index: '{{bank}}' } },
      {
        op: 'enterPin',
        secretRef: 'gpay-upi-pin',
        submit: false
      },
      {
        op: 'waitFor',
        selector: { contains: 'done', ignoreCase: true },
        timeoutMs: 8000,
        optional: true
      },
      { op: 'screenshot', label: 'balance' },
      {
        op: 'tapIfExists',
        selector: { contains: 'done', ignoreCase: true }
      }
    ]
  },
  {
    id: 'builtin-paytm-balance',
    name: 'Paytm — check first bank balance',
    appPackage: 'net.one97.paytm',
    params: {
      bank: { type: 'number', required: true, default: 1 }
    },
    steps: [
      { op: 'launch', package: 'net.one97.paytm' },
      { op: 'waitForIdle' },
      { op: 'appUnlock', package: 'net.one97.paytm' },
      { op: 'tapIfExists', selector: { contains: 'Skip' } },
      { op: 'tapIfExists', selector: { desc: 'Back' } },
      {
        op: 'waitFor',
        selector: { contains: 'Balance & History' },
        timeoutMs: 15000
      },
      { op: 'tap', selector: { contains: 'Balance & History' } },
      {
        op: 'waitFor',
        selector: { contains: 'Payment History' },
        timeoutMs: 15000
      },
      {
        op: 'tap',
        selector: { contains: 'Check Balance', index: '{{bank}}' }
      },
      {
        op: 'enterPin',
        secretRef: 'paytm-upi-pin',
        submit: true
      },
      {
        op: 'waitFor',
        selector: { contains: 'done', ignoreCase: true },
        timeoutMs: 8000,
        optional: true
      },
      { op: 'screenshot', label: 'balance' },
      {
        op: 'tapIfExists',
        selector: { contains: 'done', ignoreCase: true }
      }
    ]
  }
]
const recordingSessions = new Map<string, RecordingSession>()

function log(...args: unknown[]) {
  console.log(new Date().toISOString(), ...args)
}

function hash(value: string) {
  return createHash('sha256').update(value).digest()
}

function safeEqual(left: string, right: string) {
  return timingSafeEqual(hash(left), hash(right))
}

function parseCookies(header: string | undefined) {
  const cookies = new Map<string, string>()
  if (!header) return cookies
  for (const part of header.split(';')) {
    const index = part.indexOf('=')
    if (index < 0) continue
    cookies.set(part.slice(0, index).trim(), part.slice(index + 1).trim())
  }
  return cookies
}

function sessionFromRequest(req: IncomingMessage) {
  const token = parseCookies(req.headers.cookie).get(ADMIN_COOKIE)
  if (!token) return null
  const expires = sessions.get(token)
  if (!expires) return null
  if (expires < Date.now()) {
    sessions.delete(token)
    return null
  }
  return token
}

function adminCookie(token: string, secure: boolean) {
  return `${ADMIN_COOKIE}=${token}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${Math.floor(
    SESSION_TTL_MS / 1000
  )};${secure ? ' Secure;' : ''}`
}

function clearAdminCookie(secure: boolean) {
  return `${ADMIN_COOKIE}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0;${
    secure ? ' Secure;' : ''
  }`
}


function jsonStatus(res: ServerResponse, status: number, value: unknown) {
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  })
  res.end(JSON.stringify(value))
}

function readJsonBody(req: IncomingMessage, limit = 4096) {
  return new Promise<any>((resolve, reject) => {
    let size = 0
    const chunks: Buffer[] = []
    req.on('data', (chunk: Buffer) => {
      size += chunk.length
      if (size > limit) {
        reject(new Error('body too large'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      try {
        resolve(chunks.length ? JSON.parse(Buffer.concat(chunks).toString('utf8')) : {})
      } catch {
        reject(new Error('invalid json'))
      }
    })
    req.on('error', reject)
  })
}

function clientKey(req: IncomingMessage) {
  const forwarded = req.headers['x-forwarded-for']
  if (typeof forwarded === 'string' && forwarded.trim()) {
    return forwarded.split(',')[0].trim()
  }
  return req.socket.remoteAddress ?? 'unknown'
}

function loginBlocked(req: IncomingMessage) {
  const key = clientKey(req)
  const now = Date.now()
  const entry = loginAttempts.get(key)
  if (!entry || entry.resetAt < now) {
    loginAttempts.delete(key)
    return false
  }
  return entry.count >= 8
}

function recordLoginFailure(req: IncomingMessage) {
  const key = clientKey(req)
  const now = Date.now()
  const entry = loginAttempts.get(key)
  if (!entry || entry.resetAt < now) {
    loginAttempts.set(key, { count: 1, resetAt: now + 5 * 60 * 1000 })
    return
  }
  entry.count += 1
}

function adminOriginOk(req: IncomingMessage) {
  const origin = req.headers.origin
  if (!origin) return false
  if (PUBLIC_ORIGIN) return origin === PUBLIC_ORIGIN
  try {
    const originHost = new URL(origin).hostname
    const requestHost = (req.headers.host ?? '').split(':')[0]
    return originHost === requestHost
  } catch {
    return false
  }
}

function json(res: ServerResponse, value: unknown) {
  res.writeHead(200, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  })
  res.end(JSON.stringify(value))
}

const MIME: Record<string, string> = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.webp': 'image/webp',
  '.ico': 'image/x-icon',
  '.woff2': 'font/woff2'
}

function serveStatic(_req: IncomingMessage, res: ServerResponse, pathname: string) {
  if (!existsSync(WEB_DIST)) {
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' })
    res.end('Web build not found. Run: cd web && npm install && npm run build')
    return
  }

  let file = normalize(join(WEB_DIST, pathname === '/' ? '/index.html' : pathname))
  if (!file.startsWith(WEB_DIST)) {
    res.writeHead(403)
    res.end('forbidden')
    return
  }
  if (!existsSync(file) || !statSync(file).isFile()) {
    file = join(WEB_DIST, 'index.html')
  }

  const ext = extname(file).toLowerCase()
  res.writeHead(200, {
    'content-type': MIME[ext] ?? 'application/octet-stream',
    'cache-control': ext === '.html' ? 'no-cache' : 'public, max-age=31536000, immutable'
  })
  res.end(readFileSync(file))
}

const server = createServer(async (req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  const secureCookie =
    IS_PRODUCTION || req.headers['x-forwarded-proto'] === 'https'

  if (url.pathname === '/api/login') {
    if (req.method !== 'POST') {
      jsonStatus(res, 405, { ok: false, error: 'method not allowed' })
      return
    }
    if (loginBlocked(req)) {
      jsonStatus(res, 429, { ok: false, error: 'Too many attempts. Try later.' })
      return
    }
    let body: any
    try {
      body = await readJsonBody(req)
    } catch (error) {
      jsonStatus(res, 400, {
        ok: false,
        error: error instanceof Error ? error.message : 'invalid request'
      })
      return
    }
    const password = typeof body?.password === 'string' ? body.password : ''
    if (!safeEqual(password, ADMIN_PASSWORD)) {
      recordLoginFailure(req)
      jsonStatus(res, 401, { ok: false, error: 'Invalid password' })
      return
    }
    loginAttempts.delete(clientKey(req))
    const token = randomBytes(32).toString('base64url')
    sessions.set(token, Date.now() + SESSION_TTL_MS)
    res.setHeader('set-cookie', adminCookie(token, secureCookie))
    jsonStatus(res, 200, { ok: true })
    return
  }

  if (url.pathname === '/api/logout') {
    if (req.method !== 'POST') {
      jsonStatus(res, 405, { ok: false, error: 'method not allowed' })
      return
    }
    const token = sessionFromRequest(req)
    if (token) {
      sessions.delete(token)
      for (const admin of admins) {
        if ((admin as any).rcSession === token) admin.close(4001, 'logged out')
      }
    }
    res.setHeader('set-cookie', clearAdminCookie(secureCookie))
    jsonStatus(res, 200, { ok: true })
    return
  }

  if (url.pathname === '/api/session') {
    if (sessionFromRequest(req)) {
      jsonStatus(res, 200, { authenticated: true })
    } else {
      jsonStatus(res, 401, { authenticated: false })
    }
    return
  }

  if (url.pathname === '/api/health') {
    json(res, {
      ok: true,
      devices: devices.size,
      online: [...devices.values()].filter((d) => d.online).length,
      admins: admins.size,
      macros: macros.size,
      runs: runs.size,
      secrets: secrets.size,
      uptime: Math.round(process.uptime())
    })
    return
  }

  if (url.pathname.startsWith('/api/') && !sessionFromRequest(req)) {
    jsonStatus(res, 401, { ok: false, error: 'login required' })
    return
  }

  if (url.pathname === '/api/devices') {
    json(res, [...devices.values()].map(summary))
    return
  }

  if (url.pathname === '/api/macros') {
    json(res, [...macros.values()])
    return
  }

  if (url.pathname === '/api/runs') {
    json(res, recentRuns())
    return
  }

  if (url.pathname === '/api/secrets') {
    json(res, [...secrets.values()].map(secretInfo))
    return
  }

  serveStatic(req, res, url.pathname)
})

function summary(device: DeviceRecord): DeviceSummary {
  return {
    id: device.id,
    name: device.name,
    model: device.model,
    android: device.android,
    agent: device.agent,
    online: device.online,
    lastSeen: device.lastSeen,
    apps: device.apps,
    state: device.state,
    probes: device.probes,
    telephony: device.telephony,
    profile: device.profile
  }
}

function recentRuns(): RunRecord[] {
  return [...runs.values()]
    .sort((a, b) => b.startedAt - a.startedAt)
    .slice(0, 20)
}

function failRun(
  deviceId: string,
  macroId: string,
  macroName: string,
  message: string,
  params: Record<string, unknown>
) {
  const now = Date.now()
  const run: RunRecord = {
    id: randomUUID(),
    macroId,
    macroName,
    deviceId,
    status: 'failed',
    startedAt: now,
    finishedAt: now,
    steps: [],
    params,
    error: message
  }
  runs.set(run.id, run)
  broadcast({ t: 'runStarted', run })
  broadcast({ t: 'runFinished', run })
  log(`run rejected: ${message}`)
}

function send(ws: WsSocket, message: ServerMessage) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(message))
}

function broadcast(message: ServerMessage) {
  const payload = JSON.stringify(message)
  for (const admin of admins) {
    if (admin.readyState === admin.OPEN) admin.send(payload)
  }
}

function sendMacros(ws: WsSocket) {
  send(ws, { t: 'macros', macros: [...macros.values()] })
}

function sendRuns(ws: WsSocket) {
  send(ws, { t: 'runs', runs: recentRuns() })
}

// ---------------------------------------------------------------------
// Macro persistence
// ---------------------------------------------------------------------

function loadMacros() {
  let changed = false

  if (existsSync(MACROS_FILE)) {
    try {
      const parsed = JSON.parse(readFileSync(MACROS_FILE, 'utf8')) as Macro[]
      for (const macro of parsed) {
        if (macro?.id && macro?.name && Array.isArray(macro.steps)) {
          macros.set(macro.id, macro)
        }
      }
    } catch (error) {
      log('failed to load macros', error)
    }
  }

  // Seed built-in macros once. The marker keeps a deleted macro deleted after
  // the next restart. Delete server/data/macros.seed to restore them.
  const seedFile = join(DATA_DIR, 'macros.seed')
  if (!existsSync(seedFile) || macros.size === 0) {
    const now = Date.now()
    for (const builtin of BUILTIN_MACROS) {
      const exists = [...macros.values()].some(
        (macro) => macro.id === builtin.id || macro.name === builtin.name
      )
      if (exists) continue
      macros.set(builtin.id, {
        ...builtin,
        createdAt: builtin.createdAt ?? now,
        updatedAt: builtin.updatedAt ?? now
      })
      changed = true
    }
    try {
      mkdirSync(DATA_DIR, { recursive: true })
      writeFileSync(seedFile, `${new Date().toISOString()}\n`)
    } catch (error) {
      log('failed to write macro seed marker', error)
    }
  }

  if (changed) saveMacros()
}

function saveMacros() {
  try {
    mkdirSync(DATA_DIR, { recursive: true })
    const temp = `${MACROS_FILE}.tmp`
    writeFileSync(temp, JSON.stringify([...macros.values()], null, 2))
    renameSync(temp, MACROS_FILE)
  } catch (error) {
    log('failed to save macros', error)
  }
}

// ---------------------------------------------------------------------
// Secrets (AES-256-GCM at rest; the value is only decrypted at run time)
// ---------------------------------------------------------------------

const secretKey = scryptSync(SECRET_KEY, 'rc-secrets-salt', 32)

function encryptSecret(value: string) {
  const iv = randomBytes(12)
  const cipher = createCipheriv('aes-256-gcm', secretKey, iv)
  const data = Buffer.concat([cipher.update(value, 'utf8'), cipher.final()])
  return {
    iv: iv.toString('base64'),
    tag: cipher.getAuthTag().toString('base64'),
    data: data.toString('base64')
  }
}

function decryptSecret(secret: StoredSecret): string {
  const decipher = createDecipheriv(
    'aes-256-gcm',
    secretKey,
    Buffer.from(secret.iv, 'base64')
  )
  decipher.setAuthTag(Buffer.from(secret.tag, 'base64'))
  return Buffer.concat([
    decipher.update(Buffer.from(secret.data, 'base64')),
    decipher.final()
  ]).toString('utf8')
}

function secretInfo(secret: StoredSecret): SecretInfo {
  return { id: secret.id, name: secret.name, createdAt: secret.createdAt }
}

function loadSecrets() {
  if (!existsSync(SECRETS_FILE)) return
  try {
    const parsed = JSON.parse(readFileSync(SECRETS_FILE, 'utf8')) as StoredSecret[]
    for (const secret of parsed) {
      if (secret?.id && secret?.name && secret?.data) {
        secrets.set(secret.id, secret)
      }
    }
  } catch (error) {
    log('failed to load secrets', error)
  }
}

function saveSecrets() {
  try {
    mkdirSync(DATA_DIR, { recursive: true })
    const temp = `${SECRETS_FILE}.tmp`
    writeFileSync(temp, JSON.stringify([...secrets.values()], null, 2))
    renameSync(temp, SECRETS_FILE)
  } catch (error) {
    log('failed to save secrets', error)
  }
}

function sendSecrets(ws: WsSocket) {
  send(ws, { t: 'secrets', secrets: [...secrets.values()].map(secretInfo) })
}

function relatedApp(app: string | null | undefined, target: string | undefined): boolean {
  if (app === 'com.acme.rc' && target !== 'com.acme.rc') return false
  if (!target) return true
  if (!app) return false
  if (app === target) return true
  if (app.startsWith(target) || target.startsWith(app)) return true
  if (app.includes('settings') && target.includes('settings')) return true
  return false
}

function macroFromSession(session: RecordingSession): Macro | null {
  const events = session.events.filter((event) => relatedApp(event.app, session.appPackage))
  if (
    !events.some(
      (event) =>
        event.type === 'tap' || event.type === 'text' || event.type === 'scroll'
    )
  ) {
    return null
  }

  const steps: MacroStep[] = []

  if (session.appPackage) steps.push({ op: 'launch', package: session.appPackage })
  steps.push({ op: 'waitForIdle' })

  const textSteps = new Map<string, number>()
  for (const event of events) {
    if (event.type === 'tap') {
      if (event.viewId) {
        steps.push({ op: 'tap', selector: { viewId: event.viewId } })
      } else if (event.label) {
        steps.push({ op: 'tap', selector: { text: event.label } })
      }
    } else if (event.type === 'text' && !event.redacted) {
      const selector = event.viewId
        ? { viewId: event.viewId }
        : event.className
          ? { className: event.className }
          : { className: 'android.widget.EditText' }
      const key = event.viewId ?? event.className ?? 'field'
      const index = textSteps.get(key)
      if (index !== undefined) {
        steps[index].text = event.text ?? ''
        if (event.masked) steps[index].secret = true
      } else {
        const step: MacroStep = {
          op: 'setText',
          selector,
          text: event.text ?? ''
        }
        if (event.masked) step.secret = true
        steps.push(step)
        textSteps.set(key, steps.length - 1)
      }
    } else if (event.type === 'scroll') {
      const selector = event.viewId
        ? { viewId: event.viewId }
        : event.className
          ? { className: event.className }
          : undefined
      if (selector) {
        steps.push({
          op: 'scroll',
          selector,
          dir: (event.deltaY ?? 0) < 0 ? 'up' : 'down'
        })
      }
    }
  }

  steps.push({ op: 'screenshot', label: 'recorded' })

  const label = session.appLabel ?? session.appPackage ?? 'App'
  return {
    id: randomUUID(),
    name: session.name || `${label} — recorded ${new Date().toLocaleTimeString()}`,
    appPackage: session.appPackage,
    steps,
    createdAt: Date.now(),
    updatedAt: Date.now()
  }
}

function finalizeSession(session: RecordingSession) {
  if (!recordingSessions.has(session.id)) return
  recordingSessions.delete(session.id)
  const macro = macroFromSession(session)
  if (!macro) {
    log('recording had no actions; not saved')
    return
  }
  macros.set(macro.id, macro)
  saveMacros()
  broadcast({ t: 'macroSaved', macro })
  broadcast({ t: 'macros', macros: [...macros.values()] })
  log(`recorded macro: ${macro.name} (${macro.steps.length} steps)`)
}

loadMacros()
loadSecrets()

// ---------------------------------------------------------------------
// WebSocket relay
// ---------------------------------------------------------------------

const wss = new WebSocketServer({ noServer: true })

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)
  if (url.pathname !== '/ws') {
    socket.destroy()
    return
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    wss.emit('connection', ws, req)
  })
})

wss.on('connection', (ws, req) => {
  let role: Role | null = null
  let deviceId: string | null = null

  ;(ws as any).isAlive = true
  ws.on('pong', () => {
    ;(ws as any).isAlive = true
  })

  const fail = (message: string) => send(ws, { t: 'error', message })

  ws.on('message', (raw) => {
    let msg: any
    try {
      msg = JSON.parse(raw.toString())
    } catch {
      fail('invalid json')
      return
    }

    if (msg.t === 'hello') {
      role = msg.role as Role

      if (role === 'admin') {
        const session = sessionFromRequest(req)
        if (!session || !adminOriginOk(req)) {
          fail('login required')
          ws.close(4001, 'login required')
          return
        }
        ;(ws as any).rcSession = session
        admins.add(ws)
        send(ws, { t: 'devices', devices: [...devices.values()].map(summary) })
        sendMacros(ws)
        sendRuns(ws)
        sendSecrets(ws)
        log('admin connected')
        return
      }

      if (role === 'device') {
        if (!safeEqual(String(msg.token ?? ''), DEVICE_TOKEN)) {
          fail('bad device token')
          ws.close(4003, 'bad device token')
          return
        }
        const id = msg.deviceId ? String(msg.deviceId) : ''
        if (!id) {
          fail('deviceId required')
          ws.close()
          return
        }
        deviceId = id
        const existing = devices.get(id)
        const device: DeviceRecord = {
          ws,
          id,
          name: msg.name ?? existing?.name ?? id,
          model: msg.model ?? existing?.model,
          android: msg.android ?? existing?.android,
          agent: msg.agent ?? existing?.agent,
          online: true,
          lastSeen: Date.now(),
          apps: existing?.apps ?? [],
          state: existing?.state,
          probes: existing?.probes ?? {}
        }
        devices.set(id, device)
        // The same device may reconnect before its old socket notices the drop.
        // Drop the stale socket so it cannot linger or race the new one.
        if (existing && existing.ws !== ws) {
          try {
            existing.ws.terminate()
          } catch {
            // already closed
          }
        }
        send(ws, { t: 'helloOk', deviceId: id })
        broadcast({ t: 'device', device: summary(device) })
        log(`device connected: ${id}`)
        return
      }

      fail('unknown role')
      ws.close()
      return
    }

    if (role === 'device' && deviceId) {
      const device = devices.get(deviceId)
      if (!device || device.ws !== ws) return
      device.lastSeen = Date.now()

      switch (msg.t) {
        case 'apps':
          device.apps = Array.isArray(msg.apps) ? msg.apps : []
          broadcast({ t: 'apps', deviceId, apps: device.apps })
          break
        case 'state':
          device.state = msg.state
          broadcast({ t: 'state', deviceId, state: msg.state })
          break
        case 'snapshot':
          broadcast({ t: 'snapshot', ...msg, deviceId })
          break
        case 'probe': {
          const result = { ...msg.result, deviceId }
          if (result?.pkg) device.probes[result.pkg] = result
          broadcast({ t: 'probe', deviceId, result })
          break
        }
        case 'actionResult':
          broadcast({ t: 'actionResult', deviceId, ...msg })
          break
        case 'profile': {
          const profile = msg.profile as AgentProfile | undefined
          if (profile?.name && profile?.phone) {
            device.profile = {
              name: String(profile.name),
              phone: String(profile.phone),
              consentAt: Number(profile.consentAt) || Date.now(),
              termsVersion: Number(profile.termsVersion) || 1
            }
            broadcast({ t: 'profile', deviceId, profile: device.profile })
          }
          break
        }
        case 'telephony': {
          const telephony: TelephonyInfo = {
            sims: Array.isArray(msg.sims) ? msg.sims : [],
            permissions: msg.permissions ?? {
              readSms: false,
              sendSms: false,
              readPhoneState: false,
              readPhoneNumbers: false,
              callPhone: false
            },
            error: msg.error ?? null
          }
          device.telephony = telephony
          broadcast({ t: 'telephony', deviceId, ...telephony })
          break
        }
        case 'smsList':
          broadcast({
            t: 'smsList',
            deviceId,
            messages: Array.isArray(msg.messages) ? msg.messages : [],
            error: msg.error ?? null
          })
          break
        case 'smsSendResult':
          broadcast({ ...msg, t: 'smsSendResult', deviceId })
          break
        case 'ussdResult':
          broadcast({ ...msg, t: 'ussdResult', deviceId })
          break
        case 'recordEvent': {
          const sessionId = msg.sessionId ? String(msg.sessionId) : ''
          const session = sessionId ? recordingSessions.get(sessionId) : undefined
          if (session && session.deviceId === deviceId) {
            session.events.push(msg.event as RecordEvent)
          }
          broadcast({
            t: 'recordEvent',
            deviceId,
            event: msg.event,
            ...(sessionId ? { sessionId } : {})
          })
          break
        }
        case 'recordingState': {
          broadcast({
            t: 'recordingState',
            deviceId,
            recording: !!msg.recording,
            ...(msg.sessionId ? { sessionId: msg.sessionId } : {})
          })
          if (!msg.recording && msg.sessionId) {
            const session = recordingSessions.get(String(msg.sessionId))
            if (session) setTimeout(() => finalizeSession(session), 500)
          }
          break
        }
        case 'prompt':
          broadcast({ t: 'prompt', deviceId, ...msg })
          break
        case 'runStarted': {
          const run: RunRecord = runs.get(msg.runId) ?? {
            id: msg.runId,
            macroId: msg.macroId ?? '',
            macroName: msg.macroName ?? 'macro',
            deviceId,
            status: 'running',
            startedAt: msg.startedAt ?? Date.now(),
            steps: []
          }
          run.status = 'running'
          run.startedAt = msg.startedAt ?? run.startedAt
          runs.set(run.id, run)
          broadcast({ t: 'runStarted', run })
          break
        }
        case 'runStep': {
          const run = runs.get(msg.runId)
          if (!run) break
          const step = msg.step as RunStep
          if (step.snapshot) {
            step.snapshot = { ...step.snapshot, t: 'snapshot', deviceId } as any
          }
          const existing = run.steps.findIndex((s) => s.i === step.i)
          if (existing >= 0) run.steps[existing] = step
          else run.steps.push(step)
          run.steps.sort((a, b) => a.i - b.i)
          broadcast({ t: 'runStep', runId: run.id, step })
          break
        }
        case 'runFinished': {
          const run = runs.get(msg.runId)
          if (!run) break
          run.status = msg.status ?? 'failed'
          run.finishedAt = msg.finishedAt ?? Date.now()
          broadcast({ t: 'runFinished', run })
          break
        }
        default:
          break
      }
      return
    }

    if (role === 'admin') {
      if (msg.t === 'macroList') {
        sendMacros(ws)
        return
      }

      if (msg.t === 'macroSave') {
        const incoming = msg.macro as Macro
        if (!incoming?.name || !Array.isArray(incoming.steps)) {
          fail('invalid macro')
          return
        }
        const macro: Macro = {
          ...incoming,
          id: incoming.id || randomUUID(),
          createdAt: incoming.createdAt ?? Date.now(),
          updatedAt: Date.now()
        }
        macros.set(macro.id, macro)
        saveMacros()
        broadcast({ t: 'macroSaved', macro })
        broadcast({ t: 'macros', macros: [...macros.values()] })
        return
      }

      if (msg.t === 'macroDelete') {
        const id = String(msg.macroId ?? '')
        if (id) macros.delete(id)
        saveMacros()
        broadcast({ t: 'macros', macros: [...macros.values()] })
        return
      }

      if (msg.t === 'secretList') {
        sendSecrets(ws)
        return
      }

      if (msg.t === 'secretSave') {
        const name = String(msg.name ?? '').trim()
        const value = String(msg.value ?? '')
        if (!name || !value) {
          fail('secret name and value required')
          return
        }
        if ([...secrets.values()].some((secret) => secret.name === name)) {
          fail(`secret already exists: ${name}`)
          return
        }
        const encrypted = encryptSecret(value)
        const secret: StoredSecret = {
          id: randomUUID(),
          name,
          createdAt: Date.now(),
          ...encrypted
        }
        secrets.set(secret.id, secret)
        saveSecrets()
        broadcast({ t: 'secretSaved', secret: secretInfo(secret) })
        broadcast({
          t: 'secrets',
          secrets: [...secrets.values()].map(secretInfo)
        })
        return
      }

      if (msg.t === 'secretDelete') {
        const id = String(msg.secretId ?? '')
        if (id) secrets.delete(id)
        saveSecrets()
        broadcast({
          t: 'secrets',
          secrets: [...secrets.values()].map(secretInfo)
        })
        return
      }

      if (msg.t === 'cmd') {
        const target = msg.deviceId ? devices.get(String(msg.deviceId)) : undefined
        if (!target || !target.online) {
          fail(`device offline: ${msg.deviceId ?? '?'}`)
          return
        }

        if (msg.action === 'startRecording') {
          const sessionId = msg.sessionId ? String(msg.sessionId) : randomUUID()
          const existing = [...recordingSessions.values()].find(
            (session) => session.deviceId === target.id
          )
          if (existing) finalizeSession(existing)

          const session: RecordingSession = {
            id: sessionId,
            deviceId: target.id,
            appPackage: msg.appPackage ? String(msg.appPackage) : undefined,
            appLabel: msg.appLabel ? String(msg.appLabel) : undefined,
            name: msg.name ? String(msg.name) : undefined,
            startedAt: Date.now(),
            events: []
          }
          recordingSessions.set(sessionId, session)
          target.ws.send(
            JSON.stringify({ ...msg, deviceId: target.id, sessionId })
          )
          return
        }

        if (msg.action === 'stopRecording') {
          const sessionId = msg.sessionId ? String(msg.sessionId) : ''
          const session = sessionId
            ? recordingSessions.get(sessionId)
            : [...recordingSessions.values()].find(
                (candidate) => candidate.deviceId === target.id
              )
          target.ws.send(
            JSON.stringify({
              ...msg,
              deviceId: target.id,
              sessionId: session?.id ?? msg.sessionId
            })
          )
          if (session) {
            // Let the stop command and any final events reach us first.
            setTimeout(() => finalizeSession(session), 500)
          }
          return
        }

        if (msg.action === 'runMacro') {
          const params =
            msg.params && typeof msg.params === 'object' ? msg.params : {}

          const macro = macros.get(String(msg.macroId))
          if (!macro) {
            const message = `unknown macro: ${msg.macroId}`
            fail(message)
            failRun(
              target.id,
              String(msg.macroId ?? ''),
              'unknown macro',
              message,
              params
            )
            return
          }

          let resolvedMacro: Macro
          try {
            resolvedMacro = {
              ...macro,
              steps: macro.steps.map((step) => {
                if (!step.secretRef) return step
                const secret = [...secrets.values()].find(
                  (candidate) =>
                    candidate.name === step.secretRef ||
                    candidate.id === step.secretRef
                )
                if (!secret) throw new Error(`unknown secret: ${step.secretRef}`)
                return { ...step, text: decryptSecret(secret) }
              })
            }
          } catch (error) {
            const message = error instanceof Error ? error.message : String(error)
            fail(message)
            failRun(target.id, macro.id, macro.name, message, params)
            return
          }

          const runId = randomUUID()
          const run: RunRecord = {
            id: runId,
            macroId: macro.id,
            macroName: macro.name,
            deviceId: target.id,
            status: 'running',
            startedAt: Date.now(),
            steps: [],
            params
          }
          runs.set(runId, run)
          broadcast({ t: 'runStarted', run })
          target.ws.send(
            JSON.stringify({
              t: 'cmd',
              cmdId: msg.cmdId ?? randomUUID(),
              deviceId: target.id,
              action: 'runMacro',
              runId,
              macro: resolvedMacro,
              params
            })
          )
          return
        }

        target.ws.send(JSON.stringify({ ...msg, deviceId: target.id }))
        return
      }
    }
  })

  ws.on('close', () => {
    admins.delete(ws)
    if (deviceId) {
      const device = devices.get(deviceId)
      if (device && device.ws === ws) {
        device.online = false
        device.lastSeen = Date.now()
        broadcast({ t: 'device', device: summary(device) })
        log(`device disconnected: ${deviceId}`)
      }
    }
  })

  ws.on('error', (error) => {
    log('ws error', error.message)
  })
})

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.readyState !== ws.OPEN) continue
    // Terminate sockets that missed the previous ping so the device list
    // reflects reality and the agent reconnects instead of looking online.
    if ((ws as any).isAlive === false) {
      ws.terminate()
      continue
    }
    ;(ws as any).isAlive = false
    ws.ping()
  }
}, 30_000)

setInterval(() => {
  const now = Date.now()
  for (const [token, expires] of sessions) {
    if (expires < now) sessions.delete(token)
  }
}, 10 * 60 * 1000).unref()

server.listen(PORT, () => {
  log(`RC relay listening on http://0.0.0.0:${PORT}`)
  log(`WebSocket: ws://0.0.0.0:${PORT}/ws`)
  log(`Serving web build from ${WEB_DIST}`)
  log(`${macros.size} macro(s), ${runs.size} run(s) in memory`)
})

function shutdown() {
  clearInterval(heartbeat)
  for (const device of devices.values()) device.ws.close()
  for (const admin of admins) admin.close()
  server.close(() => process.exit(0))
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
