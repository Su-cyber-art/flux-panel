// Local, disposable UI preview. It never connects to real nodes or a database.
import { createServer } from 'node:http'
import { createHash } from 'node:crypto'
import { spawn } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import path from 'node:path'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const uiPort = Number(process.env.UI_PREVIEW_PORT || 3000)
const apiPort = Number(process.env.UI_PREVIEW_API_PORT || 17365)
const GiB = 1024 ** 3
const tunnels = [
  { id: 1, name: '香港 · 精品线路', inNodeId: 1, outNodeId: 2, type: 2, chainNodeIds: [], inIp: '198.51.100.12', protocol: 'tls', flow: 2, trafficRatio: 1, status: 1, inNodePortSta: 10000, inNodePortEnd: 60000 },
  { id: 2, name: '东京 · 高速线路', inNodeId: 2, outNodeId: 3, type: 2, chainNodeIds: [], inIp: '203.0.113.24', protocol: 'tls', flow: 2, trafficRatio: 1, status: 1, inNodePortSta: 10000, inNodePortEnd: 60000 },
  { id: 3, name: '新加坡 · IPv6', inNodeId: 3, outNodeId: 1, type: 2, chainNodeIds: [], inIp: '2001:db8::10', protocol: 'tls', flow: 1, trafficRatio: 1, status: 1, inNodePortSta: 10000, inNodePortEnd: 60000 },
]
const seeds = [
  ['官网服务', 1, 10080, 'web.example.com:443', 1, 12.8, 63.4],
  ['API 网关', 1, 10443, '10.0.0.12:8080', 1, 8.4, 41.2],
  ['SSH · 运维入口', 2, 10022, '10.0.0.8:22', 0, 0.12, 0.86],
  ['对象存储', 1, 10900, 'storage-a.example.com:9000,storage-b.example.com:9000', 1, 42.6, 128.3],
  ['开发环境', 3, 13000, '[fd00::8]:3000', 0, 0.85, 4.2],
  ['监控采集', 2, 19090, 'monitor.example.com:9090', 1, 1.6, 7.9],
  ['Git 服务', 2, 22222, 'git.example.com:22', 1, 3.7, 18.2],
  ['团队文档', 3, 18080, 'docs.example.com:8080', 1, 0.31, 2.4],
]
let forwards = seeds.map(([name, tunnelId, inPort, remoteAddr, status, up, down], index) => {
  const tunnel = tunnels.find(item => item.id === tunnelId)
  return { id: index + 1, name, tunnelId, tunnelName: tunnel.name, inIp: tunnel.inIp, inPort, remoteAddr, status,
    strategy: index === 3 ? 'round' : 'fifo', inFlow: Math.round(up * GiB), outFlow: Math.round(down * GiB),
    userId: 1, userName: 'Demo', inx: index + 1, syncStatus: index === 1 ? 'FAILED' : 'SYNCED',
    syncTaskStatus: index === 1 ? 'FAILED' : null, syncError: index === 1 ? '出口节点暂时离线，节点恢复后自动重试' : null,
    syncAttempts: index === 1 ? 2 : 0, createdTime: Date.now() - 86400000 * (index + 1) }
})
let nextId = 100
let failNextList = false
const envelope = (data = null, msg = '操作成功', code = 0) => ({ code, msg, data })
const token = () => Buffer.from(JSON.stringify({ alg: 'HS256', typ: 'JWT' })).toString('base64url') + '.' +
  Buffer.from(JSON.stringify({ sub: 1, role_id: 0, name: 'Demo', exp: Math.floor(Date.now() / 1000) + 86400 })).toString('base64url') + '.local-preview'
