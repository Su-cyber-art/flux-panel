<script setup lang="ts">
import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import {
  NButton,
  NModal,
  NInput,
  NInputNumber,
  NSelect,
  NTag,
  NSwitch,
  NAlert,
  NCollapse,
  NCollapseItem,
} from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import AddressModal from '@/components/AddressModal.vue'
import DiagnosisDialog from '@/components/DiagnosisDialog.vue'
import { useToast } from '@/composables/useToast'
import { JwtUtil } from '@/utils/jwt'
import { copyText } from '@/utils/clipboard'
import { formatFlow, formatInAddress, formatRemoteAddress, hasMultiple } from '@/utils/format'
import {
  getForwardList,
  userTunnel,
  createForward,
  updateForward,
  deleteForward,
  forceDeleteForward,
  pauseForwardService,
  resumeForwardService,
  diagnoseForward,
  checkForwardPort,
  retryForwardSync,
  updateForwardOrder,
} from '@/api'
import type { Forward, ForwardForm, Tunnel, DiagnosisReport } from '@/types'

// ============ 类型 ============
type TagType = 'default' | 'error' | 'primary' | 'info' | 'success' | 'warning'
type SyncFilter = 'ALL' | 'PENDING' | 'SYNCED' | 'FAILED'
type PortCheckStatus = 'idle' | 'checking' | 'available' | 'unavailable' | 'error'
type ViewMode = 'grouped' | 'direct'
interface TunnelGroup {
  tunnelId: number
  tunnelName: string
  forwards: Forward[]
}
interface UserGroup {
  userId: number | null
  userName: string
  tunnelGroups: TunnelGroup[]
}

const toast = useToast()
const currentUserId = JwtUtil.getUserIdFromToken()

// ============ 基础状态 ============
const loading = ref(true)
const forwards = ref<Forward[]>([])
const tunnels = ref<Tunnel[]>([])
const forwardListRequestId = ref(0)
const syncFilter = ref<SyncFilter>('ALL')
const forwardOrder = ref<number[]>([])

const viewMode = ref<ViewMode>(
  (localStorage.getItem('forward-view-mode') as ViewMode) === 'grouped' ? 'grouped' : 'direct',
)

let pollTimer: number | null = null

// ============ 常量文案 ============
const REMOTE_PLACEHOLDER =
  '请输入远程地址，多个地址用换行分隔\n例如:\n192.168.1.100:8080\nexample.com:3000'

const strategyOptions = [
  { label: '主备模式 - 自上而下', value: 'fifo' },
  { label: '轮询模式 - 依次轮换', value: 'round' },
  { label: '随机模式 - 随机选择', value: 'rand' },
  { label: '哈希模式 - IP哈希', value: 'hash' },
]

const tunnelOptions = computed(() => tunnels.value.map((t) => ({ label: t.name, value: t.id })))

// ============ 显示映射 ============
function getStatusDisplay(status: number): { type: TagType; text: string } {
  switch (status) {
    case 1:
      return { type: 'success', text: '正常' }
    case 0:
      return { type: 'warning', text: '暂停' }
    case -1:
      return { type: 'error', text: '异常' }
    case -2:
      return { type: 'warning', text: '删除中' }
    default:
      return { type: 'default', text: '未知' }
  }
}

function getSyncDisplay(f: Forward): { type: TagType; text: string; description: string } {
  const action = f.syncOperation === 'DELETE' ? '删除节点配置' : '下发节点配置'
  switch (f.syncTaskStatus) {
    case 'PROCESSING':
      return { type: 'warning', text: '正在同步', description: `正在${action}` }
    case 'PENDING':
      return { type: 'warning', text: '等待同步', description: `已保存，等待${action}` }
    case 'FAILED':
      return { type: 'error', text: '同步失败', description: f.syncError ?? `${action}失败` }
  }
  if (!f.syncTaskStatus && f.syncStatus === 'SYNCED') {
    return { type: 'success', text: '已同步', description: '节点配置已生效' }
  }
  return { type: 'warning', text: '等待同步', description: `已保存，等待${action}` }
}

function getStrategyDisplay(strategy: string): { type: TagType; text: string } {
  switch (strategy) {
    case 'fifo':
      return { type: 'primary', text: '主备' }
    case 'round':
      return { type: 'success', text: '轮询' }
    case 'rand':
      return { type: 'warning', text: '随机' }
    case 'hash':
      return { type: 'info', text: '哈希' }
    default:
      return { type: 'default', text: '未知' }
  }
}

function showSyncAlert(f: Forward): boolean {
  return !!f.syncTaskStatus || f.syncStatus !== 'SYNCED'
}

// ============ 数据加载 ============
async function loadData(lod = true, forwardsOnly = false) {
  if (lod) loading.value = true
  const requestId = ++forwardListRequestId.value
  try {
    const [forwardsRes, tunnelsRes] = await Promise.all([
      getForwardList(),
      forwardsOnly ? Promise.resolve(null) : userTunnel(),
    ])
    if (requestId !== forwardListRequestId.value) return
    if (forwardsRes.code === 0) {
      const list: Forward[] = (forwardsRes.data || []).map((f: Forward) => ({
        ...f,
        serviceRunning: f.status === 1,
      }))
      forwards.value = list
      if (viewMode.value === 'direct') initForwardOrder(list)
    } else {
      toast.error(forwardsRes.msg || '获取转发列表失败')
    }
    if (tunnelsRes) {
      if (tunnelsRes.code === 0) {
        tunnels.value = tunnelsRes.data || []
      } else {
        console.warn('获取隧道列表失败:', tunnelsRes.msg)
      }
    }
  } catch (e) {
    console.warn('加载数据失败:', e)
    toast.error('加载数据失败')
  } finally {
    if (lod) loading.value = false
  }
}

// ============ 排序初始化 ============
function writeOrder(order: number[]) {
  try {
    localStorage.setItem('forward-order', JSON.stringify(order))
  } catch {
    console.warn('无法保存排序到localStorage:')
  }
}

function initForwardOrder(list: Forward[]) {
  const mine = list.filter((f) => currentUserId == null || f.userId === currentUserId)
  const hasDbOrder = mine.some((f) => f.inx !== undefined && f.inx !== 0)
  if (hasDbOrder) {
    const order = [...mine].sort((a, b) => (a.inx ?? 0) - (b.inx ?? 0)).map((f) => f.id)
    forwardOrder.value = order
    writeOrder(order)
  } else {
    let stored: number[] = []
    try {
      stored = JSON.parse(localStorage.getItem('forward-order') || '[]')
    } catch {
      stored = []
    }
    const existing = new Set(mine.map((f) => f.id))
    const kept = stored.filter((id) => existing.has(id))
    const appended = mine.map((f) => f.id).filter((id) => !kept.includes(id))
    forwardOrder.value = [...kept, ...appended]
  }
}

// ============ 过滤 + 排序 ============
function applySyncFilter(list: Forward[]): Forward[] {
  if (syncFilter.value === 'PENDING')
    return list.filter((f) => f.syncTaskStatus === 'PENDING' || f.syncTaskStatus === 'PROCESSING')
  if (syncFilter.value === 'FAILED') return list.filter((f) => f.syncTaskStatus === 'FAILED')
  if (syncFilter.value === 'SYNCED')
    return list.filter((f) => !f.syncTaskStatus && f.syncStatus === 'SYNCED')
  return list
}

