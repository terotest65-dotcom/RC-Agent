import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type {
  ActionName,
  AppInfo,
  DeviceSummary,
  Element,
  Macro,
  NotifyPayload,
  PromptMessage,
  RecordEvent,
  RunRecord,
  SecretInfo,
  ServerMessage,
  SimInfo,
  SmsRecord,
  SnapshotMessage,
  TelephonyInfo
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

type Tab = 'control' | 'notify' | 'phone' | 'macros' | 'recorder' | 'runs' | 'secrets'
type IconName =
  | 'control'
  | 'notify'
  | 'phone'
  | 'macros'
  | 'recorder'
  | 'runs'
  | 'secrets'
  | 'camera'
  | 'activity'
  | 'close'
  | 'check'
  | 'bell'
  | 'screen'
  | 'battery'
  | 'app'
  | 'alert'
  | 'sun'
  | 'unlock'
  | 'lock'

const TABS: Array<{ key: Tab; label: string; icon: IconName }> = [
  { key: 'control', label: 'Control', icon: 'control' },
  { key: 'notify', label: 'Notify', icon: 'notify' },
  { key: 'phone', label: 'Phone', icon: 'phone' },
  { key: 'macros', label: 'Macros', icon: 'macros' },
  { key: 'recorder', label: 'Recorder', icon: 'recorder' },
  { key: 'runs', label: 'Runs', icon: 'runs' },
  { key: 'secrets', label: 'Secrets', icon: 'secrets' }
]

const TAB_LABELS: Record<Tab, string> = {
  control: 'Control',
  notify: 'Notify',
  phone: 'Phone',
  macros: 'Macros',
  recorder: 'Recorder',
  runs: 'Runs',
  secrets: 'Secrets'
}

const EMPTY_NOTIFY: NotifyPayload = {
  appName: 'RC Agent',
  title: '',
  subtitle: '',
  message: '',
  sound: true,
  vibration: true
}

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
  const [runNotice, setRunNotice] = useState<{
    ok: boolean
    text: string
  } | null>(null)
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
  const [smsMessages, setSmsMessages] = useState<SmsRecord[]>([])
  const [smsLoading, setSmsLoading] = useState(false)
  const [smsPending, setSmsPending] = useState(false)
  const [forwardPending, setForwardPending] = useState(false)
  const [phoneNotice, setPhoneNotice] = useState<{
    ok: boolean
    text: string
    scope: 'sms' | 'forward' | 'list'
  } | null>(null)
  const [notifyDraft, setNotifyDraft] = useState<NotifyPayload>(EMPTY_NOTIFY)
  const [notifyPending, setNotifyPending] = useState(false)
  const [notifyNotice, setNotifyNotice] = useState<{
    ok: boolean
    text: string
  } | null>(null)
  const [authed, setAuthed] = useState<boolean | null>(null)
  const [password, setPassword] = useState('')
  const [loginError, setLoginError] = useState<string | null>(null)
  const [loginBusy, setLoginBusy] = useState(false)

  const wsRef = useRef<WebSocket | null>(null)
  const selectedRef = useRef<string | null>(null)
  const activeSessionRef = useRef<ActiveSession | null>(null)

  const appendLog = useCallback((line: string) => {
    setLog((prev) => [`${nowLabel()}  ${line}`, ...prev].slice(0, 250))
  }, [])

  useEffect(() => {
    let mounted = true
    fetch('/api/session', { credentials: 'same-origin', cache: 'no-store' })
      .then((res) => {
        if (mounted) setAuthed(res.ok)
      })
      .catch(() => {
        if (mounted) setAuthed(false)
      })
    return () => {
      mounted = false
    }
  }, [])

  const login = useCallback(async () => {
    if (!password) return
    setLoginBusy(true)
    setLoginError(null)
    try {
      const res = await fetch('/api/login', {
        method: 'POST',
        credentials: 'same-origin',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ password })
      })
      const data = await res.json().catch(() => ({}))
      if (!res.ok) {
        setLoginError(data.error ?? 'Sign in failed')
        return
      }
      setPassword('')
      setSelectedId(null)
      setAuthed(true)
      appendLog('signed in')
    } catch {
      setLoginError('Could not reach the relay')
    } finally {
      setLoginBusy(false)
    }
  }, [appendLog, password])

  const logout = useCallback(async () => {
    try {
      await fetch('/api/logout', {
        method: 'POST',
        credentials: 'same-origin'
      })
    } catch {
      // Clear the local session anyway.
    }
    wsRef.current?.close()
    setAuthed(false)
    setSelectedId(null)
    setDevices([])
    appendLog('signed out')
  }, [appendLog])

  useEffect(() => {
    selectedRef.current = selectedId
  }, [selectedId])

  useEffect(() => {
    if (!showLog) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setShowLog(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [showLog])

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
      const logged: Record<string, unknown> = { ...extra }
      if ('pin' in logged) logged.pin = '••••'
      if ('secret' in logged) logged.secret = '••••'
      appendLog(
        `→ ${action} ${Object.keys(logged).length ? JSON.stringify(logged) : ''}`
      )
    },
    [appendLog, sendRaw]
  )

  const loadPhone = useCallback(
    (deviceId: string) => {
      setSmsLoading(true)
      setPhoneNotice(null)
      sendCmd(deviceId, 'telephonyInfo', {}, false)
      sendCmd(deviceId, 'smsList', { limit: 50 }, false)
    },
    [sendCmd]
  )

  const sendSmsNow = useCallback(
    (to: string, body: string, subId: number) => {
      if (!selectedId) return
      setSmsPending(true)
      setPhoneNotice(null)
      sendCmd(selectedId, 'smsSend', { to, body, subId }, false)
    },
    [selectedId, sendCmd]
  )

  const forwardNow = useCallback(
    (code: string, subId: number) => {
      if (!selectedId) return
      setForwardPending(true)
      setPhoneNotice(null)
      sendCmd(selectedId, 'callForward', { code, subId }, false)
    },
    [selectedId, sendCmd]
  )

  const sendNotify = useCallback(() => {
    if (!selectedId) return
    setNotifyPending(true)
    setNotifyNotice(null)
    sendCmd(selectedId, 'notify', { ...notifyDraft }, false)
  }, [notifyDraft, selectedId, sendCmd])

  const handleMessage = useCallback(
    (msg: ServerMessage) => {
      switch (msg.t) {
        case 'devices': {
          setDevices(msg.devices)
          setSelectedId((prev) =>
            prev && msg.devices.some((d) => d.id === prev) ? prev : null
          )
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
        case 'profile':
          setDevices((prev) =>
            prev.map((d) =>
              d.id === msg.deviceId ? { ...d, profile: msg.profile } : d
            )
          )
          break
        case 'telephony':
          setDevices((prev) =>
            prev.map((d) =>
              d.id === msg.deviceId
                ? {
                    ...d,
                    telephony: {
                      sims: msg.sims,
                      permissions: msg.permissions,
                      error: msg.error ?? null
                    }
                  }
                : d
            )
          )
          break
        case 'smsList':
          if (msg.deviceId !== selectedRef.current) {
            setSmsLoading(false)
            break
          }
          setSmsMessages(msg.messages)
          setSmsLoading(false)
          if (msg.error) {
            setPhoneNotice({ ok: false, text: msg.error, scope: 'list' })
          }
          break
        case 'smsSendResult':
          setSmsPending(false)
          if (msg.deviceId === selectedRef.current) {
            setPhoneNotice({
              ok: msg.ok,
              text: msg.ok
                ? msg.detail ?? `SMS queued to ${msg.to}`
                : msg.error ?? 'SMS failed',
              scope: 'sms'
            })
          }
          appendLog(
            `sms ${msg.ok ? 'ok' : 'error'} · ${msg.to}${
              msg.detail ? ` · ${msg.detail}` : ''
            }${msg.error ? ` · ${msg.error}` : ''}`
          )
          break
        case 'ussdResult':
          setForwardPending(false)
          if (msg.deviceId === selectedRef.current) {
            setPhoneNotice({
              ok: msg.ok,
              text: msg.ok
                ? msg.response ?? 'USSD accepted'
                : msg.error ?? 'USSD failed',
              scope: 'forward'
            })
          }
          appendLog(
            `ussd ${msg.ok ? 'ok' : 'error'} · ${msg.code}${
              msg.response ? ` · ${msg.response}` : ''
            }${msg.error ? ` · ${msg.error}` : ''}`
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
          if (msg.action === 'notify') {
            setNotifyPending(false)
            setNotifyNotice({
              ok: msg.ok,
              text: msg.ok
                ? msg.detail ?? 'Notification posted to the device.'
                : msg.error ?? 'Could not post the notification.'
            })
          }
          appendLog(
            `${msg.ok ? 'ok' : 'error'} ${msg.cmdId}${msg.error ? ` · ${msg.error}` : ''}${
              msg.detail ? ` · ${msg.detail}` : ''
            }`
          )
          break
        case 'error':
          setBusy(false)
          setNotifyPending(false)
          setRunNotice({ ok: false, text: msg.message })
          appendLog(`server: ${msg.message}`)
          if (msg.message.includes('login required')) {
            setAuthed(false)
            setSelectedId(null)
          }
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
          setRunNotice(null)
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
          setRunNotice(null)
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
    if (!authed) return
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
  }, [appendLog, handleMessage, authed])

  useEffect(() => {
    if (!selectedId) return
    setSnapshot(null)
    setEditing(null)
    setRecordEvents([])
    setRecording(false)
    setActiveSession(null)
    setSmsMessages([])
    setSmsLoading(false)
    setSmsPending(false)
    setForwardPending(false)
    setPhoneNotice(null)
    const device = devices.find((d) => d.id === selectedId)
    if (device?.online) {
      sendCmd(selectedId, 'snapshot', {}, false)
      sendCmd(selectedId, 'listApps', {}, false)
    }
    // Selected device changes should not re-run when the device list updates.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedId])

  useEffect(() => {
    if (tab !== 'phone' || !selectedId) return
    loadPhone(selectedId)
    // Reload only when the tab or device changes, not on every device update.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab, selectedId])

  const selected = devices.find((d) => d.id === selectedId) ?? null
  const foregroundPackage = selected?.state?.package ?? null
  const foregroundLabel = foregroundPackage
    ? selected?.apps?.find((app) => app.pkg === foregroundPackage)?.label ??
      foregroundPackage
    : null
  const appUnlockConfigured =
    !!foregroundPackage &&
    (selected?.state?.appUnlockPackages ?? []).includes(foregroundPackage)
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
    if (!selected.online) {
      setRunNotice({
        ok: false,
        text: 'Device is offline. Reconnect it, then run again.'
      })
      return
    }

    const known = new Set(secrets.flatMap((secret) => [secret.name, secret.id]))
    const missing = Array.from(
      new Set(
        macro.steps
          .map((step) => step.secretRef)
          .filter((ref): ref is string => !!ref && !known.has(ref))
      )
    )
    if (missing.length > 0) {
      setRunNotice({
        ok: false,
        text: `Missing ${
          missing.length > 1 ? 'secrets' : 'secret'
        }: ${missing.join(', ')}. Add ${
          missing.length > 1 ? 'them' : 'it'
        } in Secrets, then run again.`
      })
      return
    }

    setRunNotice(null)
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


  const tabNotes: Record<Tab, string | number | null> = {
    control: null,
    notify: null,
    phone: selected?.telephony?.sims.length ?? 0,
    macros: macros.length,
    recorder: recordEvents.length,
    runs: runs.length,
    secrets: secrets.length
  }

  if (authed === null) return <SessionLoading />

  if (!authed) {
    return (
      <LoginPage
        password={password}
        onPassword={setPassword}
        onSubmit={() => {
          void login()
        }}
        busy={loginBusy}
        error={loginError}
      />
    )
  }

  if (!selected) {
    return (
      <DevicesPage
        devices={devices}
        connected={connected}
        onSelect={setSelectedId}
        onLogout={() => {
          void logout()
        }}
      />
    )
  }

  return (
    <div className="app">
      <aside className="sidebar">
        <div className="brand">
          <div className="brand-logo">RC</div>
          <div className="brand-copy">
            <strong>RC Agent</strong>
            <span>Remote control</span>
          </div>
        </div>

        <nav className="nav" aria-label="Sections">
          {TABS.map((item) => (
            <button
              key={item.key}
              className={`nav-item ${tab === item.key ? 'active' : ''}`}
              onClick={() => setTab(item.key)}
            >
              <Icon name={item.icon} />
              <span className="nav-label">{item.label}</span>
              {tabNotes[item.key] != null && Number(tabNotes[item.key]) > 0 && (
                <span className="nav-badge">{tabNotes[item.key]}</span>
              )}
              {((item.key === 'recorder' && recording) ||
                (item.key === 'runs' && activeRun)) && (
                <span className="nav-live" />
              )}
            </button>
          ))}
        </nav>

        <div className="sidebar-devices">
          <div className="sidebar-heading">
            <span>Current device</span>
          </div>
          <div className="current-device">
            <span className="device-card-head">
              <span
                className={`status-dot ${selected.online ? 'online' : ''}`}
              />
              <strong>{selected.name}</strong>
            </span>
            <span className="device-card-meta">
              {selected.profile
                ? `${selected.profile.name} · ${selected.profile.phone}`
                : selected.model ?? selected.id}
            </span>
            <span className="device-card-foot">
              {selected.online ? 'Online' : 'Offline'} ·{' '}
              {selected.apps?.length ?? 0} apps
            </span>
          </div>
          <button
            className="btn btn-ghost btn-small switch-device"
            onClick={() => setSelectedId(null)}
          >
            <Icon name="control" size={15} />
            All devices
          </button>
        </div>

        <div className="sidebar-footer">
          <div className={`relay-status ${connected ? 'online' : ''}`}>
            <span className={`status-dot ${connected ? 'online' : ''}`} />
            {connected ? 'Relay connected' : 'Reconnecting…'}
          </div>
          <span className="relay-url">
            {WS_URL.replace(/^wss?:\/\//, '')}
          </span>
          <span className="relay-note">Admin session · TLS</span>
          <button
            className="btn btn-ghost btn-small logout-button"
            onClick={() => {
              void logout()
            }}
          >
            Log out
          </button>
        </div>
      </aside>

      <main className="main">
        {prompt && (
          <div className="alert alert-warning prompt-banner">
            <Icon name="alert" />
            <div className="alert-copy">
              <strong>Verification required</strong>
              <span>
                {prompt.message} · run {prompt.runId.slice(0, 8)}
              </span>
            </div>
            <input
              className="input"
              value={promptValue}
              onChange={(event) => setPromptValue(event.target.value)}
              placeholder="Enter OTP"
              autoFocus
            />
            <button className="btn btn-primary" onClick={submitPrompt}>
              Send code
            </button>
            <button className="btn btn-ghost" onClick={() => setPrompt(null)}>
              Dismiss
            </button>
          </div>
        )}

        {runNotice && (
          <div className="run-notice-toast">
            <NoticeBar
              notice={runNotice}
              onDismiss={() => setRunNotice(null)}
            />
          </div>
        )}

        {!selected ? (
          <div className="empty-state">
            <div className="empty-icon">
              <Icon name="control" size={28} />
            </div>
            <h1>No device selected</h1>
            <p>
              Connect the Android agent to the relay, then choose it from the
              sidebar.
            </p>
          </div>
        ) : (
          <>
            <header className="topbar">
              <div className="page-title">
                <div className="breadcrumb">
                  <button
                    className="breadcrumb-link"
                    onClick={() => setSelectedId(null)}
                  >
                    Devices
                  </button>{' '}
                  / {selected.name}
                </div>
                <div className="title-row">
                  <h1>{TAB_LABELS[tab]}</h1>
                  <span
                    className={`status-pill ${
                      selected.online ? 'online' : 'offline'
                    }`}
                  >
                    <span
                      className={`status-dot ${
                        selected.online ? 'online' : ''
                      }`}
                    />
                    {selected.online ? 'Online' : 'Offline'}
                  </span>
                </div>
                <p className="page-subtitle">
                  {selected.name} · Android {selected.android ?? '?'} ·{' '}
                  <span className="mono">{selected.id}</span>
                </p>
                {selected.profile && (
                  <p className="operator-line">
                    <Icon name="check" size={14} />
                    Registered to <strong>{selected.profile.name}</strong>
                    <span>·</span>
                    {selected.profile.phone}
                  </p>
                )}
              </div>
              <div className="topbar-actions">
                <button
                  className={`btn btn-secondary ${
                    showLog ? 'is-active' : ''
                  }`}
                  onClick={() => setShowLog((value) => !value)}
                >
                  <Icon name="activity" />
                  Activity
                  {log.length > 0 && (
                    <span className="btn-badge">{log.length}</span>
                  )}
                </button>
                <button
                  className="btn btn-secondary"
                  disabled={actionDisabled}
                  title="Wake the screen"
                  onClick={() => sendCmd(selected.id, 'wake')}
                >
                  <Icon name="sun" />
                  Wake
                </button>
                <button
                  className="btn btn-secondary"
                  disabled={actionDisabled}
                  title="Unlock with the stored device PIN/password, Device Owner, or the swipe keyguard."
                  onClick={() => sendCmd(selected.id, 'unlock')}
                >
                  <Icon name="unlock" />
                  Unlock
                </button>
                <button
                  className="btn btn-secondary"
                  disabled={actionDisabled}
                  title="Lock the device immediately."
                  onClick={() =>
                    sendCmd(selected.id, 'globalAction', { name: 'lock' })
                  }
                >
                  <Icon name="lock" />
                  Lock
                </button>
                <button
                  className="btn btn-primary"
                  disabled={actionDisabled}
                  onClick={() => sendCmd(selected.id, 'snapshot')}
                >
                  <Icon name="camera" />
                  Capture screen
                </button>
                {recording && (
                  <button className="btn btn-danger" onClick={stopRecording}>
                    <span className="status-dot" />
                    Stop recording
                  </button>
                )}
              </div>
            </header>

            <div className="content">
              {(tab === 'control' || tab === 'phone') && (
                <DeviceStats state={selected.state} />
              )}

              <div className="tab-content">
                {tab === 'control' && (
                  <div className="control-grid">
                    <div className="control-main">
                      <section className="panel composer-card">
                        <div className="composer-copy">
                          <h2>Type into device</h2>
                          <p>Writes to the focused text field.</p>
                        </div>
                        <div className="composer-field">
                          <input
                            className="input"
                            value={typing}
                            onChange={(event) => setTyping(event.target.value)}
                            onKeyDown={(event) => {
                              if (event.key === 'Enter' && !event.shiftKey) {
                                event.preventDefault()
                                sendTyping(true)
                              }
                            }}
                            placeholder="Message or value…"
                          />
                          <button
                            className="btn btn-secondary"
                            disabled={busy || !typing.trim()}
                            onClick={() => sendTyping(false)}
                          >
                            Insert
                          </button>
                          <button
                            className="btn btn-primary"
                            disabled={busy || !typing.trim()}
                            onClick={() => sendTyping(true)}
                          >
                            Insert + Enter
                          </button>
                        </div>
                      </section>

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
                      <DevicePinPanel
                        key={selected.id}
                        configured={selected.state?.devicePin === true}
                        fallback={selected.state?.pinFallback === true}
                        secureLock={selected.state?.secureLock === true}
                        locked={selected.state?.locked === true}
                        busy={busy || !selected.online}
                        onSave={(pin, allowFallback) =>
                          sendCmd(selected.id, 'setDevicePin', {
                            pin,
                            fallback: allowFallback
                          })
                        }
                        onClear={() => sendCmd(selected.id, 'clearDevicePin')}
                        onEnroll={() => sendCmd(selected.id, 'openEnrollment')}
                      />
                      <AppUnlockPanel
                        key={selected.id}
                        foregroundPackage={foregroundPackage}
                        foregroundLabel={foregroundLabel}
                        configured={appUnlockConfigured}
                        deviceConfigured={selected.state?.devicePin === true}
                        busy={busy || !selected.online}
                        onSave={(secret) =>
                          foregroundPackage &&
                          sendCmd(selected.id, 'setAppUnlock', {
                            package: foregroundPackage,
                            secret
                          })
                        }
                        onForget={() =>
                          foregroundPackage &&
                          sendCmd(selected.id, 'clearAppUnlock', {
                            package: foregroundPackage
                          })
                        }
                        onUnlock={() => sendCmd(selected.id, 'appUnlock')}
                      />
                      <section className="panel apps-panel">
                        <div className="panel-head apps-head">
                          <div>
                            <h2>Apps</h2>
                            <span className="panel-sub">
                              {apps.length} installed
                            </span>
                          </div>
                          <input
                            className="input search-input"
                            value={appQuery}
                            onChange={(event) => setAppQuery(event.target.value)}
                            placeholder="Search apps"
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
                        <div className="panel-foot">
                          <span className="status-dot online" />
                          Record captures a macro for that app.
                        </div>
                      </section>
                    </aside>
                  </div>
                )}

                {tab === 'notify' && (
                  <NotifyPanel
                    draft={notifyDraft}
                    onChange={(patch) =>
                      setNotifyDraft((prev) => ({ ...prev, ...patch }))
                    }
                    pending={notifyPending}
                    notice={notifyNotice}
                    online={selected.online}
                    onSend={sendNotify}
                    onDismissNotice={() => setNotifyNotice(null)}
                    onReset={() => {
                      setNotifyDraft(EMPTY_NOTIFY)
                      setNotifyNotice(null)
                    }}
                  />
                )}

                {tab === 'phone' && (
                  <PhonePanel
                    key={selected.id}
                    telephony={selected.telephony ?? null}
                    messages={smsMessages}
                    loading={smsLoading}
                    smsPending={smsPending}
                    forwardPending={forwardPending}
                    notice={phoneNotice}
                    onDismissNotice={() => setPhoneNotice(null)}
                    onRefresh={() => loadPhone(selected.id)}
                    onSendSms={sendSmsNow}
                    onCallForward={forwardNow}
                  />
                )}

                {tab === 'macros' && (
                  <section className="panel">
                    <div className="panel-head">
                      <div>
                        <h2>Macros</h2>
                        <span className="panel-sub">{macros.length} saved</span>
                      </div>
                      <div className="panel-actions">
                        <button
                          className="btn btn-ghost"
                          onClick={() => sendRaw({ t: 'macroList' })}
                        >
                          Refresh
                        </button>
                        <button
                          className="btn btn-primary"
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
                          <div className="editor-head">
                            <h3>JSON definition</h3>
                            <div className="panel-actions">
                              <button
                                className="btn btn-ghost btn-small"
                                onClick={() => setMacroEditorOpen(false)}
                              >
                                Close
                              </button>
                              <button
                                className="btn btn-primary btn-small"
                                onClick={saveMacro}
                              >
                                Save macro
                              </button>
                            </div>
                          </div>
                          {macroError && (
                            <p className="error">{macroError}</p>
                          )}
                          <textarea
                            value={macroDraft}
                            onChange={(event) =>
                              setMacroDraft(event.target.value)
                            }
                            spellCheck={false}
                          />
                        </div>
                      ) : (
                        <div className="editor-placeholder">
                          <h3>Select a macro to edit</h3>
                          <p>Or create a new one to get started.</p>
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
            </div>
          </>
        )}

        {showLog && (
          <>
            <div
              className="drawer-backdrop"
              onClick={() => setShowLog(false)}
            />
            <aside
              className="activity-drawer"
              role="dialog"
              aria-label="Activity log"
            >
              <div className="drawer-head">
                <div>
                  <h2>Activity</h2>
                  <span>{log.length} events</span>
                </div>
                <button
                  className="icon-button"
                  onClick={() => setShowLog(false)}
                  aria-label="Close activity"
                >
                  <Icon name="close" />
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
            </aside>
          </>
        )}
      </main>
    </div>
  )
}

function SessionLoading() {
  return (
    <div className="auth-screen">
      <div className="auth-card auth-loading">
        <div className="auth-brand">
          <div className="brand-logo">RC</div>
          <div className="auth-brand-copy">
            <strong>RC Agent</strong>
            <span>Admin console</span>
          </div>
        </div>
        <p>Checking session…</p>
      </div>
    </div>
  )
}

function LoginPage({
  password,
  onPassword,
  onSubmit,
  busy,
  error
}: {
  password: string
  onPassword: (value: string) => void
  onSubmit: () => void
  busy: boolean
  error: string | null
}) {
  return (
    <div className="auth-screen">
      <div className="auth-card">
        <div className="auth-brand">
          <div className="brand-logo">RC</div>
          <div className="auth-brand-copy">
            <strong>RC Agent</strong>
            <span>Admin console</span>
          </div>
        </div>
        <h1>Sign in</h1>
        <p className="auth-lede">
          Enter the shared admin password to manage enrolled devices.
        </p>
        <form
          className="auth-form"
          onSubmit={(event) => {
            event.preventDefault()
            onSubmit()
          }}
        >
          <label className="field">
            <span>Admin password</span>
            <input
              type="password"
              value={password}
              onChange={(event) => onPassword(event.target.value)}
              placeholder="Password"
              autoComplete="current-password"
              autoFocus
            />
          </label>
          {error && <p className="form-error">{error}</p>}
          <button
            className="btn btn-primary auth-submit"
            type="submit"
            disabled={busy || !password}
          >
            {busy ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
        <p className="auth-note">
          Internal use only · {window.location.host}
        </p>
      </div>
    </div>
  )
}

function DevicesPage({
  devices,
  connected,
  onSelect,
  onLogout
}: {
  devices: DeviceSummary[]
  connected: boolean
  onSelect: (id: string) => void
  onLogout: () => void
}) {
  return (
    <div className="devices-page">
      <header className="devices-header">
        <div className="auth-brand">
          <div className="brand-logo">RC</div>
          <div className="auth-brand-copy">
            <strong>RC Agent</strong>
            <span>Admin console</span>
          </div>
        </div>
        <div className="devices-header-actions">
          <span className={`relay-status ${connected ? 'online' : ''}`}>
            <span className={`status-dot ${connected ? 'online' : ''}`} />
            {connected ? 'Live' : 'Connecting…'}
          </span>
          <button className="btn btn-ghost btn-small" onClick={onLogout}>
            Log out
          </button>
        </div>
      </header>

      <main className="devices-content">
        <div className="devices-intro">
          <p className="eyebrow">Devices</p>
          <h1>Choose a device</h1>
          <p>
            Every enrolled agent appears here automatically, online or offline.
            Select one to open its dashboard.
          </p>
        </div>

        {devices.length === 0 ? (
          <div className="devices-empty">
            <div className="empty-icon">
              <Icon name="control" size={28} />
            </div>
            <h2>No devices enrolled yet</h2>
            <p>
              Install the RC Agent APK, complete the name and phone form, and
              grant accessibility. It will appear here as soon as it connects.
            </p>
          </div>
        ) : (
          <div className="device-tile-grid">
            {devices.map((device) => (
              <button
                key={device.id}
                className={`device-tile ${device.online ? 'online' : 'offline'}`}
                onClick={() => onSelect(device.id)}
              >
                <div className="device-tile-top">
                  <span
                    className={`status-pill ${
                      device.online ? 'online' : 'offline'
                    }`}
                  >
                    <span
                      className={`status-dot ${
                        device.online ? 'online' : ''
                      }`}
                    />
                    {device.online ? 'Online' : 'Offline'}
                  </span>
                  <span className="device-tile-apps">
                    {device.apps?.length ?? 0} apps
                  </span>
                </div>
                <h2>{device.name}</h2>
                <p className="device-tile-meta">
                  {device.model ?? 'Unknown model'} · Android{' '}
                  {device.android ?? '?'}
                </p>
                {device.profile ? (
                  <p className="device-tile-operator">
                    <Icon name="check" size={14} />
                    {device.profile.name} · {device.profile.phone}
                  </p>
                ) : (
                  <p className="device-tile-operator muted">
                    No operator registration
                  </p>
                )}
                <div className="device-tile-foot">
                  <span>{formatLastSeen(device.lastSeen)}</span>
                  <span>Open dashboard →</span>
                </div>
              </button>
            ))}
          </div>
        )}
      </main>
    </div>
  )
}

function formatLastSeen(value?: number) {
  if (!value) return 'Never seen'
  return `Last seen ${new Date(value).toLocaleString()}`
}

function Icon({ name, size = 18 }: { name: IconName; size?: number }) {
  const common = {
    width: size,
    height: size,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.8,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const
  }

  switch (name) {
    case 'control':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="3" y="4" width="18" height="13" rx="2" />
          <path d="M8 21h8M12 17v4" />
        </svg>
      )
    case 'notify':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M18 8a6 6 0 1 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
          <path d="M10 21h4" />
          <path d="M19 3v4M17 5h4" />
        </svg>
      )
    case 'phone':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M7 3h3l2 5-2 1.5a12 12 0 0 0 5 5L16.5 12l5 2v3a2 2 0 0 1-2 2A16 16 0 0 1 3 5a2 2 0 0 1 2-2z" />
        </svg>
      )
    case 'macros':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M9 6h11M9 12h11M9 18h11" />
          <circle cx="4.5" cy="6" r="1.2" />
          <circle cx="4.5" cy="12" r="1.2" />
          <circle cx="4.5" cy="18" r="1.2" />
        </svg>
      )
    case 'recorder':
      return (
        <svg {...common} aria-hidden="true">
          <circle cx="12" cy="12" r="8.5" />
          <circle cx="12" cy="12" r="3" fill="currentColor" stroke="none" />
        </svg>
      )
    case 'runs':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M8 5l11 7-11 7z" />
        </svg>
      )
    case 'secrets':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="4" y="10" width="16" height="11" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" />
        </svg>
      )
    case 'camera':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M4 8h3l2-2h6l2 2h3v11H4z" />
          <circle cx="12" cy="13" r="3.5" />
        </svg>
      )
    case 'activity':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M3 12h4l2-6 4 12 2-6h6" />
        </svg>
      )
    case 'close':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      )
    case 'check':
      return (
        <svg {...common} aria-hidden="true">
          <circle cx="12" cy="12" r="9" />
          <path d="M8 12l3 3 5-6" />
        </svg>
      )
    case 'bell':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M18 8a6 6 0 1 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9" />
          <path d="M10 21h4" />
        </svg>
      )
    case 'screen':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="3" y="5" width="18" height="12" rx="2" />
          <path d="M8 21h8M12 17v4" />
        </svg>
      )
    case 'battery':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="2" y="7" width="18" height="10" rx="2" />
          <path d="M22 10v4" />
          <rect x="5" y="10" width="8" height="4" fill="currentColor" stroke="none" />
        </svg>
      )
    case 'app':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="4" y="4" width="6" height="6" rx="1" />
          <rect x="14" y="4" width="6" height="6" rx="1" />
          <rect x="4" y="14" width="6" height="6" rx="1" />
          <rect x="14" y="14" width="6" height="6" rx="1" />
        </svg>
      )
    case 'alert':
      return (
        <svg {...common} aria-hidden="true">
          <path d="M12 3l9 16H3z" />
          <path d="M12 9v5M12 17h.01" />
        </svg>
      )
    case 'sun':
      return (
        <svg {...common} aria-hidden="true">
          <circle cx="12" cy="12" r="4" />
          <path d="M12 2v2M12 20v2M4.93 4.93l1.42 1.42M17.66 17.66l1.41 1.41M2 12h2M20 12h2M4.93 19.07l1.42-1.42M17.66 6.34l1.41-1.41" />
        </svg>
      )
    case 'unlock':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="4" y="10" width="16" height="11" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 7.5-2" />
        </svg>
      )
    case 'lock':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="4" y="10" width="16" height="11" rx="2" />
          <path d="M8 10V7a4 4 0 0 1 8 0v3" />
        </svg>
      )
    default:
      return null
  }
}

