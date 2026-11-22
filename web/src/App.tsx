import { useCallback, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { TARGET_APPS } from '../../shared/protocol'
import type {
  ActionName,
  AppInfo,
  DeviceSummary,
  Element,
  Macro,
  ProbeResult,
  PromptMessage,
  RecordEvent,
  RunRecord,
  SecretInfo,
  ServerMessage,
  SnapshotMessage
} from '../../shared/protocol'

const TOKEN =
  new URLSearchParams(window.location.search).get('token') ??
  window.localStorage.getItem('rc_token') ??
  'dev'

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
  const probes = selected?.probes ?? {}
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

  const startRecording = () => {
    if (!selected) return
    const sessionId = crypto.randomUUID()
    setTab('recorder')
    setActiveSession({ id: sessionId })
    setRecordEvents([])
    setRecording(true)
    sendCmd(selected.id, 'startRecording', { sessionId }, false)
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
    // Start recording first, then open the app from its main screen.
    sendCmd(selected.id, 'launch', { pkg: app.pkg }, false)
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

  const targetsInstalled = apps.filter((a) => TARGET_APPS.includes(a.pkg))

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="logo">RC</span>
          <div>
            <h1>RC Agent</h1>
            <p>Phase 2 · device console, macros, recorder</p>
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
          {prompt && (
            <div className="prompt-banner">
              <div className="prompt-copy">
                <strong>OTP required</strong>
                <div className="muted small">
                  {prompt.message} · run {prompt.runId.slice(0, 8)}
                </div>
              </div>
              <input
                value={promptValue}
                onChange={(event) => setPromptValue(event.target.value)}
                placeholder="Enter OTP"
                autoFocus
              />
              <button className="btn primary" onClick={submitPrompt}>
                Send OTP
              </button>
              <button className="btn" onClick={() => setPrompt(null)}>
                Dismiss
              </button>
            </div>
          )}

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
                    disabled={actionDisabled}
                    onClick={() => sendCmd(selected.id, 'snapshot')}
                  >
                    Capture
                  </button>
                  <button
                    className="btn"
                    disabled={actionDisabled || targetsInstalled.length === 0}
                    onClick={() => sendCmd(selected.id, 'probeTargets', {}, false)}
                  >
                    Probe v1 apps
                  </button>
                  <button
                    className={`btn ${recording ? 'recording' : ''}`}
                    disabled={!selected.online || !!activeRun}
                    onClick={() => {
                      setTab('recorder')
                      if (recording) stopRecording()
                      else startRecording()
                    }}
                  >
                    {recording ? '● Stop recording' : 'Record'}
                  </button>
                  <button
                    className="btn"
                    disabled={actionDisabled}
                    onClick={() => sendCmd(selected.id, 'globalAction', { name: 'back' })}
                  >
                    Back
                  </button>
                  <button
                    className="btn"
                    disabled={actionDisabled}
                    onClick={() => sendCmd(selected.id, 'globalAction', { name: 'home' })}
                  >
                    Home
                  </button>
                  <button
                    className="btn"
                    disabled={actionDisabled}
                    onClick={() => sendCmd(selected.id, 'globalAction', { name: 'recents' })}
                  >
                    Recents
                  </button>
                  <button
                    className="btn"
                    disabled={actionDisabled}
                    onClick={() => sendCmd(selected.id, 'listApps', {}, false)}
                  >
                    Refresh apps
                  </button>
                </div>
              </section>

              <div className="tabs">
                <TabButton active={tab === 'control'} onClick={() => setTab('control')}>
                  Control
                </TabButton>
                <TabButton active={tab === 'macros'} onClick={() => setTab('macros')}>
                  Macros
                </TabButton>
                <TabButton active={tab === 'recorder'} onClick={() => setTab('recorder')}>
                  Recorder {recording && <span className="rec-dot" />}
                </TabButton>
                <TabButton active={tab === 'runs'} onClick={() => setTab('runs')}>
                  Runs {activeRun ? <span className="rec-dot" /> : null}
                </TabButton>
                <TabButton active={tab === 'secrets'} onClick={() => setTab('secrets')}>
                  Secrets
                </TabButton>
              </div>

              {tab === 'control' && (
                <>
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
                          disabled={runDisabled}
                          onOpen={() => sendCmd(selected.id, 'launch', { pkg: app.pkg })}
                          onCapture={() => sendCmd(selected.id, 'launch', { pkg: app.pkg })}
                          onProbe={() => sendCmd(selected.id, 'probe', { pkg: app.pkg })}
                          onRecord={() => startAppRecording(app)}
                        />
                      ))}
                      {apps.length === 0 && (
                        <p className="empty">No app inventory yet. Use Refresh apps.</p>
                      )}
                    </div>
                  </section>

                  <SnapshotCard
                    snapshot={snapshot}
                    busy={busy}
                    status={null}
                    onCaptureCurrent={() => sendCmd(selected.id, 'snapshot')}
                    editing={editing}
                    setEditing={setEditing}
                    onTapElement={tapElement}
                    onScrollElement={scrollElement}
                    onSendText={sendText}
                    gridMode={gridMode}
                    onToggleGrid={() => setGridMode((value) => !value)}
                    onTapXY={tapXY}
                  />
                </>
              )}

              {tab === 'macros' && (
                <section className="panel">
                  <div className="panel-head">
                    <h2>Macros</h2>
                    <div className="app-actions">
                      <button
                        className="btn"
                        onClick={() => {
                          setMacroDraft(DEFAULT_MACRO)
                          setMacroError(null)
                        }}
                      >
                        New
                      </button>
                      <button
                        className="btn"
                        onClick={() => sendRaw({ t: 'macroList' })}
                      >
                        Refresh
                      </button>
                      <button className="btn primary" onClick={saveMacro}>
                        Save macro
                      </button>
                    </div>
                  </div>
                  {macroError && <p className="error small">{macroError}</p>}
                  <div className="macro-editor">
                    <textarea
                      value={macroDraft}
                      onChange={(event) => setMacroDraft(event.target.value)}
                      spellCheck={false}
                    />
                  </div>
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
                    {macros.length === 0 && <p className="empty">No macros yet.</p>}
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
                  disabled={!selected.online || !!activeRun}
                  onStart={startRecording}
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

function TabButton({
  active,
  onClick,
  children
}: {
  active: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button className={`tab ${active ? 'active' : ''}`} onClick={onClick}>
      {children}
    </button>
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
  onProbe,
  onRecord
}: {
  app: AppInfo
  probe?: ProbeResult
  disabled: boolean
  onOpen: () => void
  onCapture: () => void
  onProbe: () => void
  onRecord: () => void
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
        <button className="btn small" onClick={onRecord} disabled={disabled}>
          Record
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

function SnapshotCard({
  snapshot,
  busy,
  status,
  onCaptureCurrent,
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
  status: string | null
  onCaptureCurrent: () => void
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
    <section className="panel">
      <div className="panel-head">
        <h2>Snapshot</h2>
        <div className="app-actions">
          <button className="btn small" onClick={onToggleGrid}>
            {gridMode ? 'Hotspots' : 'Grid'}
          </button>
          {snapshot && (
            <span className="muted small">
              {snapshot.pkg ?? '?'} · {snapshot.w}×{snapshot.h} ·{' '}
              {snapshot.elements.length} hotspots · {snapshot.nodeCount} nodes ·{' '}
              {snapshot.elapsedMs} ms
            </span>
          )}
        </div>
      </div>

      {!snapshot && <p className="empty">Capture a screen to see hotspots.</p>}
      {status && <p className="muted small">{status}</p>}
      {snapshot?.error && <p className="error">Capture error: {snapshot.error}</p>}

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

          {gridMode && (
            <p className="muted small">
              Grid mode: tap a numbered cell to send a coordinate tap. Useful for
              apps with a partial accessibility tree.
            </p>
          )}

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
                  onClick={() => onTapElement(element)}
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
                      onClick={() => onScrollElement(element, 'up')}
                      disabled={busy}
                    >
                      ↑
                    </button>
                    <button
                      className="btn small"
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

          <button className="btn" onClick={onCaptureCurrent} disabled={busy}>
            Capture current
          </button>
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
        <button className="btn small" onClick={() => onEdit(macro)}>
          Edit
        </button>
        <button className="btn small" onClick={() => onDelete(macro)}>
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
  disabled,
  onStart,
  onStop,
  onClear,
  onDraft
}: {
  recording: boolean
  events: RecordEvent[]
  appLabel: (pkg?: string | null) => string
  sessionLabel: string | null
  disabled: boolean
  onStart: () => void
  onStop: () => void
  onClear: () => void
  onDraft: () => void
}) {
  const [textOnly, setTextOnly] = useState(false)
  const normalized = normalizeRecordEvents(events)
  const lastText = normalized.find(
    (event) => event.type === 'text' && !event.redacted && (event.text?.length ?? 0) > 0
  )
  const labelFor = (event: RecordEvent) =>
    event.appLabel ??
    (event.app?.includes('settings')
      ? 'Settings'
      : appLabel(event.app))

  const visibleEvents = textOnly
    ? normalized.filter((event) => event.type === 'text')
    : normalized

  return (
    <section className="panel">
      <div className="panel-head">
        <h2>Recorder</h2>
        <div className="app-actions">
          {!recording ? (
            <button className="btn primary" onClick={onStart} disabled={disabled}>
              Start recording
            </button>
          ) : (
            <button className="btn" onClick={onStop}>
              {sessionLabel ? 'Stop & save' : 'Stop recording'}
            </button>
          )}
          <button className="btn" onClick={onClear}>
            Clear
          </button>
          <button
            className={`btn ${textOnly ? 'active' : ''}`}
            onClick={() => setTextOnly((value) => !value)}
          >
            {textOnly ? 'Text only' : 'All events'}
          </button>
          <button className="btn" onClick={onDraft} disabled={events.length === 0}>
            Draft macro
          </button>
        </div>
      </div>

      {sessionLabel && (
        <p className="muted small">
          Recording for <strong>{sessionLabel}</strong>. Use the app normally, then
          click <strong>Stop &amp; save</strong> to create the macro.
        </p>
      )}

      <div className="typed-card">
        <div className="muted small">Last text entered</div>
        {lastText ? (
          <div className="typed-text">
            <span className="typed-app">{labelFor(lastText)}</span>
            <span>“{lastText.text}”</span>
          </div>
        ) : (
          <div className="empty">
            Start recording, then type in the target app on the device.
          </div>
        )}
      </div>

      <div className="record-events">
        {visibleEvents.map((event, index) => (
          <div className={`record-event ${event.type}`} key={`${event.ts}-${index}`}>
            <span className="record-time">{new Date(event.ts).toLocaleTimeString()}</span>
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
              ? 'No text events yet. Type in the target app while recording.'
              : 'Text, taps, scrolls, and window changes will appear here while recording.'}
          </p>
        )}
      </div>

      <p className="muted small">
        {textOnly
          ? 'Showing text events only. Toggle off to see taps, scrolls, and window changes.'
          : 'Text comes from accessibility text-change events. Masked bullet fields are cleaned and concatenated; OS-marked password fields are redacted.'}
      </p>
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
    <section className="panel">
      <div className="panel-head">
        <h2>Runs</h2>
        <span className="muted small">{runs.length}</span>
      </div>

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

      {selected && <RunReport run={selected} />}
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
      <div className="panel-head">
        <h3>{run.macroName}</h3>
        <RunStatusBadge status={run.status} />
      </div>
      <p className="muted small">
        {run.deviceId} · {(duration / 1000).toFixed(1)}s ·{' '}
        {new Date(run.startedAt).toLocaleString()}
      </p>

      <div className="run-steps">
        {run.steps.map((step) => (
          <div className="run-step" key={step.i}>
            <span className="index">#{step.i + 1}</span>
            <div className="element-main">
              <div>
                {step.op} <span className={`step-status ${step.status}`}>{step.status}</span>
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
    <section className="panel">
      <div className="panel-head">
        <h2>Secrets</h2>
        <span className="muted small">{secrets.length}</span>
      </div>
      <p className="muted small">
        Values are encrypted at rest and never returned to the browser. Use{' '}
        <code>"secretRef": "name"</code> in a <code>setText</code> step; the relay
        injects it at run time.
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
            <button className="btn small" onClick={() => onDelete(secret.id)}>
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
