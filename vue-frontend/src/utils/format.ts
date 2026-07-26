/** 流量格式化。unit='gb' 时按 GB 展示；99999 视为无限制 */
export function formatFlow(value: number, unit: 'bytes' | 'gb' = 'bytes'): string {
  if (value === 99999) return '无限制'
  if (unit === 'gb') return `${value} GB`
  if (!value || value === 0) return '0 B'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(2)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(2)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

/** 用户页流量格式化：无 99999 特例 */
export function formatFlowPlain(value: number, unit: 'bytes' | 'gb' = 'bytes'): string {
  if (unit === 'gb') return `${value} GB`
  if (!value || value === 0) return '0 B'
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(2)} KB`
  if (value < 1024 * 1024 * 1024) return `${(value / 1024 / 1024).toFixed(2)} MB`
  return `${(value / 1024 / 1024 / 1024).toFixed(2)} GB`
}

export function formatNumber(value: number): string {
  if (value === 99999) return '无限制'
  return String(value)
}

export function formatSpeed(bytesPerSecond: number): string {
  if (!bytesPerSecond || bytesPerSecond === 0) return '0 B/s'
  const units = ['B/s', 'KB/s', 'MB/s', 'GB/s', 'TB/s']
  const i = Math.floor(Math.log(bytesPerSecond) / Math.log(1024))
  return `${parseFloat((bytesPerSecond / Math.pow(1024, i)).toFixed(2))} ${units[i]}`
}

export function formatTraffic(bytes: number): string {
  if (!bytes || bytes === 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.floor(Math.log(bytes) / Math.log(1024))
  return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`
}

export function formatUptime(seconds: number): string {
  if (!seconds || seconds === 0) return '-'
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return `${days}天${hours}小时`
  if (hours > 0) return `${hours}小时${minutes}分钟`
  return `${minutes}分钟`
}

export function formatDate(timestamp?: number): string {
  if (!timestamp) return '-'
  return new Date(timestamp).toLocaleString()
}

/** 距离每月重置日的文案 */
export function formatResetTime(resetDay?: number | null): string {
  if (resetDay === undefined || resetDay === null) return ''
  if (resetDay === 0) return '不重置'
  const now = new Date()
  const today = now.getDate()
  let days: number
  if (resetDay >= today) {
    days = resetDay - today
  } else {
    const daysInMonth = new Date(now.getFullYear(), now.getMonth() + 1, 0).getDate()
    days = daysInMonth - today + resetDay
  }
  if (days === 0) return '今日重置'
  if (days === 1) return '明日重置'
  return `${days}天后重置`
}

/** 入口地址展示：ip:port，多地址显示 first:port (+n) */
export function formatInAddress(ipString: string, port: number): string {
  if (!ipString) return '-'
  const ips = ipString.split(',').map((s) => s.trim()).filter(Boolean)
  if (ips.length === 0) return '-'
  const one = (ip: string) => (ip.includes(':') && !ip.startsWith('[') ? `[${ip}]:${port}` : `${ip}:${port}`)
  if (ips.length === 1) return one(ips[0])
  return `${one(ips[0])} (+${ips.length - 1})`
}

export function formatRemoteAddress(remoteAddr: string): string {
  if (!remoteAddr) return '-'
  const addrs = remoteAddr.split(',').map((s) => s.trim()).filter(Boolean)
  if (addrs.length <= 1) return addrs[0] || '-'
  return `${addrs[0]} (+${addrs.length - 1})`
}

export function hasMultiple(value: string): boolean {
  if (!value) return false
  return value.split(',').map((s) => s.trim()).filter(Boolean).length > 1
}