function AppUnlockPanel({
  foregroundPackage,
  foregroundLabel,
  configured,
  deviceConfigured,
  busy,
  onSave,
  onForget,
  onUnlock
}: {
  foregroundPackage: string | null
  foregroundLabel: string | null
  configured: boolean
  deviceConfigured: boolean
  busy: boolean
  onSave: (secret: string) => void
  onForget: () => void
  onUnlock: () => void
}) {
  const [secret, setSecret] = useState('')
  const [reveal, setReveal] = useState(false)

  useEffect(() => {
    setSecret('')
    setReveal(false)
  }, [foregroundPackage])

  const valid = secret.length >= 4 && secret.length <= 64
  const systemPackage =
    !foregroundPackage ||
    foregroundPackage === 'com.android.systemui' ||
    foregroundPackage === 'com.miui.home' ||
    foregroundPackage.includes('launcher')
  const canUnlock = (configured || deviceConfigured) && !busy

  return (
    <section className="panel pin-panel">
      <div className="panel-head">
        <div>
          <h2>App unlock</h2>
          <span className="panel-sub">
            {foregroundPackage
              ? systemPackage
                ? 'Open a locked app first'
                : `Foreground: ${foregroundLabel ?? foregroundPackage}`
              : 'No foreground app'}
          </span>
        </div>
        <span className={`pin-badge ${configured ? 'on' : ''}`}>
          {configured ? 'App PIN' : deviceConfigured ? 'Device cred' : 'None'}
        </span>
      </div>
      <div className="pin-body">
        <p className="pin-copy">
          Optional per-app credential. Leave it empty to use the device unlock
          credential — MIUI/POCO App lock does this. The agent taps
          Use password/PIN/pattern first, then enters the credential.
        </p>
        <div className="pin-row">
          <input
            className="input"
            type={reveal ? 'text' : 'password'}
            autoComplete="new-password"
            maxLength={64}
            value={secret}
            disabled={systemPackage}
            placeholder={
              configured
                ? 'Replace app credential…'
                : 'App PIN, password, or pattern dots'
            }
            onChange={(event) =>
              setSecret(event.target.value.slice(0, 64))
            }
          />
          <button
            className="btn btn-ghost btn-small"
            type="button"
            onClick={() => setReveal((value) => !value)}
          >
            {reveal ? 'Hide' : 'Show'}
          </button>
        </div>
        <div className="panel-actions pin-actions">
          <button
            className="btn btn-primary btn-small"
            type="button"
            disabled={busy || systemPackage || !valid}
            onClick={() => {
              onSave(secret)
              setSecret('')
              setReveal(false)
            }}
          >
            Save
          </button>
          <button
            className="btn btn-ghost btn-small"
            type="button"
            disabled={busy || !configured}
            onClick={onForget}
          >
            Forget
          </button>
          <button
            className="btn btn-secondary btn-small"
            type="button"
            disabled={!canUnlock}
            onClick={onUnlock}
          >
            Unlock app
          </button>
        </div>
        {foregroundPackage && !systemPackage && !configured && deviceConfigured && (
          <p className="pin-copy">
            Using the device unlock credential for this app.
          </p>
        )}
        {!deviceConfigured && !configured && (
          <p className="pin-hint">
            Store a device unlock credential or a per-app credential first.
          </p>
        )}
        {systemPackage && foregroundPackage && (
          <p className="pin-hint">
            Open the locked app on the device, then use this panel.
          </p>
        )}
      </div>
    </section>
  )
}

