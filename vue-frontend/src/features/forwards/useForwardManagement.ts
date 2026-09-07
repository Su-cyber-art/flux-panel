import { ref, reactive, computed, watch, onMounted, onUnmounted } from 'vue'
import { useToast } from '@/composables/useToast'
import { JwtUtil } from '@/utils/jwt'
import { copyText } from '@/utils/clipboard'
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


export function useForwardManagement() {
  const toast = useToast()
  const currentUserId = JwtUtil.getUserIdFromToken()

  // ============ 基础状态 ============
  const loading = ref(true)
  const refreshing = ref(false)
  const loadError = ref<string | null>(null)
  const togglingIds = reactive(new Set<number>())
  let disposed = false
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
    if (disposed) return
    if (lod) loading.value = true
    refreshing.value = true
    const requestId = ++forwardListRequestId.value
    try {
      const [forwardsRes, tunnelsRes] = await Promise.all([
        getForwardList(), forwardsOnly ? Promise.resolve(null) : userTunnel(),
      ])
      if (disposed || requestId !== forwardListRequestId.value) return
      if (forwardsRes.code === 0) {
        loadError.value = null
        forwards.value = (forwardsRes.data || []).map((f: Forward) => ({ ...f, serviceRunning: f.status === 1 }))
        if (viewMode.value === 'direct') initForwardOrder(forwards.value)
      } else {
        loadError.value = forwardsRes.msg || '获取转发列表失败'
        if (lod) toast.error(loadError.value)
      }
      if (tunnelsRes?.code === 0) tunnels.value = tunnelsRes.data || []
    } catch {
      if (!disposed && requestId === forwardListRequestId.value) {
        loadError.value = '暂时无法更新转发列表'
        if (lod) toast.error(loadError.value)
      }
    } finally {
      if (!disposed && requestId === forwardListRequestId.value) {
        loading.value = false
        refreshing.value = false
        schedulePoll()
      }
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
    if (togglingIds.has(forward.id)) return
    if (forward.status !== 1 && forward.status !== 0) {
      toast.error('转发状态异常，无法操作')
      return
    }
    if (forward.deleteRequested) {
      toast.error('转发正在删除，无法修改服务状态')
      return
    }
    togglingIds.add(forward.id)
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
    } finally {
      togglingIds.delete(forward.id)
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
  }

  watch([showFormModal, currentPortCheckKey], () => {
    const reqId = ++portCheckRequestId.value
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
  const showForceDelete = ref(false)
  const forceDeleteReason = ref('')

  function openDelete(f: Forward) {
    forwardToDelete.value = f
    showDelete.value = true
  }

  async function confirmDelete() {
    const f = forwardToDelete.value
    if (!f || deleting.value) return
    deleting.value = true
    try {
      const res = await deleteForward(f.id)
      if (res.code === 0) {
        toast.success('删除请求已保存，等待节点同步')
        showDelete.value = false
        forwardToDelete.value = null
        await loadData(false)
      } else {
        showDelete.value = false
        forceDeleteReason.value = res.msg || '节点配置删除失败'
        showForceDelete.value = true
      }
    } finally { deleting.value = false }
  }

  function requestForceDelete(f: Forward) {
    forwardToDelete.value = f
    forceDeleteReason.value = f.syncError || '该转发仍在等待节点删除配置'
    showForceDelete.value = true
  }

  async function confirmForceDelete() {
    const f = forwardToDelete.value
    if (!f || deleting.value) return
    deleting.value = true
    try {
      const res = await forceDeleteForward(f.id)
      if (res.code === 0) {
        toast.success('转发记录已强制删除')
        showForceDelete.value = false
        forwardToDelete.value = null
        await loadData(false)
      } else toast.error(res.msg || '强制删除失败')
    } finally { deleting.value = false }
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

  function schedulePoll() {
    if (pollTimer) clearTimeout(pollTimer)
    pollTimer = null
    if (!disposed && forwards.value.some((f) => f.syncTaskStatus)) {
      pollTimer = window.setTimeout(() => loadData(false, true), 3000)
    }
  }
  watch(forwards, schedulePoll)
  onMounted(() => loadData())
  onUnmounted(() => {
    disposed = true
    forwardListRequestId.value++
    portCheckRequestId.value++
    if (pollTimer) clearTimeout(pollTimer)
    if (portCheckTimer) clearTimeout(portCheckTimer)
  })

  return {
    currentUserId,
    loading,
    refreshing,
    loadError,
    togglingIds,
    forwards,
    tunnels,
    syncFilter,
    viewMode,
    strategyOptions,
    getStatusDisplay,
    getSyncDisplay,
    getStrategyDisplay,
    loadData,
    sortedForwards,
    userGroups,
    toggleViewMode,
    filterButtons,
    dragIndex,
    onGripDragStart,
    onCardDrop,
    handleDragEnd,
    handleServiceToggle,
    addressModal,
    showAddressModal,
    retryingForwardId,
    handleRetrySync,
    diagnosis,
    handleDiagnose,
    onDiagnoseRetry,
    showFormModal,
    isEdit,
    submitLoading,
    form,
    formErrors,
    showStrategy,
    showPortWarning,
    portWarningMessage,
    isPortSubmissionBlocked,
    portDescription,
    openCreate,
    openEdit,
    onTunnelChange,
    closeFormModal,
    handleSubmit,
    showDelete,
    deleting,
    forwardToDelete,
    showForceDelete,
    forceDeleteReason,
    openDelete,
    confirmDelete,
    requestForceDelete,
    confirmForceDelete,
    showExport,
    exportTunnelId,
    exportData,
    openExport,
    closeExport,
    executeExport,
    copyExport,
    showImport,
    importLoading,
    importTunnelId,
    importData,
    importResults,
    importSuccessCount,
    openImport,
    executeImport,
  }
}

export type ForwardController = ReturnType<typeof useForwardManagement>