function getSortedForwards(): Forward[] {
  let list = applySyncFilter(forwards.value.slice())
  if (viewMode.value === 'direct' && currentUserId != null) {
    list = list.filter((f) => f.userId === currentUserId)
  }
  list.sort((a, b) => (a.inx ?? 0) - (b.inx ?? 0))
  const allZero = list.every((f) => f.inx === undefined || f.inx === 0)
  if (allZero && forwardOrder.value.length) {
    const orderMap = new Map(forwardOrder.value.map((id, i) => [id, i]))
    list.sort((a, b) => {
      const ai = orderMap.has(a.id) ? (orderMap.get(a.id) as number) : Number.MAX_SAFE_INTEGER
      const bi = orderMap.has(b.id) ? (orderMap.get(b.id) as number) : Number.MAX_SAFE_INTEGER
      return ai - bi
    })
  }
  return list
}

const sortedForwards = computed(() => getSortedForwards())

// ============ 分组模式 ============
const userGroups = computed<UserGroup[]>(() => {
  const filtered = applySyncFilter(forwards.value.slice())
  const map = new Map<string, UserGroup>()
  for (const f of filtered) {
    const key = f.userId != null ? String(f.userId) : 'unknown'
    let ug = map.get(key)
    if (!ug) {
      ug = { userId: f.userId ?? null, userName: f.userName || '未知用户', tunnelGroups: [] }
      map.set(key, ug)
    }
    let tg = ug.tunnelGroups.find((t) => t.tunnelId === f.tunnelId)
    if (!tg) {
      tg = { tunnelId: f.tunnelId, tunnelName: f.tunnelName, forwards: [] }
      ug.tunnelGroups.push(tg)
    }
    tg.forwards.push(f)
  }
  const groups = Array.from(map.values())
  groups.sort((a, b) => a.userName.localeCompare(b.userName))
  groups.forEach((g) => g.tunnelGroups.sort((a, b) => a.tunnelName.localeCompare(b.tunnelName)))
  return groups
})

function groupTotalForwards(g: UserGroup): number {
  return g.tunnelGroups.reduce((sum, tg) => sum + tg.forwards.length, 0)
}
function runningCount(list: Forward[]): number {
  return list.filter((f) => f.serviceRunning).length
}

const isEmpty = computed(() =>
  viewMode.value === 'direct' ? sortedForwards.value.length === 0 : userGroups.value.length === 0,
)

// ============ 视图切换 ============
function toggleViewMode() {
  const next: ViewMode = viewMode.value === 'grouped' ? 'direct' : 'grouped'
  viewMode.value = next
  try {
    localStorage.setItem('forward-view-mode', next)
  } catch {
    console.warn('无法保存显示模式到localStorage:')
  }
  if (next === 'direct') initForwardOrder(forwards.value)
}

// ============ 头部筛选 ============
const pendingCount = computed(
  () =>
    forwards.value.filter(
      (f) => f.syncTaskStatus === 'PENDING' || f.syncTaskStatus === 'PROCESSING',
    ).length,
)
const failedCount = computed(
  () => forwards.value.filter((f) => f.syncTaskStatus === 'FAILED').length,
)
const filterButtons = computed(() => [
  { value: 'ALL' as SyncFilter, label: '全部', color: 'default' as TagType },
  { value: 'PENDING' as SyncFilter, label: `同步中 ${pendingCount.value}`, color: 'warning' as TagType },
  { value: 'FAILED' as SyncFilter, label: `失败 ${failedCount.value}`, color: 'error' as TagType },
  { value: 'SYNCED' as SyncFilter, label: '已同步', color: 'default' as TagType },
])

// ============ 原生拖拽排序（仅 direct） ============
const dragIndex = ref<number | null>(null)

function arrayMove<T>(arr: T[], from: number, to: number): T[] {
  const copy = arr.slice()
  const [item] = copy.splice(from, 1)
  copy.splice(to, 0, item)
  return copy
}

function onGripDragStart(index: number, e: DragEvent) {
  dragIndex.value = index
  if (e.dataTransfer) e.dataTransfer.effectAllowed = 'move'
}
function onCardDrop(index: number, e: DragEvent) {
  e.preventDefault()
  const from = dragIndex.value
  dragIndex.value = null
  if (from == null || from === index) return
  handleDragEnd(from, index)
}

async function handleDragEnd(oldIndex: number, newIndex: number) {
  const ids = sortedForwards.value.map((f) => f.id)
  const newOrder = arrayMove(ids, oldIndex, newIndex)
  forwardOrder.value = newOrder
  writeOrder(newOrder)
  try {
    const response = await updateForwardOrder({
      forwards: newOrder.map((id, index) => ({ id, inx: index })),
    })
    if (response.code === 0) {
      forwards.value = forwards.value.map((f) => {
        const idx = newOrder.indexOf(f.id)
        return idx >= 0 ? { ...f, inx: idx } : f
      })
    } else {
      toast.error('保存排序失败：' + (response.msg || '未知错误'))
    }
  } catch {
    toast.error('保存排序失败，请重试')
  }
}

// ============ 服务开关 ============
function patchForward(id: number, patch: Partial<Forward>) {
  forwards.value = forwards.value.map((f) => (f.id === id ? { ...f, ...patch } : f))
}

async function handleServiceToggle(forward: Forward) {
  if (forward.status !== 1 && forward.status !== 0) {
    toast.error('转发状态异常，无法操作')
    return
  }
  if (forward.deleteRequested) {
    toast.error('转发正在删除，无法修改服务状态')
    return
  }
  const targetState = !forward.serviceRunning
  patchForward(forward.id, { serviceRunning: targetState })
  try {
    const res = targetState
      ? await resumeForwardService(forward.id)
      : await pauseForwardService(forward.id)
    if (res.code === 0) {
      toast.success(targetState ? '恢复请求已保存，正在同步节点' : '暂停请求已保存，正在同步节点')
      patchForward(forward.id, {
        status: targetState ? 1 : 0,
        syncStatus: 'PENDING',
        syncError: undefined,
        syncTaskStatus: 'PENDING',
      })
    } else {
      patchForward(forward.id, { serviceRunning: !targetState })
      toast.error(res.msg || '操作失败')
    }
  } catch {
    patchForward(forward.id, { serviceRunning: !targetState })
    toast.error('网络错误，操作失败')
  }
}

// ============ 地址弹窗 ============
const addressModal = reactive({ show: false, title: '', addresses: [] as string[] })

function showAddressModal(value: string, port: number | null, title: string) {
  let addresses: string[]
  if (port !== null) {
    addresses = value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .map((ip) => (ip.includes(':') && !ip.startsWith('[') ? `[${ip}]:${port}` : `${ip}:${port}`))
  } else {
    addresses = value
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
  }
  if (addresses.length <= 1) {
    const single = addresses[0] || ''
    copyText(single).then((ok) => {
      if (ok) toast.success(title === '入口端口' ? '已复制入口端口' : '已复制目标地址')
      else toast.error('复制失败')
    })
    return
  }
  addressModal.title = title
  addressModal.addresses = addresses
  addressModal.show = true
}