function DevicePinPanel({
  configured,
  fallback,
  secureLock,
  locked,
  busy,
  onSave,
  onClear,
  onEnroll
}: {
  configured: boolean
  fallback: boolean
  secureLock: boolean
  locked: boolean
  busy: boolean
  onSave: (secret: string, allowFallback: boolean) => void
  onClear: () => void
  onEnroll: () => void
}) {
  const [secret, setSecret] = useState('')
  const [reveal, setReveal] = useState(false)
  const [allowFallback, setAllowFallback] = useState(fallback)

  useEffect(() => {
    setAllowFallback(fallback)
  }, [fallback])

  const valid = secret.length >= 4 && secret.length <= 64

  return (
    <section className="panel pin-panel">
      <div className="panel-head">
        <div>
          <h2>Device unlock</h2>
          <span className="panel-sub">
            {configured ? 'Credential stored' : 'Not stored'}
          </span>
        </div>
        <span className={`pin-badge ${configured ? 'on' : ''}`}>
          {configured ? 'Set' : 'None'}
        </span>
      </div>
      <div className="pin-body">
        <p className="pin-copy">
          Used by <strong>Unlock</strong> for a PIN, password, or pattern. For a
          pattern, use <strong>Open enrollment</strong> to draw it on the
          device, or enter the dot sequence 1–9 row-major (e.g.{' '}
          <span className="mono">1478</span>). Encrypted on the device; never
          sent back to the panel.
        </p>
        <div className="pin-row">
          <input
            className="input"
            type={reveal ? 'text' : 'password'}
            autoComplete="new-password"
            maxLength={64}
            value={secret}
            placeholder={
              configured ? 'Replace PIN, password, or pattern…' : 'PIN, password, or pattern'
            }
            onChange={(event) =>
              setSecret(event.target.value.slice(0, 64))
            }
          />
          <button
            className="btn btn-ghost btn-small"
            type="button"
            onClick={() => setReveal((value) => !value)}
          >
            {reveal ? 'Hide' : 'Show'}
          </button>
        </div>
        <label className="pin-check">
          <input
            type="checkbox"
            checked={allowFallback}
            onChange={(event) => setAllowFallback(event.target.checked)}
          />
          <span>
            Fall back to cached PIN-keypad positions if the labels are missing
          </span>
        </label>
        <div className="panel-actions pin-actions">
          <button
            className="btn btn-primary btn-small"
            type="button"
            disabled={busy || !valid}
            onClick={() => {
              onSave(secret, allowFallback)
              setSecret('')
              setReveal(false)
            }}
          >
            Save
          </button>
          <button
            className="btn btn-ghost btn-small"
            type="button"
            disabled={busy || !configured}
            onClick={onClear}
          >
            Forget
          </button>
          <button
            className="btn btn-secondary btn-small"
            type="button"
            disabled={busy}
            onClick={onEnroll}
          >
            Open enrollment
          </button>
        </div>
        {locked && (
          <p className="pin-hint">
            Unlock the device on the device before opening enrollment.
          </p>
        )}
        {secureLock && !configured && (
          <p className="pin-hint">
            Secure lock is on. Without a stored PIN/password, someone must
            unlock on the device.
          </p>
        )}
        {allowFallback && (
          <p className="pin-hint">
            Blind keypad taps can count as failed unlocks. Leave this off unless
            the keypad layout is fixed.
          </p>
        )}
      </div>
    </section>
  )
}