function transition(row) { row.syncStatus = 'PENDING'; row.syncTaskStatus = 'PENDING'; row.syncError = null; row.readyAt = Date.now() + 1800 }
function settle() {
  forwards = forwards.filter(row => !(row.deleteRequested && row.readyAt && row.readyAt <= Date.now()))
  for (const row of forwards) if (row.readyAt && row.readyAt <= Date.now()) {
    row.syncStatus = 'SYNCED'; row.syncTaskStatus = null; row.readyAt = null
  }
}
const server = createServer(async (request, response) => {
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  let input = ''
  for await (const chunk of request) input += chunk
  let body = {}
  try { body = JSON.parse(input || '{}') } catch {}
  const url = new URL(request.url, 'http://127.0.0.1')
  const send = value => response.end(JSON.stringify(value))
  const route = url.pathname.replace('/api/v1', '')
  settle()
  if (route === '/__preview/fail-next-list') { failNextList = true; return send(envelope()) }
  if (route === '/captcha/check') return send(envelope(0))
  if (route === '/user/login') return send(envelope({ token: token(), role_id: 0, name: 'Demo' }))
  if (route === '/config/get') return send(envelope({ name: body.name, value: body.name === 'app_name' ? 'flux' : '' }))
  if (route === '/config/list') return send(envelope({ app_name: 'flux', captcha_enabled: 'false' }))
  if (route === '/tunnel/user/tunnel' || route === '/tunnel/list') return send(envelope(tunnels))
  if (route === '/forward/list') {
    if (failNextList) { failNextList = false; return send(envelope(null, '模拟网络波动', -1)) }
    return send(envelope(forwards))
  }
  if (route === '/forward/check-port') {
    const valid = body.inPort >= 10000 && body.inPort <= 60000
    const available = valid && !forwards.some(row => row.tunnelId === body.tunnelId && row.inPort === body.inPort && row.id !== body.excludeForwardId)
    return send(envelope({ available, port: body.inPort, minPort: 10000, maxPort: 60000, message: available ? '入口端口可用' : valid ? '入口端口已被占用，请更换端口' : '允许范围：10000–60000' }))
  }
  if (route === '/forward/create' || route === '/forward/update') {
    const tunnel = tunnels.find(item => item.id === body.tunnelId)
    if (!tunnel) return send(envelope(null, '请选择隧道', -1))
    let row = route.endsWith('update') ? forwards.find(item => item.id === body.id) : null
    if (!row) { row = { id: nextId++, userId: 1, userName: 'Demo', inFlow: 0, outFlow: 0, inx: forwards.length + 1, createdTime: Date.now() }; forwards.push(row) }
    Object.assign(row, body, { tunnelName: tunnel.name, inIp: tunnel.inIp, inPort: body.inPort || 10100 + row.id, status: 1 })
    transition(row)
    return send(envelope())
  }
  if (route === '/forward/diagnose') {
    const row = forwards.find(item => item.id === body.forwardId)
    return send(envelope({ forwardId: row?.id, forwardName: row?.name, timestamp: Date.now(),
      summary: { total: 4, passed: 4, failed: 0 }, results: ['LISTENER', 'HOP', 'TARGET', 'LOOPBACK'].map((category, index) => ({
        category, description: ['入口监听端口', '香港 → 东京', '出口 → 目标服务', '端到端数据回环'][index],
        nodeName: ['香港入口', '香港入口', '东京出口', '香港入口'][index], success: true, message: '检查通过',
        targetIp: index === 0 ? '127.0.0.1' : row?.remoteAddr.split(',')[0].split(':')[0], targetPort: row?.inPort,
        averageTime: [0.46, 29.7, 1.32, 58.2][index], minTime: .4, maxTime: 62, jitter: [0.02, 1.3, .08, 2.1][index], packetLoss: 0,
        ...(category === 'LOOPBACK' ? { integrityOk: true, bytesVerified: 4096, rounds: 2, okRounds: 2 } : {}),
      })) }))
  }
  if (route === '/forward/update-order') {
    for (const item of body.forwards || []) { const row = forwards.find(row => row.id === item.id); if (row) row.inx = item.inx }
    return send(envelope())
  }
  if (route.startsWith('/forward/')) {
    const row = forwards.find(item => item.id === body.id)
    if (!row) return send(envelope(null, '转发不存在', -1))
    if (route.endsWith('/force-delete')) { forwards = forwards.filter(item => item.id !== row.id); return send(envelope()) }
    if (route.endsWith('/pause')) row.status = 0
    else if (route.endsWith('/resume')) row.status = 1
    else if (route.endsWith('/delete')) { row.deleteRequested = true; row.status = -2; row.syncOperation = 'DELETE' }
    else if (!route.endsWith('/sync/retry')) return send(envelope(null, '预览未提供该操作', -1))
    transition(row)
    return send(envelope())
  }
  if (route === '/node/list') return send(envelope(tunnels.map((tunnel, index) => ({ id: index + 1, name: tunnel.name.split(' · ')[0], ip: tunnel.inIp, serverIp: tunnel.inIp, portSta: 10000, portEnd: 60000, status: 1, version: '1.5.9', http: 0, tls: 0, socks: 0 }))))
  if (route === '/user/package') return send(envelope({
    userInfo: { flow: 2000, num: 100, inFlow: 70 * GiB, outFlow: 268 * GiB, expTime: Date.now() + 180 * 86400000, flowResetTime: 1 },
    tunnelPermissions: tunnels.map(tunnel => ({ id: tunnel.id, tunnelId: tunnel.id, tunnelName: tunnel.name, flow: 1000, num: 30, inFlow: 12 * GiB, outFlow: 63 * GiB, expTime: Date.now() + 90 * 86400000, flowResetTime: 1, tunnelFlow: 2 })),
    forwards, statisticsFlows: Array.from({ length: 12 }, (_, index) => ({ id: index, userId: 1, flow: (index % 4 + 1) * GiB, totalFlow: 0, time: String(index * 2).padStart(2, '0') + ':00' })),
  }))
  if (route === '/user/list' || route === '/speed-limit/list' || route === '/tunnel/user/list') return send(envelope([]))
  return send(envelope(null, '此操作需连接真实后端；当前为本地预览', -1))
})
const sockets = new Set()
server.on('upgrade', (request, socket) => {
  const key = request.headers['sec-websocket-key']
  if (!key) return socket.end()
  const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n')
  sockets.add(socket)
  let uptime = 1800
  const timer = setInterval(() => {
    uptime += 2
    for (let id = 1; id <= 3; id++) {
      const data = Buffer.from(JSON.stringify({ id, type: 'info', data: { cpu_usage: 10 + id * 7, memory_usage: 22 + id * 8, bytes_transmitted: uptime * 300000, bytes_received: uptime * 900000, uptime } }))
      const header = Buffer.alloc(4); header[0] = 0x81; header[1] = 126; header.writeUInt16BE(data.length, 2)
      if (!socket.destroyed) socket.write(Buffer.concat([header, data]))
    }
  }, 2000)
  socket.on('error', () => {})
  socket.on('close', () => { clearInterval(timer); sockets.delete(socket) })
})
server.listen(apiPort, '127.0.0.1', () => {
  console.log('Local preview data only. Sign in with any nonempty username and password.')
  const vite = spawn(process.execPath, [path.join(root, 'node_modules/vite/bin/vite.js'), '--host', '127.0.0.1', '--port', String(uiPort), '--strictPort'], {
    cwd: root, stdio: 'inherit', env: { ...process.env, VITE_API_BASE: '', VITE_DEV_BACKEND: 'http://127.0.0.1:' + apiPort, VITE_UI_PREVIEW: 'true' },
  })
  function stop() { for (const socket of sockets) socket.destroy(); vite.kill('SIGTERM'); server.close() }
  process.on('SIGINT', stop); process.on('SIGTERM', stop)
  vite.on('exit', () => { for (const socket of sockets) socket.destroy(); server.close() })
})
server.on('error', error => { console.error(error.message); process.exitCode = 1 })