// ============ 同步重试 ============
const retryingForwardId = ref<number | null>(null)
async function handleRetrySync(f: Forward) {
  retryingForwardId.value = f.id
  try {
    const res = await retryForwardSync(f.id)
    if (res.code === 0) {
      toast.success('节点同步任务已重新排队')
      await loadData(false, true)
    } else {
      toast.error(res.msg || '重试节点同步失败')
    }
  } catch {
    toast.error('重试节点同步失败')
  } finally {
    retryingForwardId.value = null
  }
}

// ============ 诊断 ============
const diagnosis = reactive({
  show: false,
  loading: false,
  report: null as DiagnosisReport | null,
  current: null as Forward | null,
})

async function handleDiagnose(forward: Forward) {
  diagnosis.current = forward
  diagnosis.show = true
  diagnosis.loading = true
  diagnosis.report = null
  try {
    const res = await diagnoseForward(forward.id)
    if (res.code === 0) {
      diagnosis.report = res.data as DiagnosisReport
    } else {
      toast.error(res.msg || '诊断失败')
      diagnosis.report = {
        results: [
          { success: false, description: '诊断失败', nodeName: '-', message: res.msg, category: 'HOP' },
        ],
        timestamp: Date.now(),
      }
    }
  } catch {
    toast.error('网络错误，请重试')
    diagnosis.report = {
      results: [
        {
          success: false,
          description: '网络错误',
          nodeName: '-',
          message: '无法连接到服务器',
          category: 'HOP',
        },
      ],
      timestamp: Date.now(),
    }
  } finally {
    diagnosis.loading = false
  }
}
function onDiagnoseRetry() {
  if (diagnosis.current) handleDiagnose(diagnosis.current)
}

// ============ 新增 / 编辑弹窗 ============
const showFormModal = ref(false)
const isEdit = ref(false)
const submitLoading = ref(false)
const form = reactive<ForwardForm>({
  id: undefined,
  userId: undefined,
  name: '',
  tunnelId: null,
  inPort: null,
  remoteAddr: '',
  interfaceName: '',
  strategy: 'fifo',
})
const formErrors = reactive<{ name?: string; tunnelId?: string; remoteAddr?: string; inPort?: string }>(
  {},
)

const selectedTunnel = computed(() => tunnels.value.find((t) => t.id === form.tunnelId) || null)
const remoteLineCount = computed(
  () => form.remoteAddr.split('\n').map((s) => s.trim()).filter(Boolean).length,
)
const showStrategy = computed(() => remoteLineCount.value > 1)

function clearErrors() {
  formErrors.name = undefined
  formErrors.tunnelId = undefined
  formErrors.remoteAddr = undefined
  formErrors.inPort = undefined
}

// ---------- 端口可用性实时校验 ----------
const portCheck = reactive({ status: 'idle' as PortCheckStatus, message: '' })
const portCheckRequestId = ref(0)
const lastPortWarningKey = ref('')
const showPortWarning = ref(false)
const portWarningMessage = ref('')
let portCheckTimer: number | null = null

const currentPortCheckKey = computed(() => {
  if (form.tunnelId == null || form.inPort == null) return null
  return `${form.tunnelId}:${form.inPort}:${isEdit.value ? (form.id ?? 'edit') : 'create'}`
})
const hasCustomPort = computed(() => form.inPort !== null)
const isPortSubmissionBlocked = computed(
  () => hasCustomPort.value && portCheck.status !== 'available',
)

const portDescription = computed<{ text: string; tone: 'muted' | 'checking' | 'ok' | 'err' }>(() => {
  if (portCheck.status === 'checking') return { text: '正在校验端口可用性...', tone: 'checking' }
  if (portCheck.status === 'available') return { text: portCheck.message, tone: 'ok' }
  if (portCheck.status === 'unavailable' || portCheck.status === 'error')
    return { text: portCheck.message, tone: 'err' }
  const t = selectedTunnel.value
  if (t?.inNodePortSta && t?.inNodePortEnd)
    return { text: `允许范围: ${t.inNodePortSta}-${t.inNodePortEnd}`, tone: 'muted' }
  return { text: '留空将自动分配可用端口', tone: 'muted' }
})

function resetPortCheck() {
  portCheck.status = 'idle'
  portCheck.message = ''
  lastPortWarningKey.value = ''
  showPortWarning.value = false
  portWarningMessage.value = ''
}

function maybeOpenPortWarning(key: string, message: string) {
  const warnKey = `${key}|${message}`
  if (lastPortWarningKey.value === warnKey) return
  lastPortWarningKey.value = warnKey
  portWarningMessage.value = message
  showPortWarning.value = true
}

watch([showFormModal, currentPortCheckKey], () => {
  if (portCheckTimer) {
    clearTimeout(portCheckTimer)
    portCheckTimer = null
  }
  if (!showFormModal.value) return
  const key = currentPortCheckKey.value
  if (key == null) {
    portCheck.status = 'idle'
    portCheck.message = ''
    return
  }
  const port = form.inPort
  if (port == null || !Number.isInteger(port) || port < 1 || port > 65535) {
    portCheck.status = 'unavailable'
    portCheck.message = '端口号必须是 1-65535 之间的整数'
    maybeOpenPortWarning(key, portCheck.message)
    return
  }
  portCheck.status = 'checking'
  portCheck.message = '正在校验端口可用性'
  const reqId = ++portCheckRequestId.value
  portCheckTimer = window.setTimeout(async () => {
    try {
      const res = await checkForwardPort({
        tunnelId: form.tunnelId as number,
        inPort: port,
        excludeForwardId: isEdit.value ? form.id : undefined,
      })
      if (reqId !== portCheckRequestId.value) return
      if (res.code !== 0 || !res.data) {
        portCheck.status = 'error'
        portCheck.message = res.msg || '端口校验失败，请稍后重试'
        maybeOpenPortWarning(key, portCheck.message)
      } else if (!res.data.available) {
        portCheck.status = 'unavailable'
        portCheck.message = res.data.message || '该入口端口不可用'
        maybeOpenPortWarning(key, portCheck.message)
      } else {
        portCheck.status = 'available'
        portCheck.message = res.data.message || '入口端口可用'
      }
    } catch (e) {
      if (reqId !== portCheckRequestId.value) return
      portCheck.status = 'error'
      portCheck.message = '端口校验失败，请检查网络后重试'
      console.warn('端口校验失败:', e)
      maybeOpenPortWarning(key, portCheck.message)
    }
  }, 450)
})

function openCreate() {
  isEdit.value = false
  Object.assign(form, {
    id: undefined,
    userId: undefined,
    name: '',
    tunnelId: null,
    inPort: null,
    remoteAddr: '',
    interfaceName: '',
    strategy: 'fifo',
  })
  clearErrors()
  resetPortCheck()
  showFormModal.value = true
}

function openEdit(f: Forward) {
  isEdit.value = true
  Object.assign(form, {
    id: f.id,
    userId: f.userId,
    name: f.name,
    tunnelId: f.tunnelId,
    inPort: f.inPort ?? null,
    remoteAddr: (f.remoteAddr || '').split(',').join('\n'),
    interfaceName: f.interfaceName || '',
    strategy: f.strategy || 'fifo',
  })
  clearErrors()
  resetPortCheck()
  showFormModal.value = true
}

