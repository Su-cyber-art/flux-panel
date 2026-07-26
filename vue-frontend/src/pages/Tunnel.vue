<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import {
  NButton,
  NModal,
  NInput,
  NInputNumber,
  NSelect,
  NTag,
  NDivider,
  NAlert,
  NIcon,
} from 'naive-ui'
import {
  AddOutline,
  CreateOutline,
  PulseOutline,
  TrashOutline,
  ChevronUpOutline,
  ChevronDownOutline,
  CloseOutline,
  ArrowDownOutline,
  WifiOutline,
} from '@vicons/ionicons5'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import DiagnosisDialog from '@/components/DiagnosisDialog.vue'
import { useToast } from '@/composables/useToast'
import {
  getTunnelList,
  getNodeList,
  createTunnel,
  updateTunnel,
  deleteTunnel,
  diagnoseTunnel,
} from '@/api'
import type { Tunnel, TunnelForm, DiagnosisReport } from '@/types'

interface Node {
  id: number
  name: string
  status: number // 1 在线 0 离线
}

const toast = useToast()

const loading = ref(true)
const tunnels = ref<Tunnel[]>([])
const nodes = ref<Node[]>([])

// ============ 归一化辅助 ============
function normalizeNodeId(value: unknown): number | null {
  const candidate = Array.isArray(value) ? value[0] : value
  if (candidate === null || candidate === undefined || candidate === '') {
    return null
  }
  const nodeId = Number(candidate)
  return Number.isSafeInteger(nodeId) && nodeId > 0 ? nodeId : null
}

function normalizeTunnelForm(f: TunnelForm): TunnelForm {
  return {
    ...f,
    inNodeId: normalizeNodeId(f.inNodeId),
    outNodeId: f.type === 2 ? normalizeNodeId(f.outNodeId) : null,
    chainNodeIds:
      f.type === 2
        ? f.chainNodeIds.map(normalizeNodeId).filter((id): id is number => id !== null)
        : [],
  }
}

function defaultForm(): TunnelForm {
  return {
    name: '',
    type: 1,
    inNodeId: null,
    outNodeId: null,
    chainNodeIds: [],
    protocol: 'tls',
    tcpListenAddr: '[::]',
    udpListenAddr: '[::]',
    interfaceName: '',
    flow: 1,
    trafficRatio: 1.0,
    status: 1,
  }
}