function DeviceStats({ state }: { state?: DeviceSummary['state'] }) {
  if (!state) {
    return (
      <div className="stats-grid">
        <div className="stat-card neutral">
          <span className="stat-icon">
            <Icon name="activity" />
          </span>
          <span className="stat-copy">
            <span className="stat-label">Device state</span>
            <strong>Waiting for the agent…</strong>
          </span>
        </div>
      </div>
    )
  }

  return (
    <div className="stats-grid">
      <StatCard
        icon="check"
        label="Accessibility"
        value={state.a11y ? 'Enabled' : 'Off'}
        tone={state.a11y ? 'success' : 'danger'}
      />
      <StatCard
        icon="bell"
        label="Notifications"
        value={state.notifAccess ? 'Enabled' : 'Off'}
        tone={state.notifAccess ? 'success' : 'danger'}
      />
      <StatCard
        icon="screen"
        label="Screen"
        value={state.screenOn ? (state.locked ? 'Locked' : 'On') : 'Off'}
        tone={state.screenOn && !state.locked ? 'success' : 'warning'}
      />
      <StatCard
        icon="battery"
        label="Battery"
        value={`${state.battery}%${state.charging ? ' · chg' : ''}`}
        tone={state.charging || state.battery > 20 ? 'success' : 'danger'}
      />
      <StatCard
        icon="app"
        label="Foreground app"
        value={state.package ?? 'Unknown'}
        tone="neutral"
      />
    </div>
  )
}

