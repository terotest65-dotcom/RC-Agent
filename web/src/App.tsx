import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type {
  ActionName,
  AppInfo,
  DeviceSummary,
  Element,
  Macro,
  PromptMessage,
  RecordEvent,
  RunRecord,
  SecretInfo,
  ServerMessage,
  SnapshotMessage
} from '../../shared/protocol'

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws`

const DEFAULT_MACRO = `{
  "name": "Chrome — open example.com",
  "appPackage": "com.android.chrome",
  "steps": [
    { "op": "launch", "package": "com.android.chrome" },
    { "op": "waitForIdle" },
    { "op": "screenshot", "label": "chrome" }
  ]
}`

type Tab = 'control' | 'macros' | 'recorder' | 'runs' | 'secrets'

interface ActiveSession {
  id: string
  appPackage?: string
  appLabel?: string
  name?: string
}

function nowLabel() {
  return new Date().toLocaleTimeString()
}

const HAS_MASK = /[•●○◦∙·⋅▪▫■□◆◇‣⁃⦁⦿⊙◉*]/
const STRIP_MASK = /[•●○◦∙·⋅▪▫■□◆◇‣⁃⦁⦿⊙◉*]/g

/**
 * Masked fields often report "1", "•2", "••3", "•••4". Strip the mask
 * characters and accumulate the visible characters so each step reads as
 * "1", "12", "123", "1234". Already-cleaned events pass through unchanged.
 */
function normalizeRecordEvents(events: RecordEvent[]): RecordEvent[] {
  const chronological = [...events].reverse()
  const typed = new Map<string, string>()
  const visible = new Map<string, string>()
  const normalized: RecordEvent[] = []

  for (const event of chronological) {
    if (event.type !== 'text' || event.redacted) {
      normalized.push(event)
      continue
    }

    const key = `${event.app ?? ''}:${event.viewId ?? event.className ?? ''}`
    const raw = event.text ?? ''

    if (HAS_MASK.test(raw)) {
      const clean = raw.replace(STRIP_MASK, '')
      const typedSoFar = typed.get(key) ?? ''
      const previousVisible = visible.get(key) ?? ''
      const appended =
        clean === ''
          ? ''
          : typedSoFar.endsWith(clean)
            ? ''
            : clean.startsWith(previousVisible)
              ? clean.slice(previousVisible.length)
              : clean
      const next = typedSoFar + appended
      visible.set(key, clean)
      typed.set(key, next)
      normalized.push({
        ...event,
        text: next,
        delta: appended,
        before: typedSoFar,
        masked: true
      })
    } else {
      typed.set(key, raw)
      visible.set(key, raw)
      normalized.push({ ...event, masked: event.masked ?? false })
    }
  }

  return normalized.reverse()
}

export default function App() {
  const [connected, setConnected] = useState(false)
  const [devices, setDevices] = useState<DeviceSummary[]>([])
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [snapshot, setSnapshot] = useState<SnapshotMessage | null>(null)
  const [busy, setBusy] = useState(false)
  const [log, setLog] = useState<string[]>([])
  const [editing, setEditing] = useState<{ element: Element; text: string } | null>(null)

  const [tab, setTab] = useState<Tab>('control')
  const [macros, setMacros] = useState<Macro[]>([])
  const [macroDraft, setMacroDraft] = useState(DEFAULT_MACRO)
  const [macroError, setMacroError] = useState<string | null>(null)
  const [runs, setRuns] = useState<RunRecord[]>([])
  const [selectedRunId, setSelectedRunId] = useState<string | null>(null)
  const [recording, setRecording] = useState(false)
  const [recordEvents, setRecordEvents] = useState<RecordEvent[]>([])

  const [secrets, setSecrets] = useState<SecretInfo[]>([])
  const [secretName, setSecretName] = useState('')
  const [secretValue, setSecretValue] = useState('')
  const [prompt, setPrompt] = useState<PromptMessage | null>(null)
  const [promptValue, setPromptValue] = useState('')
  const [gridMode, setGridMode] = useState(false)
  const [activeSession, setActiveSession] = useState<ActiveSession | null>(null)
  const [typing, setTyping] = useState('')
  const [showLog, setShowLog] = useState(false)
  const [appQuery, setAppQuery] = useState('')
  const [macroEditorOpen, setMacroEditorOpen] = useState(false)

  const wsRef = useRef<WebSocket | null>(null)
  const selectedRef = useRef<string | null>(null)
  const activeSessionRef = useRef<ActiveSession | null>(null)

  const appendLog = useCallback((line: string) => {
    setLog((prev) => [`${nowLabel()}  ${line}`, ...prev].slice(0, 250))
  }, [])

  useEffect(() => {
    selectedRef.current = selectedId
  }, [selectedId])

  useEffect(() => {
    activeSessionRef.current = activeSession
  }, [activeSession])

  const sendRaw = useCallback(
    (message: object) => {
      const ws = wsRef.current
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        appendLog('not connected')
        return
      }
      ws.send(JSON.stringify(message))
    },
    [appendLog]
  )

  const sendCmd = useCallback(
    (
      deviceId: string,
      action: ActionName,
      extra: Record<string, unknown> = {},
      trackBusy = true
    ) => {
      if (trackBusy) setBusy(true)
      sendRaw({ t: 'cmd', cmdId: crypto.randomUUID(), deviceId, action, ...extra })
      appendLog(
        `→ ${action} ${Object.keys(extra).length ? JSON.stringify(extra) : ''}`
      )
    },
    [appendLog, sendRaw]
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
        case 'apps':
          setDevices((prev) =>
            prev.map((d) => (d.id === msg.deviceId ? { ...d, apps: msg.apps } : d))
          )
          break
        case 'state':
          setDevices((prev) =>
            prev.map((d) => (d.id === msg.deviceId ? { ...d, state: msg.state } : d))
          )
          break
        case 'snapshot':
          if (msg.deviceId !== selectedRef.current) break
          setSnapshot(msg)
          setBusy(false)
          appendLog(
            `snapshot ${msg.id} · ${msg.elements.length} hotspots · ${msg.elapsedMs} ms`
          )
          break
        case 'probe':
          break
        case 'actionResult':
          setBusy(false)
          appendLog(
            `${msg.ok ? 'ok' : 'error'} ${msg.cmdId}${msg.error ? ` · ${msg.error}` : ''}`
          )
          break
        case 'error':
          setBusy(false)
          appendLog(`server: ${msg.message}`)
          break
        case 'macros':
          setMacros(msg.macros)
          break
        case 'macroSaved':
          setMacros((prev) => {
            const exists = prev.some((m) => m.id === msg.macro.id)
            return exists
              ? prev.map((m) => (m.id === msg.macro.id ? msg.macro : m))
              : [...prev, msg.macro]
          })
          setMacroDraft(JSON.stringify(msg.macro, null, 2))
          appendLog(`macro saved: ${msg.macro.name}`)
          break
        case 'runs':
          setRuns(msg.runs)
          setSelectedRunId((prev) => prev ?? msg.runs[0]?.id ?? null)
          break
        case 'runStarted':
          setRuns((prev) => [
            msg.run,
            ...prev.filter((r) => r.id !== msg.run.id)
          ])
          setSelectedRunId(msg.run.id)
          appendLog(`run started: ${msg.run.macroName}`)
          break
        case 'runStep':
          setRuns((prev) =>
            prev.map((run) => {
              if (run.id !== msg.runId) return run
              const steps = [
                ...run.steps.filter((s) => s.i !== msg.step.i),
                msg.step
              ].sort((a, b) => a.i - b.i)
              return { ...run, steps }
            })
          )
          appendLog(`run step ${msg.step.i + 1}: ${msg.step.op} ${msg.step.status}`)
          break
        case 'runFinished':
          setRuns((prev) =>
            prev.map((run) => (run.id === msg.run.id ? msg.run : run))
          )
          appendLog(`run finished: ${msg.run.status}`)
          break
        case 'recordEvent':
          if (msg.deviceId !== selectedRef.current) break
          if (
            msg.sessionId &&
            activeSessionRef.current &&
            msg.sessionId !== activeSessionRef.current.id
          ) {
            break
          }
          setRecordEvents((prev) => [msg.event, ...prev].slice(0, 300))
          break
        case 'recordingState':
          if (msg.deviceId !== selectedRef.current) break
          if (
            msg.sessionId &&
            activeSessionRef.current &&
            msg.sessionId !== activeSessionRef.current.id
          ) {
            break
          }
          setRecording(msg.recording)
          appendLog(msg.recording ? 'recording started' : 'recording stopped')
          if (!msg.recording) setActiveSession(null)
          break
        case 'secrets':
          setSecrets(msg.secrets)
          break
        case 'secretSaved':
          appendLog(`secret saved: ${msg.secret.name}`)
          break
        case 'prompt':
          setPrompt(msg)
          setPromptValue('')
          appendLog(`prompt: ${msg.message}`)
          break
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
        ws.send(JSON.stringify({ t: 'hello', role: 'admin' }))
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
    setRecordEvents([])
    setRecording(false)
    setActiveSession(null)
    const device = devices.find((d) => d.id === selectedId)
    if (device?.online) {
      sendCmd(selectedId, 'snapshot', {}, false)
      sendCmd(selectedId, 'listApps', {}, false)
    }
    // Selected device changes should not re-run when the device list updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  const selected = devices.find((d) => d.id === selectedId) ?? null
  const apps = selected?.apps ?? []
  const filteredApps = useMemo(() => {
    const query = appQuery.trim().toLowerCase()
    if (!query) return apps
    return apps.filter(
      (app) =>
        app.label.toLowerCase().includes(query) ||
        app.pkg.toLowerCase().includes(query)
    )
  }, [apps, appQuery])
  const activeRun = selected
    ? runs.find((r) => r.deviceId === selected.id && r.status === 'running')
    : undefined
  const actionDisabled = !selected?.online || busy
  const runDisabled = actionDisabled || !!activeRun

  const appLabel = (pkg?: string | null) =>
    apps.find((a) => a.pkg === pkg)?.label ?? pkg ?? 'unknown'

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

  const startAppRecording = (app: AppInfo) => {
    if (!selected) return
    const sessionId = crypto.randomUUID()
    const name = `${app.label} — recorded ${new Date().toLocaleTimeString()}`
    setTab('recorder')
    setActiveSession({
      id: sessionId,
      appPackage: app.pkg,
      appLabel: app.label,
      name
    })
    setRecordEvents([])
    setRecording(true)
    sendCmd(
      selected.id,
      'startRecording',
      { sessionId, appPackage: app.pkg, appLabel: app.label, name },
      false
    )
    // Do not launch the app: the user opens it and recording stops when
    // they leave it or press Stop & save.
  }

  const stopRecording = () => {
    if (!selected) return
    const session = activeSession
    setRecording(false)
    setActiveSession(null)
    sendCmd(
      selected.id,
      'stopRecording',
      { sessionId: session?.id },
      false
    )
  }

  const runMacro = (macro: Macro, params: Record<string, string>) => {
    if (!selected) return
    setTab('runs')
    sendCmd(selected.id, 'runMacro', { macroId: macro.id, params }, false)
  }

  const saveMacro = () => {
    try {
      const parsed = JSON.parse(macroDraft)
      if (!parsed.name || !Array.isArray(parsed.steps)) {
        throw new Error('"name" and "steps" are required')
      }
      sendRaw({ t: 'macroSave', macro: parsed })
      setMacroError(null)
    } catch (error) {
      setMacroError(error instanceof Error ? error.message : String(error))
    }
  }

  const editMacro = (macro: Macro) => {
    setMacroDraft(JSON.stringify(macro, null, 2))
    setMacroError(null)
    setMacroEditorOpen(true)
    setTab('macros')
  }

  const deleteMacro = (macro: Macro) => {
    sendRaw({ t: 'macroDelete', macroId: macro.id })
  }

  const saveSecret = () => {
    const name = secretName.trim()
    if (!name || !secretValue) return
    sendRaw({ t: 'secretSave', name, value: secretValue })
    setSecretName('')
    setSecretValue('')
  }

  const deleteSecret = (id: string) => {
    sendRaw({ t: 'secretDelete', secretId: id })
  }

  const submitPrompt = () => {
    if (!prompt) return
    sendCmd(
      prompt.deviceId,
      'promptResult',
      { promptId: prompt.promptId, value: promptValue },
      false
    )
    setPrompt(null)
    setPromptValue('')
  }

  const tapXY = (x: number, y: number) => {
    if (!selected) return
    sendCmd(selected.id, 'tapXY', { x, y })
  }

  const sendTyping = (submit: boolean) => {
    if (!selected || !typing.trim()) return
    sendCmd(selected.id, 'inputText', { text: typing, submit })
    setTyping('')
  }

  const draftMacro = () => {
    const chronological = [...normalizeRecordEvents(recordEvents)]
      .reverse()
      .filter((event) => event.app !== 'com.acme.rc')
    const steps: any[] = []
    const firstApp =
      chronological.find((event) => event.type === 'tap' && event.app)?.app ??
      chronological.find((event) => event.type === 'text' && event.app)?.app ??
      chronological.find((event) => event.app)?.app

    const launchPackage = (pkg: string) => {
      if (apps.some((app) => app.pkg === pkg)) return pkg
      if (pkg.startsWith('com.google.android.settings')) return 'com.android.settings'
      return pkg
    }

    if (firstApp) steps.push({ op: 'launch', package: launchPackage(firstApp) })
    steps.push({ op: 'waitForIdle' })

    const textStepByField = new Map<string, number>()
    for (const event of chronological) {
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
        const index = textStepByField.get(key)
        if (index !== undefined) {
          steps[index].text = event.text ?? ''
          if (event.masked) steps[index].secret = true
        } else {
          const newIndex = steps.length
          const step: any = {
            op: 'setText',
            selector,
            text: event.text ?? ''
          }
          if (event.masked) step.secret = true
          steps.push(step)
          textStepByField.set(key, newIndex)
        }
      } else if (event.type === 'scroll') {
        const selector = event.viewId
          ? { viewId: event.viewId }
          : event.className
            ? { className: event.className }
            : null
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
    const draft = {
      name: `Recorded ${new Date().toLocaleTimeString()}`,
      appPackage: firstApp,
      steps
    }
    setMacroDraft(JSON.stringify(draft, null, 2))
    setMacroError(null)
    setTab('macros')
    appendLog(`drafted macro with ${steps.length} steps`)
  }


  return (
    <div className="shell">
      <aside className="rail">
        <div className="rail-brand">
          <div className="brand-mark">RC</div>
          <div className="brand-copy">
            <strong>RC Agent</strong>
            <span>Device control</span>
          </div>
        </div>

        <div className="rail-section">
          <div className="rail-heading">
            <span>Devices</span>
            <span className="count">{devices.length}</span>
          </div>
          <div className="device-list">
            {devices.length === 0 && (
              <div className="rail-empty">No devices connected</div>
            )}
            {devices.map((device) => (
              <button
                key={device.id}
                className={`device-item ${device.id === selectedId ? 'active' : ''}`}
                onClick={() => setSelectedId(device.id)}
              >
                <span className={`status-dot ${device.online ? 'online' : ''}`} />
                <span className="device-avatar">
                  {device.name.slice(0, 1).toUpperCase()}
                </span>
                <span className="device-copy">
                  <strong>{device.name}</strong>
                  <span>{device.model ?? device.id}</span>
                </span>
                <span className="device-count">{device.apps?.length ?? 0}</span>
              </button>
            ))}
          </div>
        </div>

        <div className="rail-footer">
          <div className={`relay-state ${connected ? 'online' : ''}`}>
            <span className={`status-dot ${connected ? 'online' : ''}`} />
            {connected ? 'Relay connected' : 'Reconnecting…'}
          </div>
          <div className="relay-url">{WS_URL.replace(/^wss?:\/\//, '')}</div>
        </div>
      </aside>

      <main className="workspace">
        {prompt && (
          <div className="prompt-banner">
            <div className="prompt-copy">
              <strong>Verification required</strong>
              <span>
                {prompt.message} · run {prompt.runId.slice(0, 8)}
              </span>
            </div>
            <input
              value={promptValue}
              onChange={(event) => setPromptValue(event.target.value)}
              placeholder="Enter OTP"
              autoFocus
            />
            <button className="btn primary" onClick={submitPrompt}>
              Send
            </button>
            <button className="btn ghost" onClick={() => setPrompt(null)}>
              Dismiss
            </button>
          </div>
        )}

        {!selected ? (
          <div className="empty-workspace">
            <div className="empty-mark">▣</div>
            <h2>No device selected</h2>
            <p>Connect the Android agent to the relay, then choose a device.</p>
          </div>
        ) : (
          <>
            <header className="workspace-header">
              <div className="workspace-title">
                <div className="eyebrow">
                  <span className={`status-dot ${selected.online ? 'online' : ''}`} />
                  {selected.online ? 'Online' : 'Offline'}
                </div>
                <h1>{selected.name}</h1>
                <p>
                  {selected.model ?? 'Unknown device'} · Android{' '}
                  {selected.android ?? '?'} · {selected.id}
                </p>
              </div>
              <div className="header-actions">
                <button
                  className="btn"
                  disabled={actionDisabled}
                  onClick={() => sendCmd(selected.id, 'snapshot')}
                >
                  Capture
                </button>
                {recording && (
                  <button className="btn danger" onClick={stopRecording}>
                    Stop recording
                  </button>
                )}
                <button
                  className={`btn ghost ${showLog ? 'active' : ''}`}
                  onClick={() => setShowLog((value) => !value)}
                >
                  Activity{log.length ? ` · ${log.length}` : ''}
                </button>
              </div>
            </header>

            <div className="state-bar">
              <StateChips state={selected.state} />
            </div>

            <nav className="tabs">
              {(
                [
                  ['control', 'Control'],
                  ['macros', 'Macros'],
                  ['recorder', 'Recorder'],
                  ['runs', 'Runs'],
                  ['secrets', 'Secrets']
                ] as Array<[Tab, string]>
              ).map(([key, label]) => (
                <button
                  key={key}
                  className={`tab ${tab === key ? 'active' : ''}`}
                  onClick={() => setTab(key)}
                >
                  {label}
                  {key === 'recorder' && recording && <span className="rec-dot" />}
                  {key === 'runs' && activeRun && <span className="rec-dot" />}
                </button>
              ))}
            </nav>

            <div className="workspace-body">
              {tab === 'control' && (
                <div className="control-grid">
                  <div className="control-main">
                    <div className="composer-card">
                      <div className="composer-copy">
                        <strong>Type into device</strong>
                        <span>Uses the focused text field. No keyboard taps needed.</span>
                      </div>
                      <div className="composer-row">
                        <input
                          value={typing}
                          onChange={(event) => setTyping(event.target.value)}
                          onKeyDown={(event) => {
                            if (event.key === 'Enter' && !event.shiftKey) {
                              event.preventDefault()
                              sendTyping(true)
                            }
                          }}
                          placeholder="Type a message or value…"
                        />
                        <button
                          className="btn"
                          disabled={busy || !typing.trim()}
                          onClick={() => sendTyping(false)}
                        >
                          Insert
                        </button>
                        <button
                          className="btn primary"
                          disabled={busy || !typing.trim()}
                          onClick={() => sendTyping(true)}
                        >
                          Insert + Enter
                        </button>
                      </div>
                    </div>

                    <SnapshotCard
                      snapshot={snapshot}
                      busy={busy}
                      editing={editing}
                      setEditing={setEditing}
                      onTapElement={tapElement}
                      onScrollElement={scrollElement}
                      onSendText={sendText}
                      gridMode={gridMode}
                      onToggleGrid={() => setGridMode((value) => !value)}
                      onTapXY={tapXY}
                    />
                  </div>

                  <aside className="control-side">
                    <section className="card apps-card">
                      <div className="card-header">
                        <div>
                          <h2>Apps</h2>
                          <span className="sub">{apps.length} installed</span>
                        </div>
                        <input
                          className="search-input"
                          value={appQuery}
                          onChange={(event) => setAppQuery(event.target.value)}
                          placeholder="Search"
                        />
                      </div>
                      <div className="app-list">
                        {filteredApps.map((app) => (
                          <AppRow
                            key={app.pkg}
                            app={app}
                            disabled={runDisabled}
                            onOpen={() =>
                              sendCmd(selected.id, 'launch', { pkg: app.pkg })
                            }
                            onRecord={() => startAppRecording(app)}
                          />
                        ))}
                        {filteredApps.length === 0 && (
                          <p className="empty">No matching apps.</p>
                        )}
                      </div>
                      <div className="app-hint">
                        <span className="status-dot online" /> Record captures a
                        macro for that app.
                      </div>
                    </section>
                  </aside>
                </div>
              )}

              {tab === 'macros' && (
                <section className="card">
                  <div className="card-header">
                    <div>
                      <h2>Macros</h2>
                      <span className="sub">{macros.length} saved</span>
                    </div>
                    <div className="card-actions">
                      <button
                        className="btn ghost"
                        onClick={() => sendRaw({ t: 'macroList' })}
                      >
                        Refresh
                      </button>
                      <button
                        className="btn primary"
                        onClick={() => {
                          setMacroDraft(DEFAULT_MACRO)
                          setMacroError(null)
                          setMacroEditorOpen(true)
                        }}
                      >
                        New macro
                      </button>
                    </div>
                  </div>

                  <div className="macro-layout">
                    <div className="macro-list">
                      {macros.map((macro) => (
                        <MacroRow
                          key={macro.id}
                          macro={macro}
                          disabled={runDisabled}
                          onRun={runMacro}
                          onEdit={editMacro}
                          onDelete={deleteMacro}
                        />
                      ))}
                      {macros.length === 0 && (
                        <p className="empty">No macros yet.</p>
                      )}
                    </div>

                    {macroEditorOpen ? (
                      <div className="macro-editor-panel">
                        <div className="card-header compact">
                          <h2>Edit JSON</h2>
                          <div className="card-actions">
                            <button
                              className="btn ghost small"
                              onClick={() => setMacroEditorOpen(false)}
                            >
                              Close
                            </button>
                            <button
                              className="btn primary small"
                              onClick={saveMacro}
                            >
                              Save
                            </button>
                          </div>
                        </div>
                        {macroError && <p className="error small">{macroError}</p>}
                        <textarea
                          value={macroDraft}
                          onChange={(event) => setMacroDraft(event.target.value)}
                          spellCheck={false}
                        />
                      </div>
                    ) : (
                      <div className="editor-placeholder">
                        <span>Select a macro to edit</span>
                        <small>or create a new one</small>
                      </div>
                    )}
                  </div>
                </section>
              )}

              {tab === 'recorder' && (
                <RecorderPanel
                  recording={recording}
                  events={recordEvents}
                  appLabel={appLabel}
                  sessionLabel={
                    activeSession?.appLabel ??
                    (activeSession ? 'ad-hoc session' : null)
                  }
                  onStop={stopRecording}
                  onClear={() => setRecordEvents([])}
                  onDraft={draftMacro}
                />
              )}

              {tab === 'runs' && (
                <RunsPanel
                  runs={runs}
                  selectedId={selectedRunId}
                  onSelect={setSelectedRunId}
                />
              )}

              {tab === 'secrets' && (
                <SecretsPanel
                  secrets={secrets}
                  name={secretName}
                  value={secretValue}
                  onName={setSecretName}
                  onValue={setSecretValue}
                  onSave={saveSecret}
                  onDelete={deleteSecret}
                />
              )}
            </div>

            {showLog && (
              <section className="activity-drawer">
                <div className="card-header compact">
                  <h2>Activity</h2>
                  <button
                    className="btn ghost small"
                    onClick={() => setShowLog(false)}
                  >
                    Hide
                  </button>
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
            )}
          </>
        )}
      </main>
    </div>
  )
}

function StateChips({ state }: { state?: DeviceSummary['state'] }) {
  if (!state) return <span className="muted small">No device state yet.</span>
  return (
    <div className="chips">
      <Chip label="Accessibility" ok={state.a11y} />
      <Chip label="Notifications" ok={state.notifAccess} />
      <Chip label="Screen on" ok={state.screenOn} />
      <Chip label="Unlocked" ok={!state.locked} />
      <Chip label="Device Owner" ok={state.deviceOwner} />
      <Chip label="Stay awake" ok={state.stayAwake} />
      <Chip
        label={`Battery ${state.battery}%`}
        ok={state.charging || state.battery > 20}
      />
      {state.package && <Chip label={state.package} ok />}
    </div>
  )
}

function Chip({ label, ok }: { label: string; ok: boolean }) {
  return <span className={`chip ${ok ? 'ok' : 'bad'}`}>{label}</span>
}

function AppRow({
  app,
  disabled,
  onOpen,
  onRecord
}: {
  app: AppInfo
  disabled: boolean
  onOpen: () => void
  onRecord: () => void
}) {
  return (
    <div className="app-row">
      <div className="app-icon">{app.label.slice(0, 1).toUpperCase()}</div>
      <div className="app-copy">
        <strong>{app.label}</strong>
        <span>{app.pkg}</span>
      </div>
      <div className="app-actions">
        <button
          className="icon-btn"
          title="Open app"
          onClick={onOpen}
          disabled={disabled}
        >
          ↗
        </button>
        <button
          className="icon-btn accent"
          title="Record a macro for this app"
          onClick={onRecord}
          disabled={disabled}
        >
          ●
        </button>
      </div>
    </div>
  )
}

function elementLabel(element: Element) {
  return (
    element.text?.trim() ||
    element.desc?.trim() ||
    element.cls.split('.').pop() ||
    'element'
  )
}

function SnapshotCard({
  snapshot,
  busy,
  editing,
  setEditing,
  onTapElement,
  onScrollElement,
  onSendText,
  gridMode,
  onToggleGrid,
  onTapXY
}: {
  snapshot: SnapshotMessage | null
  busy: boolean
  editing: { element: Element; text: string } | null
  setEditing: (value: { element: Element; text: string } | null) => void
  onTapElement: (element: Element) => void
  onScrollElement: (element: Element, dir: 'up' | 'down') => void
  onSendText: () => void
  gridMode: boolean
  onToggleGrid: () => void
  onTapXY: (x: number, y: number) => void
}) {
  return (
    <section className="card snapshot-card">
      <div className="card-header">
        <div>
          <h2>Device screen</h2>
          <span className="sub">
            {snapshot
              ? `${snapshot.pkg ?? 'unknown'} · ${snapshot.w}×${snapshot.h} · ${snapshot.elements.length} hotspots`
              : 'No capture yet'}
          </span>
        </div>
        <button className="btn ghost small" onClick={onToggleGrid}>
          {gridMode ? 'Hotspots' : 'Grid'}
        </button>
      </div>

      {snapshot?.error && <p className="error">{snapshot.error}</p>}

      {!snapshot && (
        <div className="viewport-empty">
          <span>No screen captured</span>
          <small>Use Capture to fetch the device screen.</small>
        </div>
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
            {gridMode ? (
              <GridOverlay onTap={onTapXY} />
            ) : (
              snapshot.elements.map((element) => (
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
                  onClick={() => onTapElement(element)}
                  disabled={busy}
                >
                  <span>{element.i}</span>
                </button>
              ))
            )}
          </div>

          <p className="hint">
            {gridMode
              ? 'Grid mode: tap a cell to send a coordinate tap.'
              : 'Tap a numbered hotspot to tap that element. The keyboard is visible in the image; use the composer above to type.'}
          </p>

          {editing && (
            <div className="edit-row">
              <span className="muted small">Text for #{editing.element.i}</span>
              <input
                value={editing.text}
                onChange={(event) =>
                  setEditing({ ...editing, text: event.target.value })
                }
                placeholder="Type text…"
                autoFocus
              />
              <button className="btn primary" onClick={onSendText} disabled={busy}>
                Set text
              </button>
              <button className="btn ghost" onClick={() => setEditing(null)}>
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
                  onClick={() => onTapElement(element)}
                  disabled={busy}
                >
                  Tap
                </button>
                {element.editable && (
                  <button
                    className="btn small ghost"
                    onClick={() => setEditing({ element, text: '' })}
                    disabled={busy}
                  >
                    Type
                  </button>
                )}
                {element.scrollable && (
                  <>
                    <button
                      className="btn small ghost"
                      onClick={() => onScrollElement(element, 'up')}
                      disabled={busy}
                    >
                      ↑
                    </button>
                    <button
                      className="btn small ghost"
                      onClick={() => onScrollElement(element, 'down')}
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
  )
}

function MacroRow({
  macro,
  disabled,
  onRun,
  onEdit,
  onDelete
}: {
  macro: Macro
  disabled: boolean
  onRun: (macro: Macro, params: Record<string, string>) => void
  onEdit: (macro: Macro) => void
  onDelete: (macro: Macro) => void
}) {
  const keys = Object.keys(macro.params ?? {})
  const [values, setValues] = useState<Record<string, string>>(() => {
    const initial: Record<string, string> = {}
    for (const key of keys) {
      const value = macro.params?.[key]?.default
      initial[key] = value == null ? '' : String(value)
    }
    return initial
  })

  return (
    <div className="macro-row">
      <div className="macro-main">
        <div className="app-title">
          {macro.name}
          <span className="muted small">{macro.steps.length} steps</span>
        </div>
        <div className="muted small">
          {macro.appPackage ?? 'any app'} · {macro.id}
        </div>
        {keys.length > 0 && (
          <div className="params">
            {keys.map((key) => (
              <label key={key} className="param">
                <span>{key}</span>
                <input
                  value={values[key] ?? ''}
                  onChange={(event) =>
                    setValues({ ...values, [key]: event.target.value })
                  }
                />
              </label>
            ))}
          </div>
        )}
      </div>
      <div className="app-actions">
        <button
          className="btn primary small"
          onClick={() => onRun(macro, values)}
          disabled={disabled}
        >
          Run
        </button>
        <button className="btn ghost small" onClick={() => onEdit(macro)}>
          Edit
        </button>
        <button className="btn ghost small" onClick={() => onDelete(macro)}>
          Delete
        </button>
      </div>
    </div>
  )
}

function RecorderPanel({
  recording,
  events,
  appLabel,
  sessionLabel,
  onStop,
  onClear,
  onDraft
}: {
  recording: boolean
  events: RecordEvent[]
  appLabel: (pkg?: string | null) => string
  sessionLabel: string | null
  onStop: () => void
  onClear: () => void
  onDraft: () => void
}) {
  const [textOnly, setTextOnly] = useState(false)
  const normalized = useMemo(() => normalizeRecordEvents(events), [events])
  const labelFor = (event: RecordEvent) =>
    event.appLabel ?? (event.app?.includes('settings') ? 'Settings' : appLabel(event.app))

  const lastText = normalized.find(
    (event) => event.type === 'text' && !event.redacted && (event.text?.length ?? 0) > 0
  )

  const visibleEvents = textOnly
    ? normalized.filter((event) => event.type === 'text')
    : normalized

  const typedEntries: Array<{ key: string; app: string; text: string; ts: number }> = []
  const seenText = new Set<string>()
  for (const event of normalized) {
    if (event.type !== 'text' || event.redacted) continue
    const text = event.text ?? ''
    if (!text) continue
    const key = `${event.app ?? ''}:${event.viewId ?? event.className ?? ''}`
    if (seenText.has(key)) continue
    seenText.add(key)
    typedEntries.push({ key, app: labelFor(event), text, ts: event.ts })
  }

  const copyTyped = async () => {
    if (typedEntries.length === 0) return
    try {
      await navigator.clipboard.writeText(
        typedEntries.map((entry) => `${entry.app}: ${entry.text}`).join('\n')
      )
    } catch {
      // Clipboard access can be denied; the list is still visible.
    }
  }

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Recorder</h2>
          <span className="sub">
            {recording ? 'Listening for the target app' : 'Idle'}
          </span>
        </div>
        <div className="card-actions">
          {recording && (
            <button className="btn danger" onClick={onStop}>
              {sessionLabel ? 'Stop & save' : 'Stop recording'}
            </button>
          )}
          <button className="btn ghost" onClick={onClear} disabled={events.length === 0}>
            Clear
          </button>
          <button
            className={`btn ghost ${textOnly ? 'active' : ''}`}
            onClick={() => setTextOnly((value) => !value)}
          >
            {textOnly ? 'All events' : 'Text only'}
          </button>
          <button className="btn" onClick={onDraft} disabled={events.length === 0}>
            Draft macro
          </button>
        </div>
      </div>

      {!recording && (
        <div className="recorder-idle">
          Click <strong>●</strong> on an app to start a passive recording. Open the
          app yourself; recording stops when you leave it.
        </div>
      )}

      {sessionLabel && recording && (
        <div className="recording-banner">
          <span className="rec-dot" />
          Recording <strong>{sessionLabel}</strong> — leave the app or click{' '}
          <strong>Stop &amp; save</strong>.
        </div>
      )}

      <div className="recorder-grid">
        <div className="recorder-side">
          <div className="typed-card">
            <div className="muted small">Last text entered</div>
            {lastText ? (
              <div className="typed-text">
                <span className="typed-app">{labelFor(lastText)}</span>
                <span>“{lastText.text}”</span>
              </div>
            ) : (
              <div className="empty">Nothing typed yet.</div>
            )}
          </div>

          <div className="typed-list">
            <div className="card-header compact">
              <h3>Typed text</h3>
              <button
                className="btn ghost small"
                onClick={copyTyped}
                disabled={typedEntries.length === 0}
              >
                Copy all
              </button>
            </div>
            {typedEntries.map((entry) => (
              <div className="typed-row" key={entry.key}>
                <span className="typed-list-app">{entry.app}</span>
                <code>{entry.text}</code>
                <span className="muted small">
                  {new Date(entry.ts).toLocaleTimeString()}
                </span>
              </div>
            ))}
            {typedEntries.length === 0 && <p className="empty">No text entered yet.</p>}
          </div>
        </div>

        <div className="recorder-main">
          <div className="record-events">
            {visibleEvents.map((event, index) => (
              <div className={`record-event ${event.type}`} key={`${event.ts}-${index}`}>
                <span className="record-time">
                  {new Date(event.ts).toLocaleTimeString()}
                </span>
                <span className="record-app">{labelFor(event)}</span>
                <span className="record-type">{event.type}</span>
                <span className="record-body">
                  {event.type === 'text' &&
                    (event.redacted
                      ? '•••• (redacted)'
                      : `${event.text ?? ''}${event.delta ? `  (+${event.delta})` : ''}`)}
                  {event.type === 'tap' && `tapped ${event.label ?? 'element'}`}
                  {event.type === 'scroll' && 'scrolled'}
                  {event.type === 'window' && `opened ${event.activity ?? 'window'}`}
                </span>
              </div>
            ))}
            {visibleEvents.length === 0 && (
              <p className="empty">
                {textOnly
                  ? 'No text events yet.'
                  : 'Events will appear here while recording.'}
              </p>
            )}
          </div>
          <p className="hint">
            {textOnly
              ? 'Showing text events only.'
              : 'Masked bullet fields are cleaned; OS-marked password fields are redacted.'}
          </p>
        </div>
      </div>
    </section>
  )
}

function RunsPanel({
  runs,
  selectedId,
  onSelect
}: {
  runs: RunRecord[]
  selectedId: string | null
  onSelect: (id: string) => void
}) {
  const selected = runs.find((run) => run.id === selectedId) ?? runs[0]

  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Runs</h2>
          <span className="sub">{runs.length} recent</span>
        </div>
      </div>

      <div className="run-layout">
        <div className="run-list">
          {runs.map((run) => (
            <button
              key={run.id}
              className={`run-row ${selected?.id === run.id ? 'selected' : ''}`}
              onClick={() => onSelect(run.id)}
            >
              <RunStatusBadge status={run.status} />
              <span className="run-name">{run.macroName}</span>
              <span className="muted small">
                {run.steps.length} steps · {new Date(run.startedAt).toLocaleTimeString()}
              </span>
            </button>
          ))}
          {runs.length === 0 && (
            <p className="empty">No runs yet. Run a macro from the Macros tab.</p>
          )}
        </div>

        <div className="run-detail">
          {selected ? (
            <RunReport run={selected} />
          ) : (
            <div className="editor-placeholder">
              <span>Select a run</span>
              <small>to inspect its steps</small>
            </div>
          )}
        </div>
      </div>
    </section>
  )
}

function RunStatusBadge({ status }: { status: RunRecord['status'] }) {
  const className =
    status === 'success'
      ? 'controllable'
      : status === 'failed'
        ? 'blocked'
        : 'partial'
  return <span className={`verdict ${className}`}>{status}</span>
}

function RunReport({ run }: { run: RunRecord }) {
  const duration = (run.finishedAt ?? Date.now()) - run.startedAt

  return (
    <div className="run-report">
      <div className="card-header compact">
        <div>
          <h3>{run.macroName}</h3>
          <span className="sub">
            {run.deviceId} · {(duration / 1000).toFixed(1)}s ·{' '}
            {new Date(run.startedAt).toLocaleString()}
          </span>
        </div>
        <RunStatusBadge status={run.status} />
      </div>

      <div className="run-steps">
        {run.steps.map((step) => (
          <div className="run-step" key={step.i}>
            <span className="index">#{step.i + 1}</span>
            <div className="element-main">
              <div>
                {step.op}{' '}
                <span className={`step-status ${step.status}`}>{step.status}</span>
              </div>
              {step.error && <div className="error small">{step.error}</div>}
              {step.snapshot?.image && (
                <details>
                  <summary className="muted small">
                    checkpoint {step.snapshot.w}×{step.snapshot.h}
                  </summary>
                  <img
                    className="checkpoint"
                    src={`data:image/webp;base64,${step.snapshot.image}`}
                    alt="checkpoint"
                  />
                </details>
              )}
            </div>
            <span className="muted small">{step.ms ? `${step.ms} ms` : ''}</span>
          </div>
        ))}
        {run.steps.length === 0 && <p className="empty">Waiting for steps…</p>}
      </div>
    </div>
  )
}

function SecretsPanel({
  secrets,
  name,
  value,
  onName,
  onValue,
  onSave,
  onDelete
}: {
  secrets: SecretInfo[]
  name: string
  value: string
  onName: (value: string) => void
  onValue: (value: string) => void
  onSave: () => void
  onDelete: (id: string) => void
}) {
  return (
    <section className="card">
      <div className="card-header">
        <div>
          <h2>Secrets</h2>
          <span className="sub">{secrets.length} stored</span>
        </div>
      </div>
      <p className="hint">
        Values are encrypted at rest and never returned to the browser. Use{' '}
        <code>"secretRef": "name"</code> in a <code>setText</code> step.
      </p>
      <div className="secret-form">
        <input
          value={name}
          onChange={(event) => onName(event.target.value)}
          placeholder="Secret name (e.g. bank-password)"
        />
        <input
          value={value}
          onChange={(event) => onValue(event.target.value)}
          placeholder="Secret value"
          type="password"
        />
        <button
          className="btn primary"
          onClick={onSave}
          disabled={!name.trim() || !value}
        >
          Save secret
        </button>
      </div>
      <div className="secret-list">
        {secrets.map((secret) => (
          <div className="secret-row" key={secret.id}>
            <span className="secret-name">{secret.name}</span>
            <span className="muted small">
              {new Date(secret.createdAt).toLocaleString()}
            </span>
            <button className="btn ghost small" onClick={() => onDelete(secret.id)}>
              Delete
            </button>
          </div>
        ))}
        {secrets.length === 0 && <p className="empty">No secrets yet.</p>}
      </div>
    </section>
  )
}

function GridOverlay({ onTap }: { onTap: (x: number, y: number) => void }) {
  const cols = 6
  const rows = 10
  return (
    <>
      {Array.from({ length: cols * rows }).map((_, index) => {
        const col = index % cols
        const row = Math.floor(index / cols)
        return (
          <button
            key={index}
            className="grid-cell"
            style={{
              left: `${(col / cols) * 100}%`,
              top: `${(row / rows) * 100}%`,
              width: `${100 / cols}%`,
              height: `${100 / rows}%`
            }}
            onClick={() => onTap((col + 0.5) / cols, (row + 0.5) / rows)}
          >
            <span>{index + 1}</span>
          </button>
        )
      })}
    </>
  )
}