function onTunnelChange(v: number | null) {
  form.tunnelId = v
  formErrors.tunnelId = undefined
  formErrors.inPort = undefined
}

function closeFormModal() {
  showFormModal.value = false
  showPortWarning.value = false
}

function validateForm(): boolean {
  clearErrors()
  let ok = true
  const name = (form.name || '').trim()
  if (!name) {
    formErrors.name = '请输入转发名称'
    ok = false
  } else if (name.length < 2 || name.length > 50) {
    formErrors.name = '转发名称长度应在2-50个字符之间'
    ok = false
  }
  if (!form.tunnelId) {
    formErrors.tunnelId = '请选择关联隧道'
    ok = false
  }
  const addrRaw = (form.remoteAddr || '').trim()
  if (!addrRaw) {
    formErrors.remoteAddr = '请输入远程地址'
    ok = false
  } else {
    const lines = form.remoteAddr.split('\n').map((s) => s.trim()).filter(Boolean)
    const ipv4 = /^(\d{1,3}\.){3}\d{1,3}:\d{1,5}$/
    const ipv6 = /^\[[0-9a-fA-F:]+\]:\d{1,5}$/
    const domain = /^([a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}:\d{1,5}$/
    for (let i = 0; i < lines.length; i++) {
      const l = lines[i]
      if (!ipv4.test(l) && !ipv6.test(l) && !domain.test(l)) {
        formErrors.remoteAddr = `第${i + 1}行地址格式错误`
        ok = false
        break
      }
    }
  }
  if (form.inPort !== null && (form.inPort < 1 || form.inPort > 65535)) {
    formErrors.inPort = '端口号必须在1-65535之间'
    ok = false
  }
  const t = selectedTunnel.value
  if (
    t?.inNodePortSta &&
    t?.inNodePortEnd &&
    form.inPort !== null &&
    (form.inPort < t.inNodePortSta || form.inPort > t.inNodePortEnd)
  ) {
    formErrors.inPort = `端口号必须在${t.inNodePortSta}-${t.inNodePortEnd}范围内`
    ok = false
  }
  return ok
}

async function handleSubmit() {
  if (isPortSubmissionBlocked.value) {
    const msg =
      portCheck.status === 'checking'
        ? '端口正在校验，请稍候'
        : portCheck.message || '请先选择隧道并确认自定义端口可用'
    portWarningMessage.value = msg
    showPortWarning.value = true
    return
  }
  if (!validateForm()) return

  const processedRemoteAddr = form.remoteAddr
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
    .join(',')
  const addressCount = processedRemoteAddr.split(',').filter(Boolean).length
  const strategy = addressCount > 1 ? form.strategy : 'fifo'

  submitLoading.value = true
  try {
    let res
    if (isEdit.value) {
      res = await updateForward({
        id: form.id,
        userId: form.userId,
        name: form.name.trim(),
        tunnelId: form.tunnelId,
        inPort: form.inPort,
        remoteAddr: processedRemoteAddr,
        interfaceName: form.interfaceName,
        strategy,
      })
    } else {
      res = await createForward({
        name: form.name.trim(),
        tunnelId: form.tunnelId,
        inPort: form.inPort,
        remoteAddr: processedRemoteAddr,
        interfaceName: form.interfaceName,
        strategy,
      })
    }
    if (res.code === 0) {
      toast.success(isEdit.value ? '修改已保存，正在下发到节点' : '转发已保存，正在下发到节点')
      closeFormModal()
      await loadData()
    } else {
      const msg = res.msg || '操作失败'
      if (form.inPort !== null && msg.includes('端口') && /(占用|范围|不可用|无法校验|未配置)/.test(msg)) {
        portCheck.status = 'unavailable'
        portCheck.message = msg
        portWarningMessage.value = msg
        showPortWarning.value = true
      } else {
        toast.error(msg)
      }
    }
  } catch {
    toast.error('操作失败')
  } finally {
    submitLoading.value = false
  }
}

// ============ 删除 ============
const showDelete = ref(false)
const deleting = ref(false)
const forwardToDelete = ref<Forward | null>(null)

function openDelete(f: Forward) {
  forwardToDelete.value = f
  showDelete.value = true
}

async function confirmDelete() {
  const f = forwardToDelete.value
  if (!f) return
  deleting.value = true
  try {
    const res = await deleteForward(f.id)
    if (res.code === 0) {
      toast.success('删除成功')
      showDelete.value = false
      forwardToDelete.value = null
      await loadData()
    } else {
      const confirmed = window.confirm(
        `常规删除失败：${res.msg || '删除失败'}\n\n是否需要强制删除？\n\n⚠️ 注意：强制删除不会去验证节点端是否已经删除对应的转发服务。`,
      )
      if (confirmed) {
        const forceRes = await forceDeleteForward(f.id)
        if (forceRes.code === 0) {
          toast.success('强制删除成功')
          showDelete.value = false
          forwardToDelete.value = null
          await loadData()
        } else {
          toast.error(forceRes.msg || '强制删除失败')
        }
      }
    }
  } catch {
    toast.error('删除失败')
  } finally {
    deleting.value = false
  }
}

// ============ 导出 ============
const showExport = ref(false)
const exportLoading = ref(false)
const exportTunnelId = ref<number | null>(null)
const exportData = ref('')

function openExport() {
  exportTunnelId.value = null
  exportData.value = ''
  showExport.value = true
}
function closeExport() {
  showExport.value = false
  exportTunnelId.value = null
  exportData.value = ''
}
function executeExport() {
  if (!exportTunnelId.value) {
    toast.error('请选择要导出的隧道')
    return
  }
  const list =
    viewMode.value === 'grouped'
      ? forwards.value.filter((f) => f.tunnelId === exportTunnelId.value)
      : getSortedForwards().filter((f) => f.tunnelId === exportTunnelId.value)
  if (!list.length) {
    toast.error('所选隧道没有转发数据')
    return
  }
  exportData.value = list.map((f) => `${f.remoteAddr}|${f.name}|${f.inPort}`).join('\n')
}
async function copyExport() {
  const ok = await copyText(exportData.value)
  if (ok) toast.success('已复制转发数据')
  else toast.error('复制失败')
}

// ============ 导入 ============
const showImport = ref(false)
const importLoading = ref(false)
const importTunnelId = ref<number | null>(null)
const importData = ref('')
const importResults = ref<{ success: boolean; line: string; message: string }[]>([])

const importSuccessCount = computed(() => importResults.value.filter((r) => r.success).length)

function openImport() {
  importTunnelId.value = null
  importData.value = ''
  importResults.value = []
  showImport.value = true
}
function prependResult(success: boolean, line: string, message: string) {
  importResults.value = [{ success, line, message }, ...importResults.value]
}
async function executeImport() {
  if (!importData.value.trim()) {
    toast.error('请输入要导入的数据')
    return
  }
  if (!importTunnelId.value) {
    toast.error('请选择要导入的隧道')
    return
  }
  importLoading.value = true
  importResults.value = []
  try {
    const lines = importData.value.split('\n').map((l) => l.trim()).filter(Boolean)
    const addrRe = /^[^:]+:\d+$/
    for (const line of lines) {
      const parts = line.split('|')
      if (parts.length < 2) {
        prependResult(false, line, '格式错误：需要至少包含目标地址和转发名称')
        continue
      }
      const remoteAddr = (parts[0] || '').trim()
      const name = (parts[1] || '').trim()
      const portStr = (parts[2] || '').trim()
      if (!remoteAddr || !name) {
        prependResult(false, line, '目标地址和转发名称不能为空')
        continue
      }
      const addrs = remoteAddr.split(',').map((s) => s.trim()).filter(Boolean)
      const addrOk = addrs.length > 0 && addrs.every((a) => addrRe.test(a))
      if (!addrOk) {
        prependResult(false, line, '目标地址格式错误，应为 地址:端口 格式，多个地址用逗号分隔')
        continue
      }
      let inPort: number | null = null
      if (portStr) {
        const p = parseInt(portStr, 10)
        if (isNaN(p) || p < 1 || p > 65535) {
          prependResult(false, line, '入口端口格式错误，应为1-65535之间的数字')
          continue
        }
        inPort = p
      }
      try {
        const res = await createForward({
          name,
          tunnelId: importTunnelId.value,
          inPort,
          remoteAddr,
          strategy: 'fifo',
        })
        if (res.code === 0) prependResult(true, line, '创建成功')
        else prependResult(false, line, res.msg || '创建失败')
      } catch {
        prependResult(false, line, '网络错误，创建失败')
      }
    }
    toast.success('导入执行完成')
    await loadData(false)
  } catch {
    toast.error('导入过程中发生错误')
  } finally {
    importLoading.value = false
  }
}

// ============ 自动刷新轮询 ============
watch(
  forwards,
  (list) => {
    if (pollTimer) {
      clearTimeout(pollTimer)
      pollTimer = null
    }
    if (list.some((f) => f.syncTaskStatus)) {
      pollTimer = window.setTimeout(() => loadData(false, true), 3000)
    }
  },
  { deep: false },
)

onMounted(() => loadData())
onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
  if (portCheckTimer) clearTimeout(portCheckTimer)
})
</script>

