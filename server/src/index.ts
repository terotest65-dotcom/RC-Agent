import { createServer, type IncomingMessage, type ServerResponse } from 'node:http'
import { existsSync, readFileSync, statSync } from 'node:fs'
import { extname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import { WebSocketServer, type WebSocket as WsSocket } from 'ws'
import type {
  AppInfo,
  DeviceState,
  DeviceSummary,
  ProbeResult,
  Role,
  ServerMessage
} from '../../shared/protocol'

const here = fileURLToPath(new URL('.', import.meta.url))
const WEB_DIST = process.env.WEB_DIST ?? join(here, '../../web/dist')
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

function log(...args: unknown[]) {
  console.log(new Date().toISOString(), ...args)
}

function json(res: ServerResponse, value: unknown) {
  const body = JSON.stringify(value)
  res.writeHead(200, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store'
  })
  res.end(body)
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
      uptime: Math.round(process.uptime())
    })
    return
  }

  if (url.pathname === '/api/devices') {
    json(res, [...devices.values()].map(summary))
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

function send(ws: WsSocket, message: ServerMessage) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(message))
}

function broadcast(message: ServerMessage) {
  const payload = JSON.stringify(message)
  for (const admin of admins) {
    if (admin.readyState === admin.OPEN) admin.send(payload)
  }
}

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
        default:
          break
      }
      return
    }

    if (role === 'admin') {
      if (msg.t === 'cmd') {
        const target = msg.deviceId ? devices.get(String(msg.deviceId)) : undefined
        if (!target || !target.online) {
          fail(`device offline: ${msg.deviceId ?? '?'}`)
          return
        }
        target.ws.send(JSON.stringify({ ...msg, deviceId: target.id }))
        return
      }
      if (msg.t === 'list') {
        send(ws, { t: 'devices', devices: [...devices.values()].map(summary) })
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
})

function shutdown() {
  clearInterval(heartbeat)
  for (const device of devices.values()) device.ws.close()
  for (const admin of admins) admin.close()
  server.close(() => process.exit(0))
}

process.on('SIGINT', shutdown)
process.on('SIGTERM', shutdown)
