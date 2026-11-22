import { useCallback, useEffect, useRef, useState } from 'react'
import { TARGET_APPS } from '../../shared/protocol'
import type {
  ActionName,
  AppInfo,
  DeviceSummary,
  Element,
  ProbeResult,
  ServerMessage,
  SnapshotMessage
} from '../../shared/protocol'

const TOKEN =
  new URLSearchParams(window.location.search).get('token') ??
  window.localStorage.getItem('rc_token') ??
  'dev'

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`

function nowLabel() {
  return new Date().toLocaleTimeString()
}

export default function App() {
  const [connected, setConnected] = useState(false)
  const [devices, setDevices] = useState<DeviceSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [snapshot, setSnapshot] = useState<SnapshotMessage | null>(null)
  const [busy, setBusy] = useState(false)
  const [log, setLog] = useState<string[]>([])
  const [editing, setEditing] = useState<{ element: Element; text: string } | null>(null)

  const wsRef = useRef<WebSocket | null>(null)
  const selectedRef = useRef<string | null>(null)

  const appendLog = useCallback((line: string) => {
    setLog((prev) => [`${nowLabel()}  ${line}`, ...prev].slice(0, 200))
  }, [])

  useEffect(() => {
    selectedRef.current = selectedId
  }, [selectedId])

  const sendCmd = useCallback(
    (deviceId: string, action: ActionName, extra: Record<string, unknown> = {}) => {
      const ws = wsRef.current
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        appendLog('not connected')
        return
      }
      const cmdId = crypto.randomUUID()
      setBusy(true)
      ws.send(JSON.stringify({ t: 'cmd', cmdId, deviceId, action, ...extra }))
      appendLog(`→ ${action} ${Object.keys(extra).length ? JSON.stringify(extra) : ''}`)
    },
    [appendLog]
  )

  const handleMessage = useCallback(
    (msg: ServerMessage) => {
      switch (msg.t) {
        case 'devices': {
          setDevices(msg.devices)
          setSelectedId((prev) => {
            if (prev && msg.devices.some((d) => d.id === prev)) return prev
            return msg.devices.find((d) => d.online)?.id ?? msg.devices[0]?.id ?? null
          })
          break
        }
        case 'device': {
          const incoming = msg.device
          setDevices((prev) => {
            const exists = prev.some((d) => d.id === incoming.id)
            if (!exists) return [...prev, incoming]
            return prev.map((d) => (d.id === incoming.id ? { ...d, ...incoming } : d))
          })
          break
        }
        case 'apps': {
          setDevices((prev) =>
            prev.map((d) => (d.id === msg.deviceId ? { ...d, apps: msg.apps } : d))
          )
          break
        }
        case 'state': {
          setDevices((prev) =>
            prev.map((d) => (d.id === msg.deviceId ? { ...d, state: msg.state } : d))
          )
          break
        }
        case 'snapshot': {
          setSnapshot(msg)
          setBusy(false)
          appendLog(`snapshot ${msg.id} · ${msg.elements.length} hotspots · ${msg.elapsedMs} ms`)
          break
        }
        case 'probe': {
          const result = msg.result
          setDevices((prev) =>
            prev.map((d) =>
              d.id === msg.deviceId
                ? { ...d, probes: { ...(d.probes ?? {}), [result.pkg]: result } }
                : d
            )
          )
          appendLog(`probe ${result.pkg}: ${result.verdict}`)
          break
        }
        case 'actionResult': {
          setBusy(false)
          appendLog(
            `${msg.ok ? 'ok' : 'error'} ${msg.cmdId}${msg.error ? ` · ${msg.error}` : ''}`
          )
          break
        }
        case 'error': {
          setBusy(false)
          appendLog(`server: ${msg.message}`)
          break
        }
        default:
          break
      }
    },
    [appendLog]
  )

  useEffect(() => {
    let closed = false
    let retry = 0
    let timer: number | undefined

    const connect = () => {
      const ws = new WebSocket(WS_URL)
      wsRef.current = ws
      ws.onopen = () => {
        retry = 0
        setConnected(true)
        appendLog(`connected to ${WS_URL}`)
        ws.send(JSON.stringify({ t: 'hello', role: 'admin', token: TOKEN }))
      }
      ws.onmessage = (event) => {
        try {
          handleMessage(JSON.parse(event.data) as ServerMessage)
        } catch {
          appendLog('bad message from server')
        }
      }
      ws.onclose = () => {
        setConnected(false)
        if (closed) return
        const delay = Math.min(30_000, 1000 * 2 ** retry++)
        appendLog(`disconnected; retrying in ${Math.round(delay / 1000)}s`)
        timer = window.setTimeout(connect, delay)
      }
      ws.onerror = () => ws.close()
    }

    connect()
    return () => {
      closed = true
      window.clearTimeout(timer)
      wsRef.current?.close()
    }
  }, [appendLog, handleMessage])

  useEffect(() => {
    if (!selectedId) return
    setSnapshot(null)
    setEditing(null)
    const device = devices.find((d) => d.id === selectedId)
    if (device?.online) {
      sendCmd(selectedId, 'snapshot')
      sendCmd(selectedId, 'listApps')
    }
    // Selected device changes should not re-run when the device list updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  const selected = devices.find((d) => d.id === selectedId) ?? null
  const apps = selected?.apps ?? []
  const probes = selected?.probes ?? {}

  const tapElement = (element: Element) => {
    if (!selected || !snapshot) return
    sendCmd(selected.id, 'tap', {
      snapshot: snapshot.id,
      element: element.id,
      index: element.i
    })
  }

  const scrollElement = (element: Element, dir: 'up' | 'down') => {
    if (!selected || !snapshot) return
    sendCmd(selected.id, 'scroll', { snapshot: snapshot.id, element: element.id, dir })
  }

  const sendText = () => {
    if (!selected || !snapshot || !editing) return
    sendCmd(selected.id, 'setText', {
      snapshot: snapshot.id,
      element: editing.element.id,
      text: editing.text
    })
    setEditing(null)
  }

  const targetsInstalled = apps.filter((a) => TARGET_APPS.includes(a.pkg))

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">RC</span>
          <div>
            <h1>RC Agent</h1>
            <p>Phase 1 · device console</p>
          </div>
        </div>
        <div className={`conn ${connected ? 'on' : 'off'}`}>
          <span className="dot" />
          {connected ? 'relay connected' : 'reconnecting…'}
        </div>
      </header>

      <div className="layout">
        <aside className="sidebar">
          <div className="sidebar-head">
            <h2>Devices</h2>
            <span className="muted">{devices.length}</span>
          </div>
          {devices.length === 0 && (
            <p className="empty">
              No devices yet. Connect the Android agent to this relay.
            </p>
          )}
          {devices.map((device) => (
            <button
              key={device.id}
              className={`device ${device.id === selectedId ? 'selected' : ''}`}
              onClick={() => setSelectedId(device.id)}
            >
              <div className="device-line">
                <span className={`dot ${device.online ? 'on' : 'off'}`} />
                <strong>{device.name}</strong>
              </div>
              <div className="muted small">
                {device.model ?? device.id} · Android {device.android ?? '?'}
              </div>
              <div className="muted small">
                {device.online ? 'online' : 'offline'} · {device.apps?.length ?? 0} apps
              </div>
            </button>
          ))}
        </aside>

        <main className="main">
          {!selected && (
            <div className="panel empty-panel">
              <h2>Select a device</h2>
              <p className="muted">
                The relay is running. Start the Android agent, point it at this
                host, and it will appear here.
              </p>
            </div>
          )}

          {selected && (
            <>
              <section className="panel">
                <div className="panel-head">
                  <div>
                    <h2>{selected.name}</h2>
                    <p className="muted small">
                      {selected.id} · {selected.model ?? 'unknown'} · Android{' '}
                      {selected.android ?? '?'} · agent {selected.agent ?? '?'}
                    </p>
                  </div>
                  <div className={`conn ${selected.online ? 'on' : 'off'}`}>
                    <span className="dot" />
                    {selected.online ? 'online' : 'offline'}
                  </div>
                </div>
                <StateChips state={selected.state} />
                <div className="toolbar">
                  <button
                    className="btn primary"
                    disabled={!selected.online || busy}
                    onClick={() => sendCmd(selected.id, 'snapshot')}
                  >
                    Capture
                  </button>
                  <button
                    className="btn"
                    disabled={!selected.online || busy || targetsInstalled.length === 0}
                    onClick={() => sendCmd(selected.id, 'probeTargets')}
                  >
                    Probe v1 apps
                  </button>
                  <button
                    className="btn"
                    disabled={!selected.online || busy}
                    onClick={() => sendCmd(selected.id, 'globalAction', { name: 'back' })}
                  >
                    Back
                  </button>
                  <button
                    className="btn"
                    disabled={!selected.online || busy}
                    onClick={() => sendCmd(selected.id, 'globalAction', { name: 'home' })}
                  >
                    Home
                  </button>
                  <button
                    className="btn"
                    disabled={!selected.online || busy}
                    onClick={() => sendCmd(selected.id, 'globalAction', { name: 'recents' })}
                  >
                    Recents
                  </button>
                  <button
                    className="btn"
                    disabled={!selected.online || busy}
                    onClick={() => sendCmd(selected.id, 'listApps')}
                  >
                    Refresh apps
                  </button>
                </div>
              </section>

              <section className="panel">
                <div className="panel-head">
                  <h2>Apps</h2>
                  <span className="muted small">{apps.length} installed</span>
                </div>
                <div className="apps">
                  {apps.map((app) => (
                    <AppRow
                      key={app.pkg}
                      app={app}
                      probe={probes[app.pkg]}
                      disabled={!selected.online || busy}
                      onOpen={() => sendCmd(selected.id, 'launch', { pkg: app.pkg })}
                      onCapture={() => sendCmd(selected.id, 'launch', { pkg: app.pkg })}
                      onProbe={() => sendCmd(selected.id, 'probe', { pkg: app.pkg })}
                    />
                  ))}
                  {apps.length === 0 && (
                    <p className="empty">No app inventory yet. Use Refresh apps.</p>
                  )}
                </div>
              </section>

              <section className="panel">
                <div className="panel-head">
                  <h2>Snapshot</h2>
                  {snapshot && (
                    <span className="muted small">
                      {snapshot.pkg ?? '?'} · {snapshot.w}×{snapshot.h} ·{' '}
                      {snapshot.elements.length} hotspots · {snapshot.nodeCount} nodes ·{' '}
                      {snapshot.elapsedMs} ms
                    </span>
                  )}
                </div>

                {!snapshot && <p className="empty">Capture a screen to see hotspots.</p>}

                {snapshot?.error && (
                  <p className="error">Capture error: {snapshot.error}</p>
                )}

                {snapshot && !snapshot.error && (
                  <>
                    <div
                      className="snapshot"
                      style={{ aspectRatio: `${snapshot.w} / ${snapshot.h}` }}
                    >
                      <img
                        src={`data:image/webp;base64,${snapshot.image}`}
                        alt="Device screen"
                      />
                      {snapshot.elements.map((element) => (
                        <button
                          key={element.id}
                          className="hotspot"
                          title={elementLabel(element)}
                          style={{
                            left: `${element.b[0] * 100}%`,
                            top: `${element.b[1] * 100}%`,
                            width: `${(element.b[2] - element.b[0]) * 100}%`,
                            height: `${(element.b[3] - element.b[1]) * 100}%`
                          }}
                          onClick={() => tapElement(element)}
                          disabled={busy}
                        >
                          <span>{element.i}</span>
                        </button>
                      ))}
                    </div>

                    {editing && (
                      <div className="edit-row">
                        <span className="muted small">
                          Text for #{editing.element.i}
                        </span>
                        <input
                          value={editing.text}
                          onChange={(event) =>
                            setEditing({ ...editing, text: event.target.value })
                          }
                          placeholder="Type text…"
                          autoFocus
                        />
                        <button className="btn primary" onClick={sendText} disabled={busy}>
                          Set text
                        </button>
                        <button className="btn" onClick={() => setEditing(null)}>
                          Cancel
                        </button>
                      </div>
                    )}

                    <div className="elements">
                      {snapshot.elements.map((element) => (
                        <div className="element" key={element.id}>
                          <span className="index">#{element.i}</span>
                          <div className="element-main">
                            <div>{elementLabel(element)}</div>
                            <div className="muted small">
                              {element.cls}
                              {element.vid ? ` · ${element.vid}` : ''}
                              {element.acts.length ? ` · ${element.acts.join(', ')}` : ''}
                            </div>
                          </div>
                          <button
                            className="btn small"
                            onClick={() => tapElement(element)}
                            disabled={busy}
                          >
                            Tap
                          </button>
                          {element.editable && (
                            <button
                              className="btn small"
                              onClick={() => setEditing({ element, text: '' })}
                              disabled={busy}
                            >
                              Type
                            </button>
                          )}
                          {element.scrollable && (
                            <>
                              <button
                                className="btn small"
                                onClick={() => scrollElement(element, 'up')}
                                disabled={busy}
                              >
                                ↑
                              </button>
                              <button
                                className="btn small"
                                onClick={() => scrollElement(element, 'down')}
                                disabled={busy}
                              >
                                ↓
                              </button>
                            </>
                          )}
                        </div>
                      ))}
                    </div>
                  </>
                )}
              </section>
            </>
          )}

          <section className="panel log-panel">
            <div className="panel-head">
              <h2>Log</h2>
              <span className="muted small">{busy ? 'busy…' : 'idle'}</span>
            </div>
            <div className="log">
              {log.length === 0 && <p className="empty">No events yet.</p>}
              {log.map((line, index) => (
                <div key={`${index}-${line}`} className="log-line">
                  {line}
                </div>
              ))}
            </div>
          </section>
        </main>
      </div>
    </div>
  )
}

function StateChips({ state }: { state?: DeviceSummary['state'] }) {
  if (!state) return <p className="muted small">No device state yet.</p>
  return (
    <div className="chips">
      <Chip label="Accessibility" ok={state.a11y} />
      <Chip label="Notifications" ok={state.notifAccess} />
      <Chip label="Screen on" ok={state.screenOn} />
      <Chip label="Unlocked" ok={!state.locked} />
      <Chip label="Device Owner" ok={state.deviceOwner} />
      <Chip label="Stay awake" ok={state.stayAwake} />
      <Chip label={`Battery ${state.battery}%`} ok={state.charging || state.battery > 20} />
      {state.package && <Chip label={state.package} ok />}
    </div>
  )
}

function Chip({ label, ok }: { label: string; ok: boolean }) {
  return <span className={`chip ${ok ? 'ok' : 'bad'}`}>{label}</span>
}

function AppRow({
  app,
  probe,
  disabled,
  onOpen,
  onCapture,
  onProbe
}: {
  app: AppInfo
  probe?: ProbeResult
  disabled: boolean
  onOpen: () => void
  onCapture: () => void
  onProbe: () => void
}) {
  const target = TARGET_APPS.includes(app.pkg)
  return (
    <div className="app-row">
      <div className="app-icon">{app.label.slice(0, 1).toUpperCase()}</div>
      <div className="app-main">
        <div className="app-title">
          {app.label}
          {target && <span className="tag">v1</span>}
          {probe && <VerdictBadge verdict={probe.verdict} />}
        </div>
        <div className="muted small">
          {app.pkg} · {app.version}
        </div>
      </div>
      <div className="app-actions">
        <button className="btn small" onClick={onOpen} disabled={disabled}>
          Open
        </button>
        <button className="btn small" onClick={onCapture} disabled={disabled}>
          Capture
        </button>
        <button className="btn small" onClick={onProbe} disabled={disabled}>
          Probe
        </button>
      </div>
    </div>
  )
}

function VerdictBadge({ verdict }: { verdict: ProbeResult['verdict'] }) {
  return <span className={`verdict ${verdict}`}>{verdict}</span>
}

function elementLabel(element: Element) {
  return (
    element.text?.trim() ||
    element.desc?.trim() ||
    element.cls.split('.').pop() ||
    'element'
  )
}
