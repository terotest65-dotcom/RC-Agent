// Shared wire protocol for the RC agent, relay, and admin panel.
// Keep this file dependency-free so both server and web can import it.

export type Role = 'device' | 'admin'

export interface HelloMessage {
  t: 'hello'
  role: Role
  deviceId?: string
  name?: string
  model?: string
  android?: number
  agent?: string
  protocolVersion?: number
}

export interface AppInfo {
  pkg: string
  label: string
  version: string
  code: number
  system: boolean
}

export interface DeviceState {
  online: boolean
  screenOn: boolean
  locked: boolean
  secureLock: boolean
  battery: number
  charging: boolean
  a11y: boolean
  notifAccess: boolean
  deviceOwner: boolean
  stayAwake: boolean
  package?: string | null
}

export interface Element {
  i: number
  id: string
  b: [number, number, number, number]
  text?: string | null
  desc?: string | null
  vid?: string | null
  cls: string
  acts: string[]
  editable: boolean
  scrollable: boolean
  clickable: boolean
}

export interface SnapshotMessage {
  t: 'snapshot'
  deviceId: string
  id: string
  ts: number
  w: number
  h: number
  rotation: number
  pkg?: string | null
  activity?: string | null
  image: string // base64 webp, no data: prefix
  elements: Element[]
  nodeCount: number
  maxDepth: number
  elapsedMs: number
  error?: string | null
}

export type Verdict = 'controllable' | 'partial' | 'blocked'

export interface ProbeResult {
  deviceId: string
  pkg: string
  verdict: Verdict
  screenshotOk: boolean
  secureWindow: boolean
  nodeCount: number
  clickableCount: number
  viewIdCount: number
  depth: number
  elapsedMs: number
  error?: string | null
  probedAt: number
}

export interface DeviceSummary {
  id: string
  name: string
  model?: string
  android?: number
  agent?: string
  online: boolean
  lastSeen?: number
  apps?: AppInfo[]
  state?: DeviceState
  probes?: Record<string, ProbeResult>
}

export interface DevicesMessage {
  t: 'devices'
  devices: DeviceSummary[]
}

export interface DeviceMessage {
  t: 'device'
  device: DeviceSummary
}

export interface AppsMessage {
  t: 'apps'
  deviceId: string
  apps: AppInfo[]
}

export interface StateMessage {
  t: 'state'
  deviceId: string
  state: DeviceState
}

export interface ActionResultMessage {
  t: 'actionResult'
  deviceId: string
  cmdId: string
  ok: boolean
  error?: string
}

export interface ProbeMessage {
  t: 'probe'
  deviceId: string
  result: ProbeResult
}

export interface ErrorMessage {
  t: 'error'
  message: string
}

export interface HelloOkMessage {
  t: 'helloOk'
  deviceId: string
}

export interface Selector {
  viewId?: string
  text?: string
  contains?: string
  desc?: string
  className?: string
}

export type MacroOp =
  | 'launch'
  | 'waitFor'
  | 'waitForIdle'
  | 'waitForOtp'
  | 'tap'
  | 'tapIfExists'
  | 'setText'
  | 'scroll'
  | 'globalAction'
  | 'screenshot'
  | 'assert'
  | 'sleep'

export interface MacroStep {
  op: MacroOp
  package?: string
  selector?: Selector
  text?: string
  timeoutMs?: number
  expect?: Selector
  optional?: boolean
  dir?: 'up' | 'down'
  name?: string
  label?: string
  ms?: number
  secretRef?: string
  secret?: boolean
}

export interface MacroParam {
  type: 'string' | 'number'
  required?: boolean
  default?: string | number
}

export interface Macro {
  id: string
  name: string
  appPackage?: string
  params?: Record<string, MacroParam>
  steps: MacroStep[]
  createdAt?: number
  updatedAt?: number
}

export type RunStatus = 'running' | 'success' | 'failed' | 'unverified' | 'cancelled'
export type StepStatus = 'ok' | 'failed' | 'unverified' | 'skipped' | 'running'

export interface RunStep {
  i: number
  op: string
  status: StepStatus
  ms?: number
  error?: string | null
  label?: string
  snapshot?: SnapshotMessage
}

export interface RunRecord {
  id: string
  macroId: string
  macroName: string
  deviceId: string
  status: RunStatus
  startedAt: number
  finishedAt?: number
  steps: RunStep[]
  params?: Record<string, unknown>
}

export interface RecordEvent {
  ts: number
  type: 'text' | 'tap' | 'scroll' | 'window'
  app?: string | null
  appLabel?: string | null
  activity?: string | null
  viewId?: string | null
  className?: string | null
  text?: string | null
  delta?: string | null
  before?: string | null
  label?: string | null
  deltaY?: number | null
  redacted?: boolean
  masked?: boolean
}

export interface MacrosMessage {
  t: 'macros'
  macros: Macro[]
}

export interface MacroSavedMessage {
  t: 'macroSaved'
  macro: Macro
}

export interface RunsMessage {
  t: 'runs'
  runs: RunRecord[]
}

export interface RunStartedMessage {
  t: 'runStarted'
  run: RunRecord
}

export interface RunStepMessage {
  t: 'runStep'
  runId: string
  step: RunStep
}

export interface RunFinishedMessage {
  t: 'runFinished'
  run: RunRecord
}

export interface RecordEventMessage {
  t: 'recordEvent'
  deviceId: string
  event: RecordEvent
  sessionId?: string
}

export interface RecordingStateMessage {
  t: 'recordingState'
  deviceId: string
  recording: boolean
  sessionId?: string
}

export interface PromptMessage {
  t: 'prompt'
  deviceId: string
  runId: string
  promptId: string
  kind: 'otp' | 'text'
  message: string
}

export interface SecretInfo {
  id: string
  name: string
  createdAt: number
}

export interface SecretsMessage {
  t: 'secrets'
  secrets: SecretInfo[]
}

export interface SecretSavedMessage {
  t: 'secretSaved'
  secret: SecretInfo
}

export type ServerMessage =
  | DevicesMessage
  | DeviceMessage
  | AppsMessage
  | StateMessage
  | SnapshotMessage
  | ActionResultMessage
  | ProbeMessage
  | ErrorMessage
  | HelloOkMessage
  | MacrosMessage
  | MacroSavedMessage
  | RunsMessage
  | RunStartedMessage
  | RunStepMessage
  | RunFinishedMessage
  | RecordEventMessage
  | RecordingStateMessage
  | PromptMessage
  | SecretsMessage
  | SecretSavedMessage

export type ActionName =
  | 'listApps'
  | 'snapshot'
  | 'launch'
  | 'tap'
  | 'tapXY'
  | 'setText'
  | 'inputText'
  | 'scroll'
  | 'globalAction'
  | 'probe'
  | 'probeTargets'
  | 'runMacro'
  | 'cancelRun'
  | 'startRecording'
  | 'stopRecording'
  | 'promptResult'

export interface CommandMessage {
  t: 'cmd'
  cmdId: string
  deviceId: string
  action: ActionName
  [key: string]: unknown
}

export const TARGET_APPS = [
  'com.whatsapp',
  'com.google.android.apps.nbu.paisa.user',
  'com.phonepe.app',
  'net.one97.paytm',
  'com.android.chrome',
  'com.android.settings'
]

export function isTargetApp(pkg: string): boolean {
  return TARGET_APPS.includes(pkg)
}