<template>
  <PageContainer :loading="loading">
    <!-- 头部 -->
    <div class="fwd-header">
      <div class="fwd-title-row">
        <div>
          <h1 class="fwd-title">转发管理</h1>
          <p class="fwd-subtitle">管理与同步各隧道下的转发配置</p>
        </div>
        <div class="fwd-actions">
          <NButton
            quaternary
            circle
            :title="viewMode === 'grouped' ? '切换到直接显示' : '切换到分类显示'"
            @click="toggleViewMode"
          >
            <svg
              v-if="viewMode === 'grouped'"
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <rect x="3" y="3" width="7" height="7" rx="1.5" />
              <rect x="14" y="3" width="7" height="7" rx="1.5" />
              <rect x="3" y="14" width="7" height="7" rx="1.5" />
              <rect x="14" y="14" width="7" height="7" rx="1.5" />
            </svg>
            <svg
              v-else
              width="18"
              height="18"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <line x1="4" y1="6" x2="20" y2="6" />
              <line x1="4" y1="12" x2="20" y2="12" />
              <line x1="4" y1="18" x2="20" y2="18" />
            </svg>
          </NButton>
          <NButton type="warning" @click="openImport">导入</NButton>
          <NButton type="success" :loading="exportLoading" @click="openExport">导出</NButton>
          <NButton type="primary" @click="openCreate">新增</NButton>
        </div>
      </div>

      <div class="fwd-filter-bar">
        <span class="fwd-filter-label">节点同步</span>
        <NButton
          v-for="btn in filterButtons"
          :key="btn.value"
          size="small"
          :type="btn.color"
          :secondary="syncFilter !== btn.value"
          @click="syncFilter = btn.value"
        >
          {{ btn.label }}
        </NButton>
      </div>
    </div>

    <!-- 空状态 -->
    <EmptyState
      v-if="isEmpty"
      title="暂无转发配置"
      description="还没有创建任何转发配置，点击上方按钮开始创建"
    >
      <template #icon>
        <svg
          width="30"
          height="30"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.7"
          stroke-linecap="round"
          stroke-linejoin="round"
        >
          <polyline points="17 11 12 6 7 11" />
          <polyline points="7 13 12 18 17 13" />
        </svg>
      </template>
    </EmptyState>

    <!-- 直接显示模式 -->
    <div v-else-if="viewMode === 'direct'" class="fx-grid">
      <div
        v-for="(f, idx) in sortedForwards"
        :key="f.id"
        class="fx-card fwd-card"
        :class="{ 'fwd-card-dragging': dragIndex === idx }"
        @dragover.prevent
        @drop="onCardDrop(idx, $event)"
      >
        <!-- 头部 -->
        <div class="fwd-card-head">
          <div class="fwd-card-title">
            <span
              class="fwd-grip"
              draggable="true"
              title="拖拽排序"
              @dragstart="onGripDragStart(idx, $event)"
              @dragend="dragIndex = null"
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                <circle cx="9" cy="6" r="1.6" />
                <circle cx="15" cy="6" r="1.6" />
                <circle cx="9" cy="12" r="1.6" />
                <circle cx="15" cy="12" r="1.6" />
                <circle cx="9" cy="18" r="1.6" />
                <circle cx="15" cy="18" r="1.6" />
              </svg>
            </span>
            <div class="fwd-name-wrap">
              <span class="fwd-name">{{ f.name }}</span>
              <span class="fwd-tunnel">{{ f.tunnelName }}</span>
            </div>
          </div>
          <div class="fwd-head-right">
            <NSwitch
              size="small"
              :value="f.serviceRunning"
              :disabled="f.deleteRequested || (f.status !== 1 && f.status !== 0)"
              @update:value="() => handleServiceToggle(f)"
            />
            <div class="fwd-chips">
              <NTag size="small" :type="getStatusDisplay(f.status).type" :bordered="false">
                {{ getStatusDisplay(f.status).text }}
              </NTag>
              <NTag size="small" round :type="getSyncDisplay(f).type" :bordered="false">
                {{ getSyncDisplay(f).text }}
              </NTag>
            </div>
          </div>
        </div>

        <!-- 地址 -->
        <div class="fwd-addr-block">
          <div class="fwd-addr-row" @click="showAddressModal(f.inIp, f.inPort, '入口端口')">
            <span class="fwd-addr-label">入口</span>
            <span class="fwd-addr-val fx-mono">{{ formatInAddress(f.inIp, f.inPort) }}</span>
            <svg
              v-if="hasMultiple(f.inIp)"
              class="fwd-copy-glyph"
              width="13"
              height="13"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
            >
              <rect x="9" y="9" width="11" height="11" rx="2" />
              <path d="M5 15V5a2 2 0 0 1 2-2h10" />
            </svg>
          </div>
          <div class="fwd-addr-row" @click="showAddressModal(f.remoteAddr, null, '目标地址')">
            <span class="fwd-addr-label">目标</span>
            <span class="fwd-addr-val fx-mono">{{ formatRemoteAddress(f.remoteAddr) }}</span>
            <svg
              v-if="hasMultiple(f.remoteAddr)"
              class="fwd-copy-glyph"
              width="13"
              height="13"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="1.8"
            >
              <rect x="9" y="9" width="11" height="11" rx="2" />
              <path d="M5 15V5a2 2 0 0 1 2-2h10" />
            </svg>
          </div>
        </div>

        <!-- 同步提示 -->
        <NAlert
          v-if="showSyncAlert(f)"
          class="fwd-sync-alert"
          size="small"
          :bordered="false"
          :type="f.syncTaskStatus === 'FAILED' ? 'error' : 'warning'"
          :title="getSyncDisplay(f).text"
        >
          <div class="fwd-sync-body">
            <div class="fwd-sync-text">
              <div>{{ getSyncDisplay(f).description }}</div>
              <div v-if="(f.syncAttempts ?? 0) > 0" class="fwd-sync-sub">
                已尝试 {{ f.syncAttempts }} 次
              </div>
              <div v-if="f.syncTaskStatus === 'FAILED' && f.syncNextAttemptAt" class="fwd-sync-sub">
                下次自动重试：{{ new Date(f.syncNextAttemptAt).toLocaleString('zh-CN') }}
              </div>
            </div>
            <NButton
              v-if="f.syncTaskStatus === 'FAILED'"
              size="tiny"
              type="error"
              :loading="retryingForwardId === f.id"
              @click="handleRetrySync(f)"
            >
              立即重试
            </NButton>
          </div>
        </NAlert>

        <!-- 统计 -->
        <div class="fwd-stats">
          <NTag size="small" :type="getStrategyDisplay(f.strategy).type" :bordered="false">
            {{ getStrategyDisplay(f.strategy).text }}
          </NTag>
          <NTag size="small" type="primary" :bordered="false">↑{{ formatFlow(f.inFlow) }}</NTag>
          <NTag size="small" type="success" :bordered="false">↓{{ formatFlow(f.outFlow) }}</NTag>
        </div>

        <!-- 操作 -->
        <div class="fwd-card-actions">
          <NButton size="small" type="primary" secondary :disabled="f.deleteRequested" @click="openEdit(f)">
            编辑
          </NButton>
          <NButton size="small" type="warning" secondary :disabled="f.deleteRequested" @click="handleDiagnose(f)">
            诊断
          </NButton>
          <NButton size="small" type="error" secondary :disabled="f.deleteRequested" @click="openDelete(f)">
            删除
          </NButton>
        </div>
      </div>
    </div>

    <!-- 分类显示模式 -->
    <div v-else class="fwd-groups">
      <div v-for="g in userGroups" :key="g.userId ?? 'unknown'" class="fx-card fwd-user-card">
        <div class="fwd-user-head">
          <div class="fwd-user-avatar">
            {{ (g.userName || '?').slice(0, 1).toUpperCase() }}
          </div>
          <div class="fwd-user-meta">
            <h2 class="fwd-user-name">{{ g.userName }}</h2>
            <span class="fwd-user-sub">
              {{ g.tunnelGroups.length }} 个隧道，{{ groupTotalForwards(g) }} 个转发
            </span>
          </div>
          <NTag size="small" type="primary" :bordered="false">用户</NTag>
        </div>

        <NCollapse :default-expanded-names="g.tunnelGroups.map((t) => String(t.tunnelId))">
          <NCollapseItem
            v-for="tg in g.tunnelGroups"
            :key="tg.tunnelId"
            :name="String(tg.tunnelId)"
          >
            <template #header>
              <div class="fwd-tg-header">
                <span class="fwd-tg-icon">
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M13 2 3 14h7l-1 8 10-12h-7z" />
                  </svg>
                </span>
                <span class="fwd-tg-name">{{ tg.tunnelName }}</span>
                <NTag size="tiny" :bordered="false" type="info">
                  {{ runningCount(tg.forwards) }}/{{ tg.forwards.length }}
                </NTag>
              </div>
            </template>

            <div class="fx-grid">
              <div v-for="f in tg.forwards" :key="f.id" class="fx-card fwd-card">
                <div class="fwd-card-head">
                  <div class="fwd-card-title">
                    <div class="fwd-name-wrap">
                      <span class="fwd-name">{{ f.name }}</span>
                      <span class="fwd-tunnel">{{ f.tunnelName }}</span>
                    </div>
                  </div>
                  <div class="fwd-head-right">
                    <NSwitch
                      size="small"
                      :value="f.serviceRunning"
                      :disabled="f.deleteRequested || (f.status !== 1 && f.status !== 0)"
                      @update:value="() => handleServiceToggle(f)"
                    />
                    <div class="fwd-chips">
                      <NTag size="small" :type="getStatusDisplay(f.status).type" :bordered="false">
                        {{ getStatusDisplay(f.status).text }}
                      </NTag>
                      <NTag size="small" round :type="getSyncDisplay(f).type" :bordered="false">
                        {{ getSyncDisplay(f).text }}
                      </NTag>
                    </div>
                  </div>
                </div>

                <div class="fwd-addr-block">
                  <div class="fwd-addr-row" @click="showAddressModal(f.inIp, f.inPort, '入口端口')">
                    <span class="fwd-addr-label">入口</span>
                    <span class="fwd-addr-val fx-mono">{{ formatInAddress(f.inIp, f.inPort) }}</span>
                    <svg
                      v-if="hasMultiple(f.inIp)"
                      class="fwd-copy-glyph"
                      width="13"
                      height="13"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.8"
                    >
                      <rect x="9" y="9" width="11" height="11" rx="2" />
                      <path d="M5 15V5a2 2 0 0 1 2-2h10" />
                    </svg>
                  </div>
                  <div class="fwd-addr-row" @click="showAddressModal(f.remoteAddr, null, '目标地址')">
                    <span class="fwd-addr-label">目标</span>
                    <span class="fwd-addr-val fx-mono">{{ formatRemoteAddress(f.remoteAddr) }}</span>
                    <svg
                      v-if="hasMultiple(f.remoteAddr)"
                      class="fwd-copy-glyph"
                      width="13"
                      height="13"
                      viewBox="0 0 24 24"
                      fill="none"
                      stroke="currentColor"
                      stroke-width="1.8"
                    >
                      <rect x="9" y="9" width="11" height="11" rx="2" />
                      <path d="M5 15V5a2 2 0 0 1 2-2h10" />
                    </svg>
                  </div>
                </div>

                <NAlert
                  v-if="showSyncAlert(f)"
                  class="fwd-sync-alert"
                  size="small"
                  :bordered="false"
                  :type="f.syncTaskStatus === 'FAILED' ? 'error' : 'warning'"
                  :title="getSyncDisplay(f).text"
                >
                  <div class="fwd-sync-body">
                    <div class="fwd-sync-text">
                      <div>{{ getSyncDisplay(f).description }}</div>
                      <div v-if="(f.syncAttempts ?? 0) > 0" class="fwd-sync-sub">
                        已尝试 {{ f.syncAttempts }} 次
                      </div>
                      <div
                        v-if="f.syncTaskStatus === 'FAILED' && f.syncNextAttemptAt"
                        class="fwd-sync-sub"
                      >
                        下次自动重试：{{ new Date(f.syncNextAttemptAt).toLocaleString('zh-CN') }}
                      </div>
                    </div>
                    <NButton
                      v-if="f.syncTaskStatus === 'FAILED'"
                      size="tiny"
                      type="error"
                      :loading="retryingForwardId === f.id"
                      @click="handleRetrySync(f)"
                    >
                      立即重试
                    </NButton>
                  </div>
                </NAlert>

                <div class="fwd-stats">
                  <NTag size="small" :type="getStrategyDisplay(f.strategy).type" :bordered="false">
                    {{ getStrategyDisplay(f.strategy).text }}
                  </NTag>
                  <NTag size="small" type="primary" :bordered="false">↑{{ formatFlow(f.inFlow) }}</NTag>
                  <NTag size="small" type="success" :bordered="false">↓{{ formatFlow(f.outFlow) }}</NTag>
                </div>

                <div class="fwd-card-actions">
                  <NButton
                    size="small"
                    type="primary"
                    secondary
                    :disabled="f.deleteRequested"
                    @click="openEdit(f)"
                  >
                    编辑
                  </NButton>
                  <NButton
                    size="small"
                    type="warning"
                    secondary
                    :disabled="f.deleteRequested"
                    @click="handleDiagnose(f)"
                  >
                    诊断
                  </NButton>
                  <NButton
                    size="small"
                    type="error"
                    secondary
                    :disabled="f.deleteRequested"
                    @click="openDelete(f)"
                  >
                    删除
                  </NButton>
                </div>
              </div>
            </div>
          </NCollapseItem>
        </NCollapse>
      </div>
    </div>

    <!-- 新增 / 编辑弹窗 -->
    <NModal
      :show="showFormModal"
      preset="card"
      :title="isEdit ? '编辑转发' : '新增转发'"
      style="width: 640px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (v ? (showFormModal = true) : closeFormModal())"
    >
      <div class="fwd-modal-sub">{{ isEdit ? '修改现有转发配置的信息' : '创建新的转发配置' }}</div>

      <div class="form-grid">
        <div class="form-item">
          <label class="form-label">转发名称</label>
          <NInput v-model:value="form.name" placeholder="请输入转发名称" />
          <span v-if="formErrors.name" class="form-err">{{ formErrors.name }}</span>
        </div>

        <div class="form-item">
          <label class="form-label">选择隧道</label>
          <NSelect
            :value="form.tunnelId"
            :options="tunnelOptions"
            placeholder="请选择关联的隧道"
            @update:value="onTunnelChange"
          />
          <span v-if="formErrors.tunnelId" class="form-err">{{ formErrors.tunnelId }}</span>
        </div>

        <div class="form-item">
          <label class="form-label">入口端口</label>
          <NInputNumber
            v-model:value="form.inPort"
            :min="1"
            :max="65535"
            :step="1"
            placeholder="留空自动分配"
            clearable
            style="width: 100%"
          />
          <span
            class="form-desc"
            :class="{
              'desc-ok': portDescription.tone === 'ok',
              'desc-err': portDescription.tone === 'err',
              'desc-checking': portDescription.tone === 'checking',
            }"
          >
            {{ portDescription.text }}
          </span>
          <span v-if="formErrors.inPort" class="form-err">{{ formErrors.inPort }}</span>
        </div>

        <div class="form-item">
          <label class="form-label">远程地址</label>
          <NInput
            v-model:value="form.remoteAddr"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 6 }"
            :placeholder="REMOTE_PLACEHOLDER"
          />
          <span class="form-desc">格式: IP:端口 或 域名:端口，支持多个地址（每行一个）</span>
          <span v-if="formErrors.remoteAddr" class="form-err">{{ formErrors.remoteAddr }}</span>
        </div>

        <div class="form-item">
          <label class="form-label">出口网卡名或IP</label>
          <NInput v-model:value="form.interfaceName" placeholder="请输入出口网卡名或IP" />
          <span class="form-desc">用于多IP服务器指定使用那个IP请求远程地址，不懂的默认为空就行</span>
        </div>

        <div v-if="showStrategy" class="form-item">
          <label class="form-label">负载策略</label>
          <NSelect
            v-model:value="form.strategy"
            :options="strategyOptions"
            placeholder="请选择负载均衡策略"
          />
          <span class="form-desc">多个目标地址的负载均衡策略</span>
        </div>
      </div>

      <template #footer>
        <div class="modal-footer">
          <NButton @click="closeFormModal">取消</NButton>
          <NButton
            type="primary"
            :loading="submitLoading"
            :disabled="isPortSubmissionBlocked"
            @click="handleSubmit"
          >
            {{ isEdit ? '保存修改' : '创建转发' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 端口不可用提示 -->
    <NModal
      :show="showPortWarning"
      preset="card"
      style="width: 420px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showPortWarning = v)"
    >
      <template #header>
        <span class="delete-title">入口端口不可用</span>
      </template>
      <NAlert type="error" :bordered="false" title="无法使用当前端口">
        {{ portWarningMessage }}
      </NAlert>
      <template #footer>
        <div class="modal-footer">
          <NButton type="primary" @click="showPortWarning = false">修改端口</NButton>
        </div>
      </template>
    </NModal>

    <!-- 删除确认 -->
    <NModal
      :show="showDelete"
      preset="card"
      style="width: 460px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showDelete = v)"
    >
      <template #header>
        <span class="delete-title">确认删除</span>
      </template>
      <div class="delete-body">
        <p>确定要删除转发 "{{ forwardToDelete?.name }}" 吗？</p>
        <p class="text-secondary">此操作无法撤销，删除后该转发将永久消失。</p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showDelete = false">取消</NButton>
          <NButton type="error" :loading="deleting" @click="confirmDelete">确认删除</NButton>
        </div>
      </template>
    </NModal>

    <!-- 导出弹窗 -->
    <NModal
      :show="showExport"
      preset="card"
      title="导出转发数据"
      style="width: 560px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (v ? (showExport = true) : closeExport())"
    >
      <div class="fwd-modal-sub">格式：目标地址|转发名称|入口端口</div>
      <div class="form-grid">
        <div class="form-item">
          <label class="form-label">选择导出隧道</label>
          <NSelect
            v-model:value="exportTunnelId"
            :options="tunnelOptions"
            placeholder="请选择要导出的隧道"
          />
        </div>

        <div v-if="!exportData" class="modal-footer">
          <NButton type="primary" :disabled="!exportTunnelId" @click="executeExport">
            生成导出数据
          </NButton>
        </div>
        <template v-else>
          <div class="fwd-export-actions">
            <NButton type="primary" @click="executeExport">重新生成</NButton>
            <NButton type="info" secondary @click="copyExport">复制</NButton>
          </div>
          <NInput
            :value="exportData"
            type="textarea"
            readonly
            class="fx-mono"
            :autosize="{ minRows: 10, maxRows: 20 }"
            placeholder="暂无数据"
          />
        </template>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="closeExport">关闭</NButton>
        </div>
      </template>
    </NModal>

    <!-- 导入弹窗 -->
    <NModal
      :show="showImport"
      preset="card"
      title="导入转发数据"
      style="width: 620px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showImport = v)"
    >
      <div class="fwd-modal-sub">
        格式：目标地址|转发名称|入口端口，每行一个，入口端口留空将自动分配可用端口
      </div>
      <div class="fwd-modal-sub">
        目标地址支持单个地址(如：example.com:8080)或多个地址用逗号分隔(如：3.3.3.3:3,4.4.4.4:4)
      </div>

      <div class="form-grid">
        <div class="form-item">
          <label class="form-label">选择导入隧道</label>
          <NSelect
            v-model:value="importTunnelId"
            :options="tunnelOptions"
            placeholder="请选择要导入的隧道"
          />
        </div>

        <div class="form-item">
          <label class="form-label">导入数据</label>
          <NInput
            v-model:value="importData"
            type="textarea"
            class="fx-mono"
            :autosize="{ minRows: 8, maxRows: 12 }"
            placeholder="请输入要导入的转发数据，格式：目标地址|转发名称|入口端口"
          />
        </div>

        <div v-if="importResults.length" class="fwd-import-results">
          <div class="fwd-import-results-head">
            <span>导入结果</span>
            <span class="text-secondary">
              成功：{{ importSuccessCount }} / 总计：{{ importResults.length }}
            </span>
          </div>
          <div class="fwd-import-results-body">
            <div
              v-for="(r, i) in importResults"
              :key="i"
              class="fwd-import-row"
              :class="r.success ? 'row-ok' : 'row-err'"
            >
              <span class="fwd-import-badge">{{ r.success ? '✓' : '✕' }}</span>
              <span class="fwd-import-label">{{ r.success ? '成功' : '失败' }}</span>
              <span class="fwd-import-sep">|</span>
              <code class="fwd-import-line fx-mono">{{ r.line }}</code>
              <span class="fwd-import-msg">{{ r.message }}</span>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <div class="modal-footer">
          <NButton @click="showImport = false">关闭</NButton>
          <NButton
            type="warning"
            :loading="importLoading"
            :disabled="!importData.trim() || !importTunnelId"
            @click="executeImport"
          >
            开始导入
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 地址弹窗 -->
    <AddressModal
      v-model:show="addressModal.show"
      :title="addressModal.title"
      :addresses="addressModal.addresses"
    />

    <!-- 诊断弹窗 -->
    <DiagnosisDialog
      v-model:show="diagnosis.show"
      :loading="diagnosis.loading"
      :report="diagnosis.report"
      title="转发诊断结果"
      :subtitle="diagnosis.current?.name"
      type-label="转发服务"
      @retry="onDiagnoseRetry"
    />
  </PageContainer>
</template>

<style scoped>
/* 头部 */
.fwd-header {
  display: flex;
  flex-direction: column;
  gap: 14px;
  margin-bottom: 20px;
}
.fwd-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.fwd-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.fwd-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 4px 0 0;
}
.fwd-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.fwd-filter-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 12px;
  background: var(--bg-subtle);
  border-radius: var(--radius-md);
}
.fwd-filter-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-secondary);
  margin-right: 4px;
}