function StatCard({
  icon,
  label,
  value,
  tone
}: {
  icon: IconName
  label: string
  value: string
  tone: 'success' | 'danger' | 'warning' | 'neutral'
}) {
  return (
    <div className={`stat-card ${tone}`}>
      <span className="stat-icon">
        <Icon name={icon} />
      </span>
      <span className="stat-copy">
        <span className="stat-label">{label}</span>
        <strong title={value}>{value}</strong>
      </span>
    </div>
  )
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
      <span className="app-index">{app.label.slice(0, 1).toUpperCase()}</span>
      <div className="app-copy">
        <strong>{app.label}</strong>
        <span>{app.pkg}</span>
      </div>
      <div className="app-actions">
        <button
          className="btn btn-ghost btn-small"
          title={`Open ${app.label}`}
          aria-label={`Open ${app.label}`}
          onClick={onOpen}
          disabled={disabled}
        >
          Open
        </button>
        <button
          className="btn btn-small app-record"
          title={`Record a macro for ${app.label}`}
          aria-label={`Record a macro for ${app.label}`}
          onClick={onRecord}
          disabled={disabled}
        >
          Record
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
    <section className="panel snapshot-panel">
      <div className="panel-head view-head">
        <div>
          <h2>Device view</h2>
          <span className="panel-sub">
            {snapshot
              ? `${snapshot.pkg ?? 'unknown'} · ${snapshot.elements.length} nodes`
              : 'No capture yet'}
          </span>
        </div>
        <div className="view-actions">
          <span className="live-tag">
            <span className="live-dot" /> frame
          </span>
          <button className="btn btn-ghost btn-small" onClick={onToggleGrid}>
            {gridMode ? 'Hotspots' : 'Grid'}
          </button>
        </div>
      </div>

      {snapshot?.error && <p className="error">{snapshot.error}</p>}

      <div className="screen-stage">
        {!snapshot ? (
          <div className="viewport-empty">
            <span>No screen captured</span>
            <small>Use Capture screen to pull a frame from the device.</small>
          </div>
        ) : (
          !snapshot.error && (
            <div className="monitor">
              <div className="screen-chrome">
                <span className="chrome-pkg">
                  <span className="live-dot" />
                  {snapshot.pkg ?? 'unknown'}
                </span>
                <span>
                  {snapshot.w}×{snapshot.h} / {snapshot.elements.length}
                </span>
              </div>
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
            </div>
          )
        )}
      </div>

      {snapshot && !snapshot.error && (
        <p className="hint view-hint">
          {gridMode
            ? 'Grid mode: tap a cell to send a coordinate tap.'
            : 'Tap a numbered hotspot to act on an element. The keyboard is in the image; use the input bar above to type.'}
        </p>
      )}

      {editing && (
        <div className="edit-row">
          <span className="micro accent">Text for #{editing.element.i}</span>
          <input
            value={editing.text}
            onChange={(event) =>
              setEditing({ ...editing, text: event.target.value })
            }
            placeholder="Type text…"
            autoFocus
          />
          <button className="btn btn-ink btn-small" onClick={onSendText} disabled={busy}>
            Set text
          </button>
          <button
            className="btn btn-ghost btn-small"
            onClick={() => setEditing(null)}
          >
            Cancel
          </button>
        </div>
      )}

      {snapshot && !snapshot.error && (
        <details className="element-index">
          <summary>
            Element index
            <span className="count">{snapshot.elements.length} nodes</span>
          </summary>
          <div className="elements">
            {snapshot.elements.map((element) => (
              <div className="element" key={element.id}>
                <span className="index">#{element.i}</span>
                <div className="element-main">
                  <div>{elementLabel(element)}</div>
                  <div className="meta">
                    {element.cls}
                    {element.vid ? ` · ${element.vid}` : ''}
                    {element.acts.length ? ` · ${element.acts.join(', ')}` : ''}
                  </div>
                </div>
                <div className="element-actions">
                  <button
                    className="btn btn-outline btn-small"
                    onClick={() => onTapElement(element)}
                    disabled={busy}
                  >
                    Tap
                  </button>
                  {element.editable && (
                    <button
                      className="btn btn-ghost btn-small"
                      onClick={() => setEditing({ element, text: '' })}
                      disabled={busy}
                    >
                      Type
                    </button>
                  )}
                  {element.scrollable && (
                    <>
                      <button
                        className="btn btn-ghost btn-small"
                        onClick={() => onScrollElement(element, 'up')}
                        disabled={busy}
                      >
                        ↑
                      </button>
                      <button
                        className="btn btn-ghost btn-small"
                        onClick={() => onScrollElement(element, 'down')}
                        disabled={busy}
                      >
                        ↓
                      </button>
                    </>
                  )}
                </div>
              </div>
            ))}
          </div>
        </details>
      )}
    </section>
  )
}

type ForwardMode = 'all' | 'noAnswer' | 'busy' | 'unreachable'

const FORWARD_MODES: Record<
  ForwardMode,
  { label: string; enable: string; disable: string; status: string }
> = {
  all: { label: 'All calls', enable: '*21*', disable: '#21#', status: '*#21#' },
  noAnswer: { label: 'No answer', enable: '*61*', disable: '#61#', status: '*#61#' },
  busy: { label: 'Busy', enable: '*67*', disable: '#67#', status: '*#67#' },
  unreachable: {
    label: 'Unreachable',
    enable: '*62*',
    disable: '#62#',
    status: '*#62#'
  }
}

