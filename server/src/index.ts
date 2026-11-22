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
import { randomUUID, createCipheriv, createDecipheriv, randomBytes, scryptSync } from 'node:crypto'
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

if (IS_PRODUCTION && SECRET_KEY.length < 32) {
  console.error(
    'RC_SECRET_KEY must be set to at least 32 characters in production. ' +
      'Generate one with: openssl rand -base64 48'
  )
  process.exit(1)
}

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
const recordingSessions = new Map<string, RecordingSession>()

function log(...args: unknown[]) {
  console.log(new Date().toISOString(), ...args)
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

const server = createServer((req, res) => {
  const url = new URL(req.url ?? '/', `http://${req.headers.host ?? 'localhost'}`)

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
  if (!existsSync(MACROS_FILE)) {
    const sample: Macro = {
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
      ],
      createdAt: Date.now(),
      updatedAt: Date.now()
    }
    macros.set(sample.id, sample)
    saveMacros()
    return
  }

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

wss.on('connection', (ws) => {
  let role: Role | null = null
  let deviceId: string | null = null

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
        admins.add(ws)
        send(ws, { t: 'devices', devices: [...devices.values()].map(summary) })
        sendMacros(ws)
        sendRuns(ws)
        sendSecrets(ws)
        log('admin connected')
        return
      }

      if (role === 'device') {
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
          const macro = macros.get(String(msg.macroId))
          if (!macro) {
            fail(`unknown macro: ${msg.macroId}`)
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
            fail(error instanceof Error ? error.message : String(error))
            return
          }

          const runId = randomUUID()
          const params =
            msg.params && typeof msg.params === 'object' ? msg.params : {}
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
  for (const device of devices.values()) {
    if (device.online && device.ws.readyState === device.ws.OPEN) device.ws.ping()
  }
  for (const admin of admins) {
    if (admin.readyState === admin.OPEN) admin.ping()
  }
}, 25_000)

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