/* 卡片 */
.fwd-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
}
.fwd-card-dragging {
  opacity: 0.5;
}
.fwd-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.fwd-card-title {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  min-width: 0;
}
.fwd-grip {
  display: inline-flex;
  align-items: center;
  color: var(--text-secondary);
  cursor: grab;
  touch-action: none;
  margin-top: 2px;
  opacity: 0.55;
  transition: opacity 0.15s ease;
}
.fwd-grip:hover {
  opacity: 1;
}
.fwd-grip:active {
  cursor: grabbing;
}
.fwd-name-wrap {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.fwd-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fwd-tunnel {
  font-size: 12px;
  color: var(--text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fwd-head-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
.fwd-chips {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-end;
}

/* 地址 */
.fwd-addr-block {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.fwd-addr-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-soft);
  border-radius: 10px;
  cursor: pointer;
  transition: border-color 0.15s ease;
}
.fwd-addr-row:hover {
  border-color: var(--brand-400);
}
.fwd-addr-label {
  font-size: 12px;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.fwd-addr-val {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}
.fwd-copy-glyph {
  color: var(--text-secondary);
  flex-shrink: 0;
}

/* 同步提示 */
.fwd-sync-alert :deep(.n-alert__content) {
  font-size: 12px;
}
.fwd-sync-body {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.fwd-sync-text {
  min-width: 0;
  flex: 1;
}
.fwd-sync-sub {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 2px;
}

/* 统计 */
.fwd-stats {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

/* 操作 */
.fwd-card-actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}
.fwd-card-actions .n-button {
  flex: 1;
}

/* 分组模式 */
.fwd-groups {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.fwd-user-card {
  padding: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
}
.fwd-user-head {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.fwd-user-avatar {
  width: 40px;
  height: 40px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  flex-shrink: 0;
}
.fwd-user-meta {
  flex: 1;
  min-width: 0;
}
.fwd-user-name {
  font-size: 16px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 60vw;
}
.fwd-user-sub {
  font-size: 12px;
  color: var(--text-secondary);
}
.fwd-tg-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.fwd-tg-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 7px;
  color: var(--warn);
  background: color-mix(in srgb, var(--warn) 16%, transparent);
}
.fwd-tg-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--text-primary);
}

/* 表单 */
.fwd-modal-sub {
  font-size: 13px;
  color: var(--text-secondary);
  margin: -6px 0 14px;
}
.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}
.form-desc {
  font-size: 12px;
  color: var(--text-secondary);
  white-space: pre-line;
}
.form-desc.desc-ok {
  color: var(--ok);
}
.form-desc.desc-err {
  color: var(--danger);
}
.form-desc.desc-checking {
  color: var(--warn);
}
.form-err {
  font-size: 12px;
  color: var(--danger);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.delete-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--danger);
}
.delete-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
  color: var(--text-primary);
}
.delete-body p {
  margin: 0;
}