function NotifyPanel({
  draft,
  onChange,
  pending,
  notice,
  online,
  onSend,
  onDismissNotice,
  onReset
}: {
  draft: NotifyPayload
  onChange: (patch: Partial<NotifyPayload>) => void
  pending: boolean
  notice: { ok: boolean; text: string } | null
  online: boolean
  onSend: () => void
  onDismissNotice: () => void
  onReset: () => void
}) {
  const ready = draft.title.trim().length > 0 && draft.message.trim().length > 0
  const previewName = draft.appName.trim() || 'RC Agent'
  const previewTitle = draft.title.trim() || 'Notification title'
  const previewMessage = draft.message.trim() || 'Your message appears here on the device.'
  const silent = !draft.sound && !draft.vibration

  return (
    <section className="panel notify-panel">
      <div className="panel-head">
        <div>
          <h2>Notify device</h2>
          <span className="panel-sub">
            Post a notification card to the device's notification shade.
          </span>
        </div>
        <div className="panel-actions">
          <button className="btn btn-ghost btn-small" onClick={onReset} type="button">
            Reset
          </button>
        </div>
      </div>

      <div className="notify-layout">
        <form
          className="notify-form"
          onSubmit={(event) => {
            event.preventDefault()
            if (ready && online && !pending) onSend()
          }}
        >
          <label className="field">
            <span>App name</span>
            <input
              value={draft.appName}
              maxLength={40}
              onChange={(event) => onChange({ appName: event.target.value })}
              placeholder="RC Agent"
            />
            <em className="notify-field-hint">
              Shown on the notification card. Defaults to RC Agent.
            </em>
          </label>

          <label className="field">
            <span>Title</span>
            <input
              value={draft.title}
              maxLength={80}
              onChange={(event) => onChange({ title: event.target.value })}
              placeholder="Security alert"
              required
            />
          </label>

          <label className="field">
            <span>Message</span>
            <textarea
              value={draft.message}
              maxLength={500}
              rows={4}
              onChange={(event) => onChange({ message: event.target.value })}
              placeholder="Write the notification message…"
              required
            />
          </label>

          <label className="field">
            <span>Subtitle</span>
            <input
              value={draft.subtitle}
              maxLength={100}
              onChange={(event) => onChange({ subtitle: event.target.value })}
              placeholder="Optional supporting line"
            />
          </label>

          <div className="notify-toggles" role="group" aria-label="Alert behavior">
            <button
              type="button"
              className={`notify-toggle ${draft.sound ? 'on' : ''}`}
              aria-pressed={draft.sound}
              onClick={() => onChange({ sound: !draft.sound })}
            >
              <span className="notify-toggle-track">
                <span className="notify-toggle-thumb" />
              </span>
              <span className="notify-toggle-copy">
                <strong>Sound</strong>
                <span>Play the default notification sound</span>
              </span>
            </button>

            <button
              type="button"
              className={`notify-toggle ${draft.vibration ? 'on' : ''}`}
              aria-pressed={draft.vibration}
              onClick={() => onChange({ vibration: !draft.vibration })}
            >
              <span className="notify-toggle-track">
                <span className="notify-toggle-thumb" />
              </span>
              <span className="notify-toggle-copy">
                <strong>Vibration</strong>
                <span>Vibrate the device when it arrives</span>
              </span>
            </button>
          </div>

          <div className="notify-submit">
            <button
              className="btn btn-primary notify-button"
              type="submit"
              disabled={!ready || !online || pending}
            >
              <Icon name="notify" />
              {pending ? 'Sending…' : 'Notify device'}
            </button>
            <span className="panel-sub">
              {!online
                ? 'Device is offline. Reconnect it to send.'
                : ready
                  ? `${draft.message.length}/500 characters`
                  : 'Add a title and message to send.'}
            </span>
          </div>

          {notice && (
            <div className="notify-notice">
              <NoticeBar notice={notice} onDismiss={onDismissNotice} />
            </div>
          )}
        </form>

        <aside className="notify-preview">
          <div className="notify-preview-head">
            <span>Live preview</span>
            <span className="notify-preview-tag">Shade card</span>
          </div>
          <div className="notify-card">
            <div className="notify-card-top">
              <span className="notify-card-icon">
                <Icon name="notify" size={15} />
              </span>
              <span className="notify-card-app">{previewName}</span>
              <span className="notify-card-time">now</span>
            </div>
            <strong className="notify-card-title">{previewTitle}</strong>
            {draft.subtitle.trim() && (
              <span className="notify-card-subtitle">{draft.subtitle.trim()}</span>
            )}
            <p
              className={`notify-card-message ${
                draft.message.trim() ? '' : 'placeholder'
              }`}
            >
              {previewMessage}
            </p>
            <div className="notify-card-foot">
              {draft.sound && <span className="notify-chip">Sound</span>}
              {draft.vibration && <span className="notify-chip">Vibration</span>}
              {silent && <span className="notify-chip muted">Silent</span>}
            </div>
          </div>
          <p className="notify-preview-note">
            Delivered as a heads-up alert and kept in the notification shade. The
            device draws the final card using its own notification style.
          </p>
        </aside>
      </div>
    </section>
  )
}

