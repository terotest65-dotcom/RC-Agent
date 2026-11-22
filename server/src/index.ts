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
import { randomUUID } from 'node:crypto'
import { WebSocketServer, type WebSocket as WsSocket } from 'ws'
import type {
  AppInfo,
  DeviceState,
  DeviceSummary,
  Macro,
  ProbeResult,
  Role,
  RunRecord,
  RunStep,
  ServerMessage
} from '../../shared/protocol'

const here = fileURLToPath(new URL('.', import.meta.url))
const WEB_DIST = process.env.WEB_DIST ?? join(here, '../../web/dist')
const DATA_DIR = process.env.DATA_DIR ?? join(here, '../data')
const MACROS_FILE = join(DATA_DIR, 'macros.json')
const PORT = Number(process.env.PORT ?? 8787)
const TOKEN = process.env.RC_TOKEN ?? 'dev'

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
}

const devices = new Map<string, DeviceRecord>()
const admins = new Set<WsSocket>()
const macros = new Map<string, Macro>()
const runs = new Map<string, RunRecord>()

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
    probes: device.probes
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

loadMacros()

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
      if (msg.token !== TOKEN) {
        fail('bad token')
        ws.close()
        return
      }
      role = msg.role as Role

      if (role === 'admin') {
        admins.add(ws)
        send(ws, { t: 'devices', devices: [...devices.values()].map(summary) })
        sendMacros(ws)
        sendRuns(ws)
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
        case 'recordEvent':
          broadcast({ t: 'recordEvent', deviceId, event: msg.event })
          break
        case 'recordingState':
          broadcast({ t: 'recordingState', deviceId, recording: !!msg.recording })
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

      if (msg.t === 'cmd') {
        const target = msg.deviceId ? devices.get(String(msg.deviceId)) : undefined
        if (!target || !target.online) {
          fail(`device offline: ${msg.deviceId ?? '?'}`)
          return
        }

        if (msg.action === 'runMacro') {
          const macro = macros.get(String(msg.macroId))
          if (!macro) {
            fail(`unknown macro: ${msg.macroId}`)
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
              macro,
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
  log(`WebSocket: ws://0.0.0.0:${PORT}/ws  token=${TOKEN}`)
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