/* 导出 */
.fwd-export-actions {
  display: flex;
  gap: 10px;
}

/* 导入结果 */
.fwd-import-results {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fwd-import-results-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}
.fwd-import-results-body {
  display: flex;
  flex-direction: column;
  gap: 6px;
  max-height: 160px;
  overflow-y: auto;
}
.fwd-import-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 8px;
  font-size: 12px;
  flex-wrap: wrap;
}
.fwd-import-row.row-ok {
  background: color-mix(in srgb, var(--ok) 12%, transparent);
  color: var(--text-primary);
}
.fwd-import-row.row-err {
  background: color-mix(in srgb, var(--danger) 12%, transparent);
  color: var(--text-primary);
}
.fwd-import-badge {
  font-weight: 700;
}
.row-ok .fwd-import-badge {
  color: var(--ok);
}
.row-err .fwd-import-badge {
  color: var(--danger);
}
.fwd-import-label {
  font-weight: 600;
}
.fwd-import-sep {
  color: var(--text-secondary);
}
.fwd-import-line {
  color: var(--text-secondary);
}
.fwd-import-msg {
  color: var(--text-secondary);
}

@media (max-width: 768px) {
  .fwd-title-row {
    flex-direction: column;
    align-items: flex-start;
  }
  .fwd-actions {
    width: 100%;
  }
  .fwd-grip {
    opacity: 1;
  }
  .fwd-user-name {
    max-width: 55vw;
  }
}
</style>
