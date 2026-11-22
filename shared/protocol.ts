// Shared wire protocol for the RC agent, relay, and admin panel.
// Keep this file dependency-free so both server and web can import it.

export type Role = 'device' | 'admin'

export interface HelloMessage {
  t: 'hello'
  role: Role
  token: string
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

export type ActionName =
  | 'listApps'
  | 'snapshot'
  | 'launch'
  | 'tap'
  | 'tapXY'
  | 'setText'
  | 'scroll'
  | 'globalAction'
  | 'probe'
  | 'probeTargets'

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
