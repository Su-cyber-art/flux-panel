import { onUnmounted } from 'vue'
import { getBaseURL } from '@/api/http'

export interface NodeSocketMessage {
  id: string | number
  type: string
  data: any
}

/**
 * 节点实时遥测 WebSocket。
 * 连接 /system-info?type=0&secret=<token>，自动重连（最多 5 次，退避递增）。
 */
export function useNodeSocket(onMessage: (msg: NodeSocketMessage) => void, onClosed?: () => void) {
  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let attempts = 0
  const maxAttempts = 5
  let manualClose = false

  function buildUrl(): string {
    const base = getBaseURL() || '/api/v1/'
    const wsBase = base.replace(/^http/, 'ws').replace(/\/api\/v1\/$/, '')
    const token = localStorage.getItem('token') || ''
    return `${wsBase}/system-info?type=0&secret=${token}`
  }

  function connect() {
    try {
      ws = new WebSocket(buildUrl())
      ws.onopen = () => {
        attempts = 0
      }
      ws.onmessage = (evt) => {
        try {
          const parsed = JSON.parse(evt.data)
          onMessage(parsed)
        } catch {
          /* ignore malformed frames */
        }
      }
      ws.onerror = () => {
        /* silent, onclose handles reconnect */
      }
      ws.onclose = () => {
        if (!manualClose) scheduleReconnect()
      }
    } catch {
      scheduleReconnect()
    }
  }

  function scheduleReconnect() {
    if (manualClose || attempts >= maxAttempts) return
    attempts += 1
    reconnectTimer = setTimeout(connect, 3000 * attempts)
  }

  function close() {
    manualClose = true
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = null
    attempts = 0
    if (ws) {
      ws.onopen = ws.onmessage = ws.onerror = ws.onclose = null
      if (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING) ws.close()
      ws = null
    }
    onClosed?.()
  }

  connect()
  onUnmounted(close)

  return { close }
}
