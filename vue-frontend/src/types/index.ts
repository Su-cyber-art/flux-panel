// ============ 通用响应 ============
export interface ApiResponse<T = any> {
  code: number
  msg: string
  ts?: number
  data: T
}

// ============ 登录/用户 ============
export interface LoginData {
  username: string
  password: string
  captchaId: string
}

export interface LoginResponse {
  token: string
  role_id: number
  name: string
  requirePasswordChange?: boolean
}

export interface User {
  id: number
  name?: string
  user: string
  status: number // 1 正常 0 禁用
  flow: number // GB
  num: number // 转发数量
  expTime?: number
  flowResetTime?: number // 1-31, 0 不重置
  createdTime?: number
  inFlow?: number // bytes
  outFlow?: number // bytes
}

export interface UserForm {
  id?: number
  name?: string
  user: string
  pwd?: string
  status: number
  flow: number
  num: number
  expTime: number | null
  flowResetTime: number
}

export interface UserTunnel {
  id: number
  userId: number
  tunnelId: number
  tunnelName: string
  status: number // 1 正常 0 禁用
  flow: number // GB
  num: number
  expTime: number
  flowResetTime: number
  speedId?: number | null
  speedLimitName?: string
  inFlow?: number
  outFlow?: number
  tunnelFlow?: number // 1 单向 2 双向
}

// 隧道权限分配 / 编辑表单。speedId 用 'null' 字符串表示“不限速”，提交时转为 null。
export interface UserTunnelForm {
  id?: number
  userId?: number
  tunnelId: number | null
  flow: number
  num: number
  expTime: number | null
  flowResetTime: number
  speedId: number | 'null'
  status: number
}

// ============ 隧道 ============
export interface Tunnel {
  id: number
  name: string
  type: number // 1 端口转发 2 隧道转发
  inNodeId: number
  outNodeId?: number
  chainNodeIds: number[]
  inIp?: string
  outIp?: string
  protocol?: string
  tcpListenAddr?: string
  udpListenAddr?: string
  interfaceName?: string
  flow: number // 1 单向 2 双向
  trafficRatio: number
  status: number
  createdTime?: string
  inNodePortSta?: number
  inNodePortEnd?: number
}

export interface TunnelForm {
  id?: number
  name: string
  type: number
  inNodeId: number | null
  outNodeId?: number | null
  chainNodeIds: number[]
  protocol: string
  tcpListenAddr: string
  udpListenAddr: string
  interfaceName?: string
  flow: number
  trafficRatio: number
  status: number
}

// ============ 节点 ============
export interface NodeSystemInfo {
  cpuUsage: number
  memoryUsage: number
  uploadTraffic: number
  downloadTraffic: number
  uploadSpeed: number
  downloadSpeed: number
  uptime: number
}

export interface NodeItem {
  id: number
  name: string
  ip: string
  serverIp: string
  portSta: number
  portEnd: number
  version?: string
  http?: number
  tls?: number
  socks?: number
  status: number // 1 在线 0 离线
  connectionStatus: 'online' | 'offline'
  systemInfo?: NodeSystemInfo | null
  copyLoading?: boolean
}

export interface NodeForm {
  id: number | null
  name: string
  ipString: string
  serverIp: string
  portSta: number
  portEnd: number
  http: number
  tls: number
  socks: number
}

// ============ 转发 ============
export interface Forward {
  id: number
  name: string
  tunnelId: number
  tunnelName: string
  inIp: string
  inPort: number
  remoteAddr: string
  interfaceName?: string
  strategy: string
  status: number
  inFlow: number
  outFlow: number
  serviceRunning: boolean
  createdTime?: number
  userName?: string
  userId?: number
  inx?: number
  syncStatus?: 'PENDING' | 'SYNCED' | 'FAILED'
  syncError?: string
  syncTaskStatus?: 'PENDING' | 'PROCESSING' | 'FAILED'
  syncOperation?: 'UPSERT' | 'DELETE'
  deleteRequested?: boolean
  syncAttempts?: number
  syncNextAttemptAt?: number
  syncUpdatedTime?: number
}

export interface ForwardForm {
  id?: number
  userId?: number
  name: string
  tunnelId: number | null
  inPort: number | null
  remoteAddr: string
  interfaceName?: string
  strategy: string
}

export interface ForwardPortAvailability {
  available: boolean
  message: string
  port: number
  minPort?: number
  maxPort?: number
}

// ============ 限速 ============
export interface SpeedLimitRule {
  id: number
  name: string
  speed: number
  status: number
  tunnelId: number
  tunnelName: string
  createdTime?: number
  updatedTime?: number
}

export interface SpeedLimitForm {
  id?: number
  name: string
  speed: number
  tunnelId: number | null
  tunnelName: string
  status: number
}

// ============ 诊断 ============
export interface DiagnosisResultItem {
  category?: string // LISTENER / HOP / TARGET / LOOPBACK
  nodeId?: number | string
  nodeName?: string
  targetIp?: string
  targetPort?: number
  description: string
  success: boolean
  message?: string
  averageTime?: number
  minTime?: number
  maxTime?: number
  jitter?: number
  packetLoss?: number
  rounds?: number
  okRounds?: number
  bytesVerified?: number
  integrityOk?: boolean
}

export interface DiagnosisReport {
  forwardId?: number
  tunnelId?: number
  forwardName?: string
  tunnelName?: string
  tunnelType?: string
  pathNodeIds?: number[]
  results: DiagnosisResultItem[]
  summary?: { total: number; passed: number; failed: number }
  timestamp: number
}