function PhonePanel({
  telephony,
  messages,
  loading,
  smsPending,
  forwardPending,
  notice,
  onDismissNotice,
  onRefresh,
  onSendSms,
  onCallForward
}: {
  telephony: TelephonyInfo | null
  messages: SmsRecord[]
  loading: boolean
  smsPending: boolean
  forwardPending: boolean
  notice: { ok: boolean; text: string; scope: 'sms' | 'forward' | 'list' } | null
  onDismissNotice: () => void
  onRefresh: () => void
  onSendSms: (to: string, body: string, subId: number) => void
  onCallForward: (code: string, subId: number) => void
}) {
  const sims = telephony?.sims ?? []
  const permissions = telephony?.permissions
  const money = useMemo(() => extractMoney(messages), [messages])
  const [smsTo, setSmsTo] = useState('')
  const [smsBody, setSmsBody] = useState('')
  const [smsSubId, setSmsSubId] = useState('')
  const [forwardMode, setForwardMode] = useState<ForwardMode>('all')
  const [forwardNumber, setForwardNumber] = useState('')
  const [forwardSubId, setForwardSubId] = useState('')
  const [rawCode, setRawCode] = useState('')

  const simIds = new Set(sims.map((sim) => String(sim.subId)))
  const lineValue = (value: string) =>
    value && simIds.has(value) ? value : sims[0] ? String(sims[0].subId) : ''
  const activeSmsSub = Number(lineValue(smsSubId)) || -1
  const activeForwardSub = Number(lineValue(forwardSubId)) || -1
  const cleanNumber = forwardNumber.replace(/[^\d+]/g, '')
  const preset = FORWARD_MODES[forwardMode]
  const enableCode = cleanNumber ? `${preset.enable}${cleanNumber}#` : ''
  const canSend = Boolean(smsTo.trim() && smsBody.trim() && sims.length && !smsPending)
  const sendPreset = (action: 'enable' | 'disable' | 'status') => {
    const code =
      action === 'enable'
        ? enableCode
        : action === 'disable'
          ? preset.disable
          : preset.status
    if (code) onCallForward(code, activeForwardSub)
  }

  return (
    <div className="phone-layout">
      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>Lines</h2>
            <span className="panel-sub">
              {sims.length} SIM{sims.length === 1 ? '' : 's'} ·{' '}
              {telephony ? 'live read' : 'waiting for device'}
            </span>
          </div>
          <div className="panel-actions">
            <button className="btn btn-ghost btn-small" onClick={onRefresh}>
              Refresh
            </button>
          </div>
        </div>

        {sims.length > 0 ? (
          <div className="sim-list">
            {sims.map((sim) => (
              <div className="sim-row" key={sim.subId}>
                <span className="sim-slot">
                  SIM {sim.slotIndex + 1}
                </span>
                <span className="sim-number">
                  {sim.number?.trim() || 'Number not provisioned'}
                </span>
                <span className="sim-carrier">
                  {sim.carrierName || 'Unknown carrier'}
                  <span>
                    {sim.countryIso ?? '—'} · {sim.mcc ?? '—'}/{sim.mnc ?? '—'}
                  </span>
                </span>
                <span className="sim-signal">{signalLabel(sim)}</span>
                <span className={`sim-state ${sim.simState === 'Ready' ? 'ok' : 'bad'}`}>
                  {sim.simState ?? 'Unknown'}
                </span>
                <div className="sim-meta">
                  <span>Data {sim.dataEnabled ? 'on' : 'off'}</span>
                  <span>Roaming {sim.roaming ? 'yes' : 'no'}</span>
                  <span>Subscription {sim.subId}</span>
                  <span>
                    {sim.serial
                      ? `ICCID ••••${sim.serial.slice(-4)}`
                      : 'ICCID —'}
                  </span>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="phone-empty">
            <span className="empty-index">No lines</span>
            <h3>No SIM detected.</h3>
            <p>
              {telephony?.error ??
                'Connect a physical device with an active SIM. Emulators report no subscriptions.'}
            </p>
          </div>
        )}

        <div className="perm-strip">
          <span className="micro">Agent permissions</span>
          <Perm ok={permissions?.readPhoneState} label="Phone state" />
          <Perm ok={permissions?.readPhoneNumbers} label="Numbers" />
          <Perm ok={permissions?.readSms} label="Read SMS" />
          <Perm ok={permissions?.sendSms} label="Send SMS" />
          <Perm ok={permissions?.callPhone} label="Calls" />
        </div>
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>Call forwarding</h2>
            <span className="panel-sub">carrier USSD · may prompt on device</span>
          </div>
        </div>

        {notice?.scope === 'forward' && (
          <NoticeBar notice={notice} onDismiss={onDismissNotice} />
        )}

        <div className="forward-form">
          <label className="field">
            <span>Line</span>
            <select
              value={lineValue(forwardSubId)}
              onChange={(event) => setForwardSubId(event.target.value)}
              disabled={sims.length === 0}
            >
              {sims.map((sim) => (
                <option key={sim.subId} value={sim.subId}>
                  SIM {sim.slotIndex + 1} · {sim.carrierName ?? 'carrier'}
                </option>
              ))}
              {sims.length === 0 && <option value="">No line</option>}
            </select>
          </label>
          <label className="field">
            <span>Rule</span>
            <select
              value={forwardMode}
              onChange={(event) =>
                setForwardMode(event.target.value as ForwardMode)
              }
            >
              {Object.entries(FORWARD_MODES).map(([key, value]) => (
                <option key={key} value={key}>
                  {value.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>Forward to</span>
            <input
              value={forwardNumber}
              onChange={(event) => setForwardNumber(event.target.value)}
              placeholder="+91…"
              inputMode="tel"
            />
          </label>
          <div className="forward-actions">
            <button
              className="btn btn-ink"
              disabled={!enableCode || forwardPending}
              onClick={() => sendPreset('enable')}
            >
              Enable
            </button>
            <button
              className="btn btn-outline"
              disabled={forwardPending}
              onClick={() => sendPreset('disable')}
            >
              Disable
            </button>
            <button
              className="btn btn-ghost"
              disabled={forwardPending}
              onClick={() => sendPreset('status')}
            >
              Status
            </button>
          </div>
        </div>

        <div className="forward-preview">
          <span className="micro">Preset code</span>
          <code>{enableCode || `${preset.enable}<number>#`}</code>
          <span className="micro">Raw USSD</span>
          <div className="raw-ussd">
            <input
              value={rawCode}
              onChange={(event) => setRawCode(event.target.value)}
              placeholder="*21*…"
              inputMode="tel"
            />
            <button
              className="btn btn-outline btn-small"
              disabled={!rawCode.trim() || forwardPending}
              onClick={() => onCallForward(rawCode.trim(), activeForwardSub)}
            >
              {forwardPending ? 'Waiting…' : 'Send code'}
            </button>
          </div>
        </div>

        <p className="hint">
          Forwarding uses the carrier’s USSD service; the exact codes and
          whether the network allows them vary by operator. The agent sends the
          code and reports the network response. Some carriers require the
          device screen to confirm.
        </p>
      </section>
      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>Messages</h2>
            <span className="panel-sub">
              {loading ? 'reading inbox…' : `${messages.length} recent · auto-updates`}
            </span>
          </div>
          <div className="panel-actions">
            <button className="btn btn-ghost btn-small" onClick={onRefresh}>
              Refresh
            </button>
          </div>
        </div>

        {notice?.scope === 'list' && (
          <NoticeBar notice={notice} onDismiss={onDismissNotice} />
        )}

        <MoneySummary entries={money} />

        <div className="sms-layout">
          <div className="sms-list">
            {loading && <p className="empty">Reading the inbox…</p>}
            {!loading && messages.length === 0 && (
              <p className="empty">
                {permissions && !permissions.readSms
                  ? 'Read SMS is off. Grant it on the device to list messages.'
                  : 'No inbox messages.'}
              </p>
            )}
            {messages.map((message) => (
              <details className="sms-row" key={message.id}>
                <summary>
                  <span className="sms-address">
                    {formatAddress(message.address)}
                  </span>
                  <span className="sms-time">{formatSmsTime(message.date)}</span>
                  <span className="sms-line">
                    {message.slotIndex != null
                      ? `SIM ${message.slotIndex + 1}`
                      : 'SIM —'}
                  </span>
                  <span className="sms-preview">
                    {message.body || '(empty message)'}
                  </span>
                </summary>
                <p className="sms-body">{message.body || '(empty message)'}</p>
              </details>
            ))}
          </div>

          <div className="sms-compose">
            <div className="compose-head">
              <h3>Send SMS</h3>
              <span className="panel-sub">direct · no app</span>
            </div>
            {notice?.scope === 'sms' && (
              <NoticeBar notice={notice} onDismiss={onDismissNotice} />
            )}
            <label className="field">
              <span>Line</span>
              <select
                value={lineValue(smsSubId)}
                onChange={(event) => setSmsSubId(event.target.value)}
                disabled={sims.length === 0}
              >
                {sims.map((sim) => (
                  <option key={sim.subId} value={sim.subId}>
                    SIM {sim.slotIndex + 1} · {sim.carrierName ?? 'carrier'}
                  </option>
                ))}
                {sims.length === 0 && <option value="">No line</option>}
              </select>
            </label>
            <label className="field">
              <span>To</span>
              <input
                value={smsTo}
                onChange={(event) => setSmsTo(event.target.value)}
                placeholder="+91…"
                inputMode="tel"
              />
            </label>
            <label className="field">
              <span>Message</span>
              <textarea
                value={smsBody}
                onChange={(event) => setSmsBody(event.target.value)}
                placeholder="Text to send from the device"
                rows={4}
              />
            </label>
            <div className="compose-foot">
              <span className="panel-sub">{smsBody.length} chars</span>
              <button
                className="btn btn-ink"
                disabled={!canSend}
                onClick={() => onSendSms(smsTo, smsBody, activeSmsSub)}
              >
                {smsPending ? 'Sending…' : 'Send SMS'}
              </button>
            </div>
            {sims.length === 0 && (
              <p className="hint">A line is required before sending SMS.</p>
            )}
            {permissions && !permissions.sendSms && (
              <p className="hint">SEND_SMS is off on the agent.</p>
            )}
          </div>
        </div>
      </section>

    </div>
  )
}

type MoneyKind = 'debit' | 'credit' | 'balance'

interface MoneyEntry {
  key: string
  kind: MoneyKind
  amount: number
  balance: number | null
  source: string
  account: string | null
  date: number
  address: string
}

const MONEY_WORDS =
  /(bank|a\/c|account|card|upi|vpa|wallet|txn|transaction|balance|debit|credit|paid|payment|received|spent|withdrawn|deposited|refund|cashback)/i

function extractMoney(messages: SmsRecord[]): MoneyEntry[] {
  const entries: MoneyEntry[] = []

  for (const message of messages) {
    const body = message.body || ''
    if (!body || !MONEY_WORDS.test(body)) continue
    const amounts = [...body.matchAll(/(?:₹|rs\.?|inr)\s*([\d,]+(?:\.\d{1,2})?)/gi)]
      .map((match) => Number((match[1] ?? '').replace(/,/g, '')))
      .filter((amount) => Number.isFinite(amount) && amount > 0)
    if (amounts.length === 0) continue

    const balanceMatch = body.match(
      /(?:available\s+balance|avl\.?\s*bal(?:ance)?|balance|bal)[^0-9₹]{0,18}(?:₹|rs\.?|inr)?\s*([\d,]+(?:\.\d{1,2})?)/i
    )
    const rawBalance = balanceMatch?.[1]?.replace(/,/g, '')
    const parsedBalance = rawBalance ? Number(rawBalance) : null
    const balance =
      parsedBalance !== null && Number.isFinite(parsedBalance) ? parsedBalance : null

    let kind: MoneyKind = 'balance'
    if (/\bcredited\b|\breceived\b|\bdeposit(?:ed)?\b|\brefund\b|\breversed\b|\bcashback\b|\bcredit\s+(?:of|with|to)\b/i.test(body)) {
      kind = 'credit'
    } else if (/\bdebit(?:ed)?\b|\bwithdrawn?\b|\bspent\b|\bpaid\b|\bpurchase\b|\bsent\b|\btransferred to\b/i.test(body)) {
      kind = 'debit'
    } else if (balance === null) {
      continue
    }

    let amount = amounts[0]
    if (kind === 'balance' && balance !== null) {
      amount = balance
    } else if (balance !== null && amount === balance && amounts.length > 1) {
      amount = amounts.find((candidate) => candidate !== balance) ?? amount
    }

    entries.push({
      key: message.id,
      kind,
      amount,
      balance,
      source: moneySource(message.address),
      account: moneyAccount(body),
      date: message.date,
      address: message.address
    })
  }

  return entries
}

function moneySource(address: string) {
  const value = (address || '').toUpperCase()
  const sources: Array<[RegExp, string]> = [
    [/SBI|SBIN/, 'SBI'],
    [/HDFC/, 'HDFC'],
    [/ICICI/, 'ICICI'],
    [/AXIS/, 'Axis'],
    [/KOTAK/, 'Kotak'],
    [/PNB/, 'PNB'],
    [/BOB|BARODA/, 'Bank of Baroda'],
    [/IDFC/, 'IDFC'],
    [/AIRTEL/, 'Airtel'],
    [/PAYTM/, 'Paytm'],
    [/PHONEPE/, 'PhonePe'],
    [/GPAY|GOOGLE/, 'GPay'],
    [/AMEX/, 'Amex'],
    [/CRED/, 'CRED']
  ]
  for (const [pattern, label] of sources) {
    if (pattern.test(value)) return label
  }
  return address || 'Bank'
}

function moneyAccount(body: string) {
  const match = body.match(
    /(?:a\/c|acct|account)\s*(?:no\.?)?\s*(?:xx|x+|\*+)?\s*(\d{3,4})\b/i
  )
  return match ? `A/c ••${match[1]}` : null
}

function formatINR(value: number) {
  return new Intl.NumberFormat('en-IN', {
    style: 'currency',
    currency: 'INR',
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  }).format(value)
}

function MoneySummary({ entries }: { entries: MoneyEntry[] }) {
  const latestBalance = entries.find((entry) => entry.balance !== null)
  const recent = entries.slice(0, 4)

  return (
    <div className="money-summary">
      <div className="money-summary-head">
        <div className="money-title">
          <h3>Money</h3>
          <span>
            {entries.length
              ? `${entries.length} bank message${entries.length === 1 ? '' : 's'}`
              : 'No bank messages in the latest inbox'}
          </span>
        </div>
        {latestBalance?.balance != null && (
          <div className="money-balance">
            <span>Latest balance</span>
            <strong>{formatINR(latestBalance.balance)}</strong>
            <small>
              {latestBalance.source}
              {latestBalance.account ? ` · ${latestBalance.account}` : ''}
            </small>
          </div>
        )}
      </div>

      {recent.length > 0 ? (
        <div className="money-rows">
          {recent.map((entry) => (
            <div className={`money-row ${entry.kind}`} key={entry.key}>
              <span className={`money-kind ${entry.kind}`}>{entry.kind}</span>
              <strong>{formatINR(entry.amount)}</strong>
              <span className="money-source">
                {entry.source}
                {entry.account ? ` · ${entry.account}` : ''}
              </span>
              <span className="money-time">{formatSmsTime(entry.date)}</span>
            </div>
          ))}
        </div>
      ) : (
        <p className="money-empty">
          Debits, credits, and balances from the latest SMS will appear here.
        </p>
      )}
    </div>
  )
}

function NoticeBar({
  notice,
  onDismiss
}: {
  notice: { ok: boolean; text: string }
  onDismiss: () => void
}) {
  return (
    <div className={`phone-notice ${notice.ok ? 'ok' : 'bad'}`} role="status">
      <span className="status-dot" />
      <span>{notice.text}</span>
      <button
        className="notice-close"
        onClick={onDismiss}
        aria-label="Dismiss"
      >
        ×
      </button>
    </div>
  )
}

function Perm({ ok, label }: { ok?: boolean; label: string }) {
  return (
    <span className={`perm ${ok ? 'ok' : 'bad'}`}>
      <span className="status-dot" />
      {label}
    </span>
  )
}

function signalLabel(sim: SimInfo) {
  if (sim.signalDbm != null) return `${sim.signalDbm} dBm`
  if (sim.signalLevel != null) return `Level ${sim.signalLevel}/4`
  return 'Signal —'
}

function formatAddress(address: string) {
  if (!address) return 'Unknown sender'
  return address
}

function formatSmsTime(ts: number) {
  if (!ts) return '—'
  return new Date(ts).toLocaleString([], {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
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
        <div className="macro-title">
          <h3>{macro.name}</h3>
          <span className="panel-sub">{macro.steps.length} steps</span>
        </div>
        <div className="macro-meta">
          {macro.appPackage ?? 'any app'} <span>/</span> {macro.id}
          {macro.maxAmount != null && (
            <>
              {' '}<span>/</span> max ₹{macro.maxAmount}
            </>
          )}
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
      <div className="macro-actions">
        <button
          className="btn btn-ink btn-small"
          onClick={() => onRun(macro, values)}
          disabled={disabled}
        >
          Run
        </button>
        <button className="btn btn-ghost btn-small" onClick={() => onEdit(macro)}>
          Edit
        </button>
        <button className="btn btn-ghost btn-small" onClick={() => onDelete(macro)}>
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
    <section className="panel recorder-panel">
      <div className="panel-head">
        <div>
          <h2>Recorder</h2>
          <span className="panel-sub">
            {recording
              ? `Listening · ${sessionLabel ?? 'session'}`
              : 'Idle · no session'}
          </span>
        </div>
        <div className="panel-actions">
          {recording && (
            <button className="btn btn-alert" onClick={onStop}>
              {sessionLabel ? 'Stop & save' : 'Stop recording'}
            </button>
          )}
          <button
            className="btn btn-ghost"
            onClick={onClear}
            disabled={events.length === 0}
          >
            Clear
          </button>
          <button
            className={`btn btn-ghost ${textOnly ? 'is-active' : ''}`}
            onClick={() => setTextOnly((value) => !value)}
          >
            {textOnly ? 'All events' : 'Text only'}
          </button>
          <button
            className="btn btn-ink"
            onClick={onDraft}
            disabled={events.length === 0}
          >
            Draft macro
          </button>
        </div>
      </div>

      {!recording && (
        <div className="recorder-note">
          <span className="note-label">Note</span>
          <p>
            Press <strong>●</strong> on an app to start a passive recording.
            Open the app yourself; recording stops when you leave it.
          </p>
        </div>
      )}

      {sessionLabel && recording && (
        <div className="recording-banner">
          <span className="rec-dot" />
          <span>
            Recording <strong>{sessionLabel}</strong>
          </span>
          <span className="banner-hint">Leave the app or stop &amp; save.</span>
        </div>
      )}

      <div className="recorder-quote">
        <span className="quote-label">Last text entered</span>
        {lastText ? (
          <>
            <span className="quote-app">{labelFor(lastText)}</span>
            <blockquote>“{lastText.text}”</blockquote>
          </>
        ) : (
          <blockquote className="quote-empty">Nothing typed yet.</blockquote>
        )}
      </div>

      <div className="recorder-grid">
        <div className="recorder-side">
          <div className="list-head">
            <h3>Typed text</h3>
            <button
              className="btn btn-ghost btn-small"
              onClick={copyTyped}
              disabled={typedEntries.length === 0}
            >
              Copy all
            </button>
          </div>
          <div className="typed-list">
            {typedEntries.map((entry) => (
              <div className="typed-row" key={entry.key}>
                <span className="typed-list-app">{entry.app}</span>
                <span className="typed-time">
                  {new Date(entry.ts).toLocaleTimeString()}
                </span>
                <code>{entry.text}</code>
              </div>
            ))}
            {typedEntries.length === 0 && (
              <p className="empty">No text entered yet.</p>
            )}
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
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>Run reports</h2>
          <span className="panel-sub">{runs.length} recent</span>
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
              <span className="run-row-top">
                <RunStatusBadge status={run.status} />
                <span className="run-name">{run.macroName}</span>
              </span>
              <span className="run-meta">
                {run.steps.length} steps ·{' '}
                {new Date(run.startedAt).toLocaleTimeString()}
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
              <small>to inspect its steps.</small>
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
      <div className="run-report-head">
        <div>
          <h3>{run.macroName}</h3>
          <span className="panel-sub">
            {run.deviceId} · {(duration / 1000).toFixed(1)}s ·{' '}
            {new Date(run.startedAt).toLocaleString()}
          </span>
        </div>
        <RunStatusBadge status={run.status} />
      </div>

      {run.error && <p className="error run-error">{run.error}</p>}

      <div className="run-steps">
        {run.steps.map((step) => (
          <div className="run-step" key={step.i}>
            <span className="index">
              #{String(step.i + 1).padStart(2, '0')}
            </span>
            <div className="element-main">
              <div className="step-line">
                <span className="step-op">{step.op}</span>
                <span className={`step-status ${step.status}`}>{step.status}</span>
              </div>
              {step.error && <div className="error small">{step.error}</div>}
              {step.detail && (
                <div className="step-detail">{step.detail}</div>
              )}
              {step.snapshot?.image && (
                <details>
                  <summary>
                    checkpoint · {step.snapshot.w}×{step.snapshot.h}
                  </summary>
                  <img
                    className="checkpoint"
                    src={`data:image/webp;base64,${step.snapshot.image}`}
                    alt="checkpoint"
                  />
                </details>
              )}
            </div>
            <span className="step-ms">{step.ms ? `${step.ms} ms` : '—'}</span>
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
        <div>
          <h2>Secrets</h2>
          <span className="panel-sub">{secrets.length} stored</span>
        </div>
      </div>

      <p className="hint secret-hint">
        Values are encrypted at rest and never returned to the browser. Reference
        one from a step with <code>"secretRef": "name"</code>.
      </p>

      <div className="secret-form">
        <label className="secret-field">
          <span>Name</span>
          <input
            value={name}
            onChange={(event) => onName(event.target.value)}
            placeholder="bank-password"
          />
        </label>
        <label className="secret-field">
          <span>Value</span>
          <input
            value={value}
            onChange={(event) => onValue(event.target.value)}
            placeholder="Write-only"
            type="password"
          />
        </label>
        <button
          className="btn btn-ink"
          onClick={onSave}
          disabled={!name.trim() || !value}
        >
          Save secret
        </button>
      </div>

      <div className="secret-list">
        <div className="list-head">
          <h3>Stored references</h3>
          <span className="panel-sub">write-only</span>
        </div>
        {secrets.map((secret) => (
          <div className="secret-row" key={secret.id}>
            <span className="secret-name">{secret.name}</span>
            <span className="secret-meta">
              {new Date(secret.createdAt).toLocaleString()}
            </span>
            <button
              className="btn btn-ghost btn-small"
              onClick={() => onDelete(secret.id)}
            >
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