// ============ 加载 ============
async function loadData() {
  loading.value = true
  try {
    const [tunnelsRes, nodesRes] = await Promise.all([getTunnelList(), getNodeList()])
    if (tunnelsRes.code === 0) {
      tunnels.value = tunnelsRes.data || []
    } else {
      toast.error(tunnelsRes.msg || '获取隧道列表失败')
    }
    if (nodesRes.code === 0) {
      nodes.value = nodesRes.data || []
    } else {
      console.warn('获取节点列表失败:', nodesRes.msg)
    }
  } catch (error) {
    console.error('加载数据失败:', error)
    toast.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

// ============ 显示辅助 ============
function getTypeDisplay(type: number): { text: string; color: 'primary' | 'default' } {
  switch (type) {
    case 1:
      return { text: '端口转发', color: 'primary' }
    case 2:
      // secondary → naive 无 secondary，使用 default
      return { text: '隧道转发', color: 'default' }
    default:
      return { text: '未知', color: 'default' }
  }
}

function getStatusDisplay(
  status: number,
): { text: string; color: 'success' | 'default' | 'warning' } {
  switch (status) {
    case 1:
      return { text: '启用', color: 'success' }
    case 0:
      return { text: '禁用', color: 'default' }
    default:
      return { text: '未知', color: 'warning' }
  }
}

function getFlowDisplay(flow: number): string {
  switch (flow) {
    case 1:
      return '单向计算'
    case 2:
      return '双向计算'
    default:
      return '未知'
  }
}

function getDisplayIp(ipString?: string): string {
  if (!ipString) return '-'
  const ips = ipString
    .split(',')
    .map((ip) => ip.trim())
    .filter((ip) => ip)
  if (ips.length === 0) return '-'
  if (ips.length === 1) return ips[0]
  return `${ips[0]} 等${ips.length}个`
}

function getNodeName(nodeId?: number): string {
  if (!nodeId) return '-'
  const node = nodes.value.find((n) => n.id === nodeId)
  return node ? node.name : `节点${nodeId}`
}

// ============ 表单 / 弹窗 ============
const modalOpen = ref(false)
const isEdit = ref(false)
const submitLoading = ref(false)
const form = ref<TunnelForm>(defaultForm())
const errors = ref<Record<string, string>>({})

const typeOptions = [
  { label: '端口转发', value: 1 },
  { label: '隧道转发', value: 2 },
]
const flowOptions = [
  { label: '单向计算（仅上传）', value: 1 },
  { label: '双向计算（上传+下载）', value: 2 },
]
const protocolOptions = [
  { label: 'TLS', value: 'tls' },
  { label: 'WSS', value: 'wss' },
  { label: 'TCP', value: 'tcp' },
  { label: 'MTLS', value: 'mtls' },
  { label: 'MWSS', value: 'mwss' },
  { label: 'MTCP', value: 'mtcp' },
]

const nodeOptions = computed(() =>
  nodes.value.map((n) => ({ label: n.name, value: n.id, node: n })),
)

function chainOptions(index: number) {
  const current = form.value.chainNodeIds[index]
  return nodes.value
    .filter(
      (node) =>
        node.id === current ||
        (node.id !== form.value.inNodeId &&
          node.id !== form.value.outNodeId &&
          !form.value.chainNodeIds.includes(node.id)),
    )
    .map((n) => ({ label: n.name, value: n.id, node: n }))
}

function nodeLabel(option: any, withEntryChip = false) {
  const node: Node = option.node
  const tags = [
    h(
      NTag,
      { size: 'small', type: node.status === 1 ? 'success' : 'error', bordered: false, round: true },
      { default: () => (node.status === 1 ? '在线' : '离线') },
    ),
  ]
  if (withEntryChip && form.value.inNodeId === node.id) {
    tags.push(
      h(
        NTag,
        { size: 'small', type: 'warning', bordered: false, round: true },
        { default: () => '已选为入口' },
      ),
    )
  }
  return h(
    'div',
    { style: 'display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%' },
    [
      h('span', { style: 'overflow:hidden;text-overflow:ellipsis;white-space:nowrap' }, node.name),
      h('div', { style: 'display:flex;gap:6px;flex-shrink:0' }, tags),
    ],
  )
}
const renderEntryLabel = (o: any) => nodeLabel(o)
const renderExitLabel = (o: any) => nodeLabel(o, true)

function handleAdd() {
  isEdit.value = false
  form.value = defaultForm()
  errors.value = {}
  modalOpen.value = true
}

function handleEdit(tunnel: Tunnel) {
  isEdit.value = true
  form.value = {
    id: tunnel.id,
    name: tunnel.name,
    type: tunnel.type,
    inNodeId: tunnel.inNodeId,
    outNodeId: tunnel.outNodeId || null,
    chainNodeIds: tunnel.chainNodeIds || [],
    protocol: tunnel.protocol || 'tls',
    tcpListenAddr: tunnel.tcpListenAddr || '[::]',
    udpListenAddr: tunnel.udpListenAddr || '[::]',
    interfaceName: tunnel.interfaceName || '',
    flow: tunnel.flow,
    trafficRatio: tunnel.trafficRatio,
    status: tunnel.status,
  }
  errors.value = {}
  modalOpen.value = true
}

function handleTypeChange(type: number) {
  form.value.type = type
  if (type === 1) {
    form.value.outNodeId = null
    form.value.chainNodeIds = []
    form.value.protocol = 'tls'
  }
}

// ============ 中转节点编辑 ============
function addChainNode() {
  const selected = new Set([
    form.value.inNodeId,
    form.value.outNodeId,
    ...form.value.chainNodeIds,
  ])
  const available = nodes.value.find((node) => node.status === 1 && !selected.has(node.id))
  if (!available) {
    toast.error('没有可用的中转节点')
    return
  }
  form.value.chainNodeIds = [...form.value.chainNodeIds, available.id]
}

function updateChainNode(index: number, nodeId: number) {
  form.value.chainNodeIds = form.value.chainNodeIds.map((id, i) => (i === index ? nodeId : id))
}

function removeChainNode(index: number) {
  form.value.chainNodeIds = form.value.chainNodeIds.filter((_, i) => i !== index)
}

function moveChainNode(index: number, direction: -1 | 1) {
  const target = index + direction
  if (target < 0 || target >= form.value.chainNodeIds.length) return
  const list = [...form.value.chainNodeIds]
  ;[list[index], list[target]] = [list[target], list[index]]
  form.value.chainNodeIds = list
}

// ============ 校验 ============
function validateForm(data: TunnelForm): boolean {
  const e: Record<string, string> = {}

  if (!data.name.trim()) {
    e.name = '请输入隧道名称'
  } else if (data.name.length < 2 || data.name.length > 50) {
    e.name = '隧道名称长度应在2-50个字符之间'
  }

  if (!data.inNodeId) {
    e.inNodeId = '请选择入口节点'
  }

  if (!data.tcpListenAddr.trim()) {
    e.tcpListenAddr = '请输入TCP监听地址'
  }
  if (!data.udpListenAddr.trim()) {
    e.udpListenAddr = '请输入UDP监听地址'
  }

  if (data.trafficRatio < 0.0 || data.trafficRatio > 100.0) {
    e.trafficRatio = '流量倍率必须在0.0-100.0之间'
  }

  if (data.type === 2) {
    if (!data.outNodeId) {
      e.outNodeId = '请选择出口节点'
    } else if (data.inNodeId === data.outNodeId) {
      e.outNodeId = '隧道转发模式下，入口和出口不能是同一个节点'
    }

    const pathNodeIds = [data.inNodeId, ...data.chainNodeIds, data.outNodeId].filter(
      (id): id is number => !!id,
    )
    if (pathNodeIds.length !== new Set(pathNodeIds).size) {
      e.chainNodeIds = '同一节点不能在链路中重复使用'
    } else if (
      data.chainNodeIds.some((id) => nodes.value.find((n) => n.id === id)?.status !== 1)
    ) {
      e.chainNodeIds = '中转节点必须在线'
    }

    if (!data.protocol) {
      e.protocol = '请选择协议类型'
    }
  }

  errors.value = e
  return Object.keys(e).length === 0
}

async function handleSubmit() {
  const data = normalizeTunnelForm(form.value)
  if (!validateForm(data)) return

  submitLoading.value = true
  try {
    const response = isEdit.value ? await updateTunnel(data) : await createTunnel(data)
    if (response.code === 0) {
      toast.success(isEdit.value ? '更新成功' : '创建成功')
      modalOpen.value = false
      await loadData()
    } else {
      toast.error(response.msg || (isEdit.value ? '更新失败' : '创建失败'))
    }
  } catch (error) {
    console.error('提交失败:', error)
    toast.error('网络错误，请重试')
  } finally {
    submitLoading.value = false
  }
}

// ============ 删除 ============
const deleteModalOpen = ref(false)
const deleteLoading = ref(false)
const tunnelToDelete = ref<Tunnel | null>(null)

function handleDelete(tunnel: Tunnel) {
  tunnelToDelete.value = tunnel
  deleteModalOpen.value = true
}

async function confirmDelete() {
  if (!tunnelToDelete.value) return
  deleteLoading.value = true
  try {
    const response = await deleteTunnel(tunnelToDelete.value.id)
    if (response.code === 0) {
      toast.success('删除成功')
      deleteModalOpen.value = false
      tunnelToDelete.value = null
      await loadData()
    } else {
      toast.error(response.msg || '删除失败')
    }
  } catch (error) {
    console.error('删除失败:', error)
    toast.error('删除失败')
  } finally {
    deleteLoading.value = false
  }
}

// ============ 诊断 ============
const diagShow = ref(false)
const diagLoading = ref(false)
const diagReport = ref<DiagnosisReport | null>(null)
const diagTunnel = ref<Tunnel | null>(null)

const diagTypeLabel = computed(() =>
  diagTunnel.value ? (diagTunnel.value.type === 1 ? '端口转发' : '隧道转发') : '',
)

async function handleDiagnose(tunnel: Tunnel) {
  diagTunnel.value = tunnel
  diagShow.value = true
  diagLoading.value = true
  diagReport.value = null

  try {
    const response = await diagnoseTunnel(tunnel.id)
    if (response.code === 0) {
      diagReport.value = response.data as DiagnosisReport
    } else {
      toast.error(response.msg || '诊断失败')
      diagReport.value = {
        tunnelName: tunnel.name,
        tunnelType: tunnel.type === 1 ? '端口转发' : '隧道转发',
        timestamp: Date.now(),
        results: [
          {
            success: false,
            description: '诊断失败',
            nodeName: '-',
            nodeId: '-',
            targetIp: '-',
            targetPort: 443,
            message: response.msg || '诊断过程中发生错误',
          },
        ],
      }
    }
  } catch (error) {
    console.error('诊断失败:', error)
    toast.error('网络错误，请重试')
    diagReport.value = {
      tunnelName: tunnel.name,
      tunnelType: tunnel.type === 1 ? '端口转发' : '隧道转发',
      timestamp: Date.now(),
      results: [
        {
          success: false,
          description: '网络错误',
          nodeName: '-',
          nodeId: '-',
          targetIp: '-',
          targetPort: 443,
          message: '无法连接到服务器',
        },
      ],
    }
  } finally {
    diagLoading.value = false
  }
}

function retryDiagnose() {
  if (diagTunnel.value) handleDiagnose(diagTunnel.value)
}

onMounted(loadData)
</script>

<template>
  <PageContainer :loading="loading">
    <!-- 页面头部 -->
    <div class="tn-header">
      <div>
        <h1 class="tn-title">隧道管理</h1>
        <p class="tn-subtitle">管理隧道链路与转发配置</p>
      </div>
      <NButton type="primary" size="small" @click="handleAdd">
        <template #icon>
          <NIcon :component="AddOutline" />
        </template>
        新增
      </NButton>
    </div>

    <!-- 隧道卡片网格 -->
    <div v-if="tunnels.length" class="fx-grid">
      <div v-for="tunnel in tunnels" :key="tunnel.id" class="fx-card tn-card">
        <!-- 头部 -->
        <div class="tn-card-head">
          <span class="tn-name">{{ tunnel.name }}</span>
          <div class="tn-tags">
            <NTag size="small" :bordered="false" :type="getTypeDisplay(tunnel.type).color">
              {{ getTypeDisplay(tunnel.type).text }}
            </NTag>
            <NTag size="small" :bordered="false" :type="getStatusDisplay(tunnel.status).color">
              {{ getStatusDisplay(tunnel.status).text }}
            </NTag>
          </div>
        </div>

        <!-- 链路展示 -->
        <div class="tn-path">
          <div class="tn-node">
            <span class="tn-node-label">入口节点</span>
            <code class="fx-mono tn-node-name">{{ getNodeName(tunnel.inNodeId) }}</code>
            <code class="fx-mono tn-node-ip">{{ getDisplayIp(tunnel.inIp) }}</code>
          </div>

          <template v-for="(nodeId, index) in tunnel.chainNodeIds || []" :key="`${tunnel.id}-hop-${index}`">
            <div class="tn-arrow">
              <NIcon :component="ArrowDownOutline" :size="14" />
            </div>
            <div class="tn-node tn-node-hop">
              <span class="tn-node-label tn-node-label-hop">中转节点 {{ index + 1 }}</span>
              <code class="fx-mono tn-node-name">{{ getNodeName(nodeId) }}</code>
            </div>
          </template>

          <div class="tn-arrow">
            <NIcon :component="ArrowDownOutline" :size="14" />
          </div>

          <div class="tn-node">
            <span class="tn-node-label">
              {{ tunnel.type === 1 ? '出口节点（同入口）' : '出口节点' }}
            </span>
            <code class="fx-mono tn-node-name">
              {{ tunnel.type === 1 ? getNodeName(tunnel.inNodeId) : getNodeName(tunnel.outNodeId) }}
            </code>
            <code class="fx-mono tn-node-ip">
              {{ tunnel.type === 1 ? getDisplayIp(tunnel.inIp) : getDisplayIp(tunnel.outIp) }}
            </code>
          </div>
        </div>

        <!-- 配置信息 -->
        <div class="tn-config">
          <span class="tn-config-item">{{ getFlowDisplay(tunnel.flow) }}</span>
          <span class="tn-config-item">{{ tunnel.trafficRatio }}x</span>
        </div>

        <!-- 操作 -->
        <div class="tn-actions">
          <NButton size="small" type="primary" secondary class="tn-btn" @click="handleEdit(tunnel)">
            <template #icon><NIcon :component="CreateOutline" /></template>
            编辑
          </NButton>
          <NButton size="small" type="warning" secondary class="tn-btn" @click="handleDiagnose(tunnel)">
            <template #icon><NIcon :component="PulseOutline" /></template>
            诊断
          </NButton>
          <NButton size="small" type="error" secondary class="tn-btn" @click="handleDelete(tunnel)">
            <template #icon><NIcon :component="TrashOutline" /></template>
            删除
          </NButton>
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      title="暂无隧道配置"
      description="还没有创建任何隧道配置，点击上方按钮开始创建"
    >
      <template #icon>
        <NIcon :component="WifiOutline" :size="30" />
      </template>
    </EmptyState>

    <!-- 新增 / 编辑 弹窗 -->
    <NModal
      :show="modalOpen"
      preset="card"
      style="width: 720px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (modalOpen = v)"
    >
      <template #header>
        <div>
          <div class="modal-title">{{ isEdit ? '编辑隧道' : '新增隧道' }}</div>
          <div class="modal-sub">{{ isEdit ? '修改现有隧道配置的信息' : '创建新的隧道配置' }}</div>
        </div>
      </template>

      <div class="form-body">
        <!-- 隧道名称 -->
        <div class="form-item">
          <label class="form-label">隧道名称</label>
          <NInput v-model:value="form.name" placeholder="请输入隧道名称" :status="errors.name ? 'error' : undefined" />
          <p v-if="errors.name" class="err">{{ errors.name }}</p>
        </div>

        <!-- 隧道类型 -->
        <div class="form-item">
          <label class="form-label">隧道类型</label>
          <NSelect
            :value="form.type"
            :options="typeOptions"
            placeholder="请选择隧道类型"
            :disabled="isEdit"
            @update:value="handleTypeChange"
          />
        </div>

        <div class="grid2">
          <!-- 流量计算 -->
          <div class="form-item">
            <label class="form-label">流量计算</label>
            <NSelect
              v-model:value="form.flow"
              :options="flowOptions"
              placeholder="请选择流量计算方式"
            />
          </div>

          <!-- 流量倍率 -->
          <div class="form-item">
            <label class="form-label">流量倍率</label>
            <NInputNumber
              :value="form.trafficRatio"
              placeholder="请输入流量倍率"
              :step="0.1"
              style="width: 100%"
              :status="errors.trafficRatio ? 'error' : undefined"
              @update:value="(v: number | null) => (form.trafficRatio = v ?? 0)"
            >
              <template #suffix>x</template>
            </NInputNumber>
            <p v-if="errors.trafficRatio" class="err">{{ errors.trafficRatio }}</p>
          </div>
        </div>

        <NDivider style="margin: 4px 0" />
        <h3 class="form-section">入口配置</h3>

        <!-- 入口节点 -->
        <div class="form-item">
          <label class="form-label">入口节点</label>
          <NSelect
            :value="form.inNodeId"
            :options="nodeOptions"
            placeholder="请选择入口节点"
            :disabled="isEdit"
            :render-label="renderEntryLabel"
            :status="errors.inNodeId ? 'error' : undefined"
            @update:value="(v: number) => (form.inNodeId = normalizeNodeId(v))"
          />
          <p v-if="errors.inNodeId" class="err">{{ errors.inNodeId }}</p>
        </div>

        <div class="grid2">
          <!-- TCP监听地址 -->
          <div class="form-item">
            <label class="form-label">TCP监听地址</label>
            <NInput
              v-model:value="form.tcpListenAddr"
              placeholder="请输入TCP监听地址"
              :status="errors.tcpListenAddr ? 'error' : undefined"
            >
              <template #prefix><span class="addr-prefix">TCP</span></template>
            </NInput>
            <p v-if="errors.tcpListenAddr" class="err">{{ errors.tcpListenAddr }}</p>
          </div>

          <!-- UDP监听地址 -->
          <div class="form-item">
            <label class="form-label">UDP监听地址</label>
            <NInput
              v-model:value="form.udpListenAddr"
              placeholder="请输入UDP监听地址"
              :status="errors.udpListenAddr ? 'error' : undefined"
            >
              <template #prefix><span class="addr-prefix">UDP</span></template>
            </NInput>
            <p v-if="errors.udpListenAddr" class="err">{{ errors.udpListenAddr }}</p>
          </div>
        </div>

        <!-- 出口网卡名或IP（仅隧道转发） -->
        <div v-if="form.type === 2" class="form-item">
          <label class="form-label">出口网卡名或IP</label>
          <NInput v-model:value="form.interfaceName" placeholder="请输入出口网卡名或IP" />
        </div>

        <!-- 出口配置（仅隧道转发） -->
        <template v-if="form.type === 2">
          <NDivider style="margin: 4px 0" />
          <h3 class="form-section">出口配置</h3>

          <!-- 协议类型 -->
          <div class="form-item">
            <label class="form-label">协议类型</label>
            <NSelect
              v-model:value="form.protocol"
              :options="protocolOptions"
              placeholder="请选择协议类型"
              :status="errors.protocol ? 'error' : undefined"
            />
            <p v-if="errors.protocol" class="err">{{ errors.protocol }}</p>
          </div>

          <!-- 中转节点 -->
          <div class="form-item">
            <div class="chain-head">
              <h3 class="chain-title">中转节点</h3>
              <NButton size="small" type="primary" secondary @click="addChainNode">
                <template #icon><NIcon :component="AddOutline" /></template>
                添加跳点
              </NButton>
            </div>

            <div
              v-for="(nodeId, index) in form.chainNodeIds"
              :key="`chain-node-${index}`"
              class="chain-row"
            >
              <NTag size="small" :bordered="false" class="chain-idx">{{ index + 1 }}</NTag>
              <NSelect
                :value="nodeId"
                :options="chainOptions(index)"
                :aria-label="`中转节点 ${index + 1}`"
                placeholder="请选择中转节点"
                :render-label="renderEntryLabel"
                class="chain-select"
                @update:value="(v: number) => { const id = normalizeNodeId(v); if (id) updateChainNode(index, id) }"
              />
              <NButton
                circle
                size="small"
                quaternary
                aria-label="上移中转节点"
                :disabled="index === 0"
                @click="moveChainNode(index, -1)"
              >
                <template #icon><NIcon :component="ChevronUpOutline" /></template>
              </NButton>
              <NButton
                circle
                size="small"
                quaternary
                aria-label="下移中转节点"
                :disabled="index === form.chainNodeIds.length - 1"
                @click="moveChainNode(index, 1)"
              >
                <template #icon><NIcon :component="ChevronDownOutline" /></template>
              </NButton>
              <NButton
                circle
                size="small"
                quaternary
                type="error"
                aria-label="删除中转节点"
                @click="removeChainNode(index)"
              >
                <template #icon><NIcon :component="CloseOutline" /></template>
              </NButton>
            </div>
            <p v-if="errors.chainNodeIds" class="err">{{ errors.chainNodeIds }}</p>
          </div>

          <!-- 出口节点 -->
          <div class="form-item">
            <label class="form-label">出口节点</label>
            <NSelect
              :value="form.outNodeId"
              :options="nodeOptions"
              placeholder="请选择出口节点"
              :disabled="isEdit"
              :render-label="renderExitLabel"
              :status="errors.outNodeId ? 'error' : undefined"
              @update:value="(v: number) => (form.outNodeId = normalizeNodeId(v))"
            />
            <p v-if="errors.outNodeId" class="err">{{ errors.outNodeId }}</p>
          </div>
        </template>

        <!-- 说明 -->
        <NAlert type="info" :bordered="false" title="TCP,UDP监听地址" style="margin-top: 4px">
          V6或者双栈填写[::],V4填写0.0.0.0。不懂的就去看文档网站内的说明
        </NAlert>
        <NAlert type="info" :bordered="false" title="出口网卡名或IP">
          用于多IP服务器指定使用那个IP和出口服务器通讯，不懂的默认为空就行
        </NAlert>
      </div>

      <template #footer>
        <div class="modal-footer">
          <NButton @click="modalOpen = false">取消</NButton>
          <NButton type="primary" :loading="submitLoading" @click="handleSubmit">
            {{ submitLoading ? (isEdit ? '更新中...' : '创建中...') : isEdit ? '更新' : '创建' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 删除确认 弹窗 -->
    <NModal
      :show="deleteModalOpen"
      preset="card"
      style="width: 480px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (deleteModalOpen = v)"
    >
      <template #header>
        <span class="delete-title">确认删除</span>
      </template>
      <div class="delete-body">
        <p>确定要删除隧道 "{{ tunnelToDelete?.name }}" 吗？</p>
        <p class="text-secondary">此操作不可恢复，请谨慎操作。</p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="deleteModalOpen = false">取消</NButton>
          <NButton type="error" :loading="deleteLoading" @click="confirmDelete">
            {{ deleteLoading ? '删除中...' : '确认删除' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 诊断结果 -->
    <DiagnosisDialog
      v-model:show="diagShow"
      :loading="diagLoading"
      :report="diagReport"
      title="隧道诊断结果"
      :subtitle="diagTunnel?.name"
      :type-label="diagTypeLabel"
      @retry="retryDiagnose"
    />
  </PageContainer>
</template>

<style scoped>
.tn-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.tn-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.tn-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 4px 0 0;
}

.tn-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
}
.tn-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.tn-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  min-width: 0;
}
.tn-tags {
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}

.tn-path {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.tn-node {
  padding: 8px 10px;
  border-radius: 8px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-soft);
}
.tn-node-hop {
  background: rgba(240, 160, 32, 0.1);
  border-color: rgba(240, 160, 32, 0.35);
}
.tn-node-label {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-secondary);
  display: block;
  margin-bottom: 2px;
}
.tn-node-label-hop {
  color: #d48806;
}
.tn-node-name {
  font-size: 12px;
  color: var(--text-primary);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tn-node-ip {
  font-size: 12px;
  color: var(--text-secondary);
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.tn-arrow {
  display: flex;
  justify-content: center;
  color: var(--text-secondary);
  opacity: 0.6;
  padding: 1px 0;
}

.tn-config {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-top: 8px;
  border-top: 1px solid var(--border-soft);
}
.tn-config-item {
  font-size: 12px;
  font-weight: 500;
  color: var(--text-primary);
}

.tn-actions {
  display: flex;
  gap: 6px;
  margin-top: 2px;
}
.tn-btn {
  flex: 1;
}

/* 弹窗表单 */
.modal-title {
  font-size: 18px;
  font-weight: 700;
  color: var(--text-primary);
}
.modal-sub {
  font-size: 13px;
  color: var(--text-secondary);
  margin-top: 2px;
}
.form-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  max-height: 62vh;
  overflow-y: auto;
  padding-right: 4px;
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
.form-section {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.grid2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.addr-prefix {
  font-size: 12px;
  color: var(--text-secondary);
}
.err {
  font-size: 12px;
  color: var(--error-color, #d03050);
  margin: 0;
}

.chain-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.chain-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.chain-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.chain-idx {
  flex-shrink: 0;
}
.chain-select {
  flex: 1;
  min-width: 0;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.delete-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--error-color, #d03050);
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

@media (max-width: 768px) {
  .tn-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .grid2 {
    grid-template-columns: 1fr;
  }
}
</style>
