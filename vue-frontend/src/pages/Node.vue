<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NButton, NModal, NInput, NInputNumber, NTag, NSwitch, NProgress, NAlert, NIcon } from 'naive-ui'
import {
  AddOutline,
  ServerOutline,
  CopyOutline,
  CreateOutline,
  TrashOutline,
  ArrowUpOutline,
  ArrowDownOutline,
  CloudUploadOutline,
  CloudDownloadOutline,
} from '@vicons/ionicons5'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useToast } from '@/composables/useToast'
import { useNodeSocket } from '@/composables/useNodeSocket'
import type { NodeSocketMessage } from '@/composables/useNodeSocket'
import { getNodeList, createNode, updateNode, deleteNode, getNodeInstallCommand } from '@/api'
import { copyText } from '@/utils/clipboard'
import { formatSpeed, formatTraffic, formatUptime } from '@/utils/format'
import type { NodeItem, NodeForm } from '@/types'

const toast = useToast()

const loading = ref(true)
const nodes = ref<NodeItem[]>([])

// 保存每个节点上一次采样，用于推导瞬时速率（不进入响应式）
const prevSamples = new Map<
  number,
  { uptime: number; uploadTraffic: number; downloadTraffic: number }
>()

// ============ 加载节点 ============
async function loadNodes() {
  loading.value = true
  try {
    const res = await getNodeList()
    if (res.code === 0) {
      nodes.value = (res.data || []).map((row: any) => ({
        ...row,
        connectionStatus: row.status === 1 ? 'online' : 'offline',
        systemInfo: null,
        copyLoading: false,
      })) as NodeItem[]
    } else {
      toast.error(res.msg || '加载节点列表失败')
    }
  } catch (e) {
    console.warn('加载节点列表失败:', e)
    toast.error('网络错误，请重试')
  } finally {
    loading.value = false
  }
}

// ============ WebSocket 实时遥测 ============
function handleSocketMessage(msg: NodeSocketMessage) {
  const { id, type, data } = msg
  if (type === 'status') {
    const node = nodes.value.find((n) => n.id == id)
    if (!node) return
    if (data === 1) {
      node.connectionStatus = 'online'
    } else {
      node.connectionStatus = 'offline'
      if (data === 0) {
        node.systemInfo = null
        prevSamples.delete(node.id)
      }
    }
  } else if (type === 'info') {
    let payload: any = data
    if (typeof payload === 'string') {
      try {
        payload = JSON.parse(payload)
      } catch {
        return
      }
    }
    if (!payload || typeof payload !== 'object') return
    const node = nodes.value.find((n) => n.id == id)
    if (!node) return

    const cpuUsage = Number(payload.cpu_usage) || 0
    const memoryUsage = Number(payload.memory_usage) || 0
    const uploadTraffic = Number(payload.bytes_transmitted) || 0
    const downloadTraffic = Number(payload.bytes_received) || 0
    const uptime = Number(payload.uptime) || 0

    let uploadSpeed = 0
    let downloadSpeed = 0
    const prev = prevSamples.get(node.id)
    if (prev) {
      const timeDiff = uptime - prev.uptime
      if (timeDiff > 0 && timeDiff <= 10) {
        const upDelta = uploadTraffic - prev.uploadTraffic
        if (upDelta >= 0) uploadSpeed = upDelta / timeDiff
        const downDelta = downloadTraffic - prev.downloadTraffic
        if (downDelta >= 0) downloadSpeed = downDelta / timeDiff
      }
    }
    prevSamples.set(node.id, { uptime, uploadTraffic, downloadTraffic })

    node.connectionStatus = 'online'
    node.systemInfo = {
      cpuUsage,
      memoryUsage,
      uploadTraffic,
      downloadTraffic,
      uploadSpeed,
      downloadSpeed,
      uptime,
    }
  }
}

// 组件卸载自动断开
useNodeSocket(handleSocketMessage)

// ============ 展示辅助 ============
function isOnline(node: NodeItem): boolean {
  return node.connectionStatus === 'online'
}

function inIpDisplay(node: NodeItem): { text: string; title: string } {
  const ips = (node.ip || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  if (ips.length === 0) return { text: '-', title: '' }
  if (ips.length === 1) return { text: ips[0], title: ips[0] }
  return { text: `${ips[0]} +${ips.length - 1}个`, title: ips.join(', ') }
}

function percentText(value?: number): string {
  return typeof value === 'number' ? `${value.toFixed(1)}%` : '-'
}

function clampPercent(value?: number): number {
  const v = typeof value === 'number' ? value : 0
  return Math.min(100, Math.max(0, v))
}

/** 进度条颜色：离线灰、<=50 绿、<=80 橙、其余红 */
function getProgressColor(value: number, offline = false): string {
  if (offline) return '#94a3b8'
  if (value <= 50) return '#18a058'
  if (value <= 80) return '#f0a020'
  return '#e04141'
}

// ============ IP 校验 ============
function validateIp(ip: string): boolean {
  const v = (ip || '').trim()
  if (!v) return false
  if (v === 'localhost') return true

  const ipv4 =
    /^((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)$/
  if (ipv4.test(v)) return true

  const ipv6 =
    /^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,5}(:[0-9a-fA-F]{1,4}){1,2}|([0-9a-fA-F]{1,4}:){1,4}(:[0-9a-fA-F]{1,4}){1,3}|([0-9a-fA-F]{1,4}:){1,3}(:[0-9a-fA-F]{1,4}){1,4}|([0-9a-fA-F]{1,4}:){1,2}(:[0-9a-fA-F]{1,4}){1,5}|[0-9a-fA-F]{1,4}:((:[0-9a-fA-F]{1,4}){1,6})|:((:[0-9a-fA-F]{1,4}){1,7}|:)|fe80:(:[0-9a-fA-F]{0,4}){0,4}%[0-9a-zA-Z]+|::(ffff(:0{1,4})?:)?((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9])|([0-9a-fA-F]{1,4}:){1,4}:((25[0-5]|(2[0-4]|1?[0-9])?[0-9])\.){3}(25[0-5]|(2[0-4]|1?[0-9])?[0-9]))$/
  if (ipv6.test(v)) return true

  // 纯数字串不视为合法地址
  if (/^\d+$/.test(v)) return false

  const domain = /^([a-zA-Z0-9]([a-zA-Z0-9\-]{0,61}[a-zA-Z0-9])?\.)+[a-zA-Z]{2,}$/
  if (domain.test(v)) return true

  const singleLabel = /^[a-zA-Z][a-zA-Z0-9\-]{0,62}$/
  if (singleLabel.test(v)) return true

  return false
}

// ============ 表单 ============
function defaultForm(): NodeForm {
  return {
    id: null,
    name: '',
    ipString: '',
    serverIp: '',
    portSta: 1000,
    portEnd: 65535,
    http: 0,
    tls: 0,
    socks: 0,
  }
}

const showFormModal = ref(false)
const submitLoading = ref(false)
const form = ref<NodeForm>(defaultForm())
const isEdit = computed(() => form.value.id != null)

// 屏蔽协议区块状态
const protocolDisabled = ref(true)
const protocolDisabledReason = ref('节点未在线，等待节点上线后再设置')

function openCreate() {
  form.value = defaultForm()
  protocolDisabled.value = true
  protocolDisabledReason.value = '节点未在线，等待节点上线后再设置'
  showFormModal.value = true
}

function openEdit(node: NodeItem) {
  const online = isOnline(node)
  form.value = {
    id: node.id,
    name: node.name || '',
    ipString: (node.ip || '')
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean)
      .join('\n'),
    serverIp: node.serverIp || '',
    portSta: node.portSta,
    portEnd: node.portEnd,
    http: online ? (typeof node.http === 'number' ? node.http : 1) : 0,
    tls: online ? (typeof node.tls === 'number' ? node.tls : 1) : 0,
    socks: online ? (typeof node.socks === 'number' ? node.socks : 1) : 0,
  }
  protocolDisabled.value = !online
  protocolDisabledReason.value = online ? '' : '节点未在线，等待节点上线后再设置'
  showFormModal.value = true
}

function setProtocol(key: 'http' | 'tls' | 'socks', v: boolean) {
  form.value[key] = v ? 1 : 0
}

function validateForm(): string | null {
  const name = (form.value.name || '').trim()
  if (!name) return '请输入节点名称'
  if (name.length < 2) return '节点名称长度至少2位'
  if (name.length > 50) return '节点名称长度不能超过50位'

  const ipString = form.value.ipString || ''
  if (!ipString.trim()) return '请输入入口IP地址'
  const ips = ipString
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
  if (ips.length === 0) return '请输入至少一个有效IP地址'
  for (let i = 0; i < ips.length; i++) {
    if (!validateIp(ips[i])) return `第${i + 1}行IP地址格式错误: ${ips[i]}`
  }

  const serverIp = (form.value.serverIp || '').trim()
  if (!serverIp) return '请输入服务器IP地址'
  if (!validateIp(serverIp)) return '请输入有效的IPv4、IPv6地址或域名'

  const { portSta, portEnd } = form.value
  if (!portSta || portSta < 1 || portSta > 65535) return '端口范围必须在1-65535之间'
  if (!portEnd || portEnd < 1 || portEnd > 65535) return '端口范围必须在1-65535之间'
  if (portEnd < portSta) return '结束端口不能小于起始端口'

  return null
}

async function submitForm() {
  const err = validateForm()
  if (err) {
    toast.error(err)
    return
  }
  const editing = isEdit.value
  const ip = (form.value.ipString || '')
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean)
    .join(',')

  submitLoading.value = true
  try {
    let res
    if (editing) {
      const body: any = { ...form.value, ip }
      delete body.ipString
      res = await updateNode(body)
    } else {
      res = await createNode({
        name: form.value.name,
        ip,
        serverIp: form.value.serverIp,
        portSta: form.value.portSta,
        portEnd: form.value.portEnd,
        http: form.value.http,
        tls: form.value.tls,
        socks: form.value.socks,
      })
    }
    if (res.code === 0) {
      toast.success(editing ? '更新成功' : '创建成功')
      showFormModal.value = false
      if (editing) {
        // 原地更新，保留 version / 遥测数据
        const idx = nodes.value.findIndex((n) => n.id === form.value.id)
        if (idx !== -1) {
          const n = nodes.value[idx]
          n.name = form.value.name
          n.ip = ip
          n.serverIp = form.value.serverIp
          n.portSta = form.value.portSta
          n.portEnd = form.value.portEnd
          n.http = form.value.http
          n.tls = form.value.tls
          n.socks = form.value.socks
        }
      } else {
        await loadNodes()
      }
    } else {
      toast.error(res.msg || (editing ? '更新失败' : '创建失败'))
    }
  } catch (e) {
    console.warn('提交节点失败:', e)
    toast.error('网络错误，请重试')
  } finally {
    submitLoading.value = false
  }
}

// ============ 安装命令 ============
const showInstallModal = ref(false)
const installCommand = ref('')
const currentNodeName = ref('')

async function handleCopyInstallCommand(node: NodeItem) {
  node.copyLoading = true
  try {
    const res = await getNodeInstallCommand(node.id)
    if (res.code === 0 && res.data) {
      let copied = false
      try {
        copied = await copyText(res.data)
      } catch {
        copied = false
      }
      if (copied) {
        toast.success('安装命令已复制到剪贴板')
      } else {
        // 复制失败，打开安装命令弹窗供手动复制
        installCommand.value = res.data
        currentNodeName.value = node.name
        showInstallModal.value = true
      }
    } else {
      toast.error(res.msg || '获取安装命令失败')
    }
  } catch (e) {
    console.warn('获取安装命令失败:', e)
    toast.error('获取安装命令失败')
  } finally {
    node.copyLoading = false
  }
}

async function copyInstallFromModal() {
  const ok = await copyText(installCommand.value)
  if (ok) {
    toast.success('安装命令已复制到剪贴板')
    showInstallModal.value = false
  } else {
    toast.error('复制失败，请手动选择文本复制')
  }
}

// ============ 删除 ============
const showDeleteModal = ref(false)
const deleteLoading = ref(false)
const nodeToDelete = ref<NodeItem | null>(null)

function openDelete(node: NodeItem) {
  nodeToDelete.value = node
  showDeleteModal.value = true
}

async function confirmDelete() {
  if (!nodeToDelete.value) return
  const target = nodeToDelete.value
  deleteLoading.value = true
  try {
    const res = await deleteNode(target.id)
    if (res.code === 0) {
      toast.success('删除成功')
      const idx = nodes.value.findIndex((n) => n.id === target.id)
      if (idx !== -1) nodes.value.splice(idx, 1)
      prevSamples.delete(target.id)
      showDeleteModal.value = false
      nodeToDelete.value = null
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch (e) {
    console.warn('删除节点失败:', e)
    toast.error('网络错误，请重试')
  } finally {
    deleteLoading.value = false
  }
}

onMounted(loadNodes)
</script>

<template>
  <PageContainer :loading="loading">
    <div class="node-header">
      <div>
        <h1 class="node-title">节点监控</h1>
        <p class="node-subtitle">管理并实时监控转发节点</p>
      </div>
      <NButton type="primary" @click="openCreate">
        <template #icon><NIcon :component="AddOutline" /></template>
        新增
      </NButton>
    </div>

    <div v-if="nodes.length" class="fx-grid">
      <div v-for="node in nodes" :key="node.id" class="fx-card node-card">
        <!-- 头部 -->
        <div class="node-card-head">
          <div class="node-card-head-main">
            <span class="node-name">{{ node.name }}</span>
            <span class="node-server text-secondary fx-mono">{{ node.serverIp }}</span>
          </div>
          <NTag
            size="small"
            :bordered="false"
            :type="isOnline(node) ? 'success' : 'error'"
          >
            {{ isOnline(node) ? '在线' : '离线' }}
          </NTag>
        </div>

        <!-- 基本信息 -->
        <div class="node-rows">
          <div class="node-row">
            <span class="node-label">入口IP</span>
            <span class="node-value fx-mono" :title="inIpDisplay(node).title">
              {{ inIpDisplay(node).text }}
            </span>
          </div>
          <div class="node-row">
            <span class="node-label">端口</span>
            <span class="node-value fx-mono">{{ node.portSta }}-{{ node.portEnd }}</span>
          </div>
          <div class="node-row">
            <span class="node-label">版本</span>
            <span class="node-value">{{ node.version || '未知' }}</span>
          </div>
          <div class="node-row">
            <span class="node-label">开机时间</span>
            <span class="node-value">
              {{ isOnline(node) && node.systemInfo ? formatUptime(node.systemInfo.uptime) : '-' }}
            </span>
          </div>
        </div>

        <!-- 监控 -->
        <div class="node-monitor">
          <div class="monitor-bar">
            <div class="monitor-bar-head">
              <span class="node-label">CPU</span>
              <span class="node-value">{{ node.systemInfo ? percentText(node.systemInfo.cpuUsage) : '-' }}</span>
            </div>
            <NProgress
              type="line"
              aria-label="CPU使用率"
              :height="8"
              :show-indicator="false"
              :percentage="clampPercent(node.systemInfo?.cpuUsage)"
              :color="getProgressColor(node.systemInfo?.cpuUsage ?? 0, !isOnline(node) || !node.systemInfo)"
            />
          </div>
          <div class="monitor-bar">
            <div class="monitor-bar-head">
              <span class="node-label">内存</span>
              <span class="node-value">{{ node.systemInfo ? percentText(node.systemInfo.memoryUsage) : '-' }}</span>
            </div>
            <NProgress
              type="line"
              aria-label="内存使用率"
              :height="8"
              :show-indicator="false"
              :percentage="clampPercent(node.systemInfo?.memoryUsage)"
              :color="getProgressColor(node.systemInfo?.memoryUsage ?? 0, !isOnline(node) || !node.systemInfo)"
            />
          </div>

          <div class="monitor-tiles">
            <div class="monitor-tile">
              <div class="tile-head">
                <NIcon :component="CloudUploadOutline" />
                <span>上传</span>
              </div>
              <span class="tile-value fx-mono">
                {{ node.systemInfo ? formatSpeed(node.systemInfo.uploadSpeed) : '-' }}
              </span>
            </div>
            <div class="monitor-tile">
              <div class="tile-head">
                <NIcon :component="CloudDownloadOutline" />
                <span>下载</span>
              </div>
              <span class="tile-value fx-mono">
                {{ node.systemInfo ? formatSpeed(node.systemInfo.downloadSpeed) : '-' }}
              </span>
            </div>
            <div class="monitor-tile tile-up">
              <div class="tile-head">
                <NIcon :component="ArrowUpOutline" />
                <span>上行流量</span>
              </div>
              <span class="tile-value fx-mono">
                {{ node.systemInfo ? formatTraffic(node.systemInfo.uploadTraffic) : '-' }}
              </span>
            </div>
            <div class="monitor-tile tile-down">
              <div class="tile-head">
                <NIcon :component="ArrowDownOutline" />
                <span>下行流量</span>
              </div>
              <span class="tile-value fx-mono">
                {{ node.systemInfo ? formatTraffic(node.systemInfo.downloadTraffic) : '-' }}
              </span>
            </div>
          </div>
        </div>

        <!-- 操作 -->
        <div class="node-actions">
          <NButton
            size="small"
            type="success"
            secondary
            class="node-btn"
            :loading="node.copyLoading"
            @click="handleCopyInstallCommand(node)"
          >
            <template #icon><NIcon :component="CopyOutline" /></template>
            安装
          </NButton>
          <NButton size="small" type="primary" secondary class="node-btn" @click="openEdit(node)">
            <template #icon><NIcon :component="CreateOutline" /></template>
            编辑
          </NButton>
          <NButton size="small" type="error" secondary class="node-btn" @click="openDelete(node)">
            <template #icon><NIcon :component="TrashOutline" /></template>
            删除
          </NButton>
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      title="暂无节点配置"
      description="还没有创建任何节点配置，点击上方按钮开始创建"
    >
      <template #icon>
        <NIcon :component="ServerOutline" :size="30" />
      </template>
    </EmptyState>

    <!-- 新增 / 编辑 弹窗 -->
    <NModal
      :show="showFormModal"
      preset="card"
      :title="isEdit ? '编辑节点' : '新增节点'"
      style="width: 640px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showFormModal = v)"
    >
      <div class="form-grid">
        <div class="form-item">
          <label class="form-label">节点名称</label>
          <NInput v-model:value="form.name" placeholder="请输入节点名称" />
        </div>

        <div class="form-item">
          <label class="form-label">服务器IP</label>
          <NInput
            v-model:value="form.serverIp"
            placeholder="请输入服务器IP地址，如: 192.168.1.100 或 example.com"
          />
        </div>

        <div class="form-item">
          <label class="form-label">入口IP</label>
          <NInput
            v-model:value="form.ipString"
            type="textarea"
            :autosize="{ minRows: 3, maxRows: 5 }"
            placeholder="一行一个IP地址或域名，例如:&#10;192.168.1.100&#10;example.com"
          />
          <span class="form-desc text-secondary">支持多个IP，每行一个地址</span>
        </div>

        <div class="form-row">
          <div class="form-item">
            <label class="form-label">起始端口</label>
            <NInputNumber
              v-model:value="form.portSta"
              :min="1"
              :max="65535"
              placeholder="1000"
              style="width: 100%"
            />
          </div>
          <div class="form-item">
            <label class="form-label">结束端口</label>
            <NInputNumber
              v-model:value="form.portEnd"
              :min="1"
              :max="65535"
              placeholder="65535"
              style="width: 100%"
            />
          </div>
        </div>

        <!-- 屏蔽协议 -->
        <div class="protocol-block" :class="{ 'protocol-disabled': protocolDisabled }">
          <div class="protocol-head">
            <span class="protocol-title">屏蔽协议</span>
            <span class="protocol-sub text-secondary">开启开关以屏蔽对应协议</span>
          </div>

          <NAlert
            v-if="protocolDisabled"
            type="warning"
            :bordered="false"
            style="margin-bottom: 12px"
          >
            {{ protocolDisabledReason || '等待节点上线后再设置' }}
          </NAlert>

          <div class="protocol-tiles">
            <div class="protocol-tile">
              <div class="protocol-tile-head">
                <span class="protocol-tile-name">HTTP</span>
                <span class="protocol-tile-state text-secondary">
                  {{ form.http === 1 ? '启用' : '禁用' }}
                </span>
              </div>
              <div class="protocol-tile-switch">
                <NSwitch
                  size="small"
                  :value="form.http === 1"
                  :disabled="protocolDisabled"
                  @update:value="(v: boolean) => setProtocol('http', v)"
                />
                <span class="protocol-caption text-secondary">
                  {{ form.http === 1 ? '已开启' : '已关闭' }}
                </span>
              </div>
            </div>

            <div class="protocol-tile">
              <div class="protocol-tile-head">
                <span class="protocol-tile-name">TLS</span>
                <span class="protocol-tile-state text-secondary">
                  {{ form.tls === 1 ? '启用' : '禁用' }}
                </span>
              </div>
              <div class="protocol-tile-switch">
                <NSwitch
                  size="small"
                  :value="form.tls === 1"
                  :disabled="protocolDisabled"
                  @update:value="(v: boolean) => setProtocol('tls', v)"
                />
                <span class="protocol-caption text-secondary">
                  {{ form.tls === 1 ? '已开启' : '已关闭' }}
                </span>
              </div>
            </div>

            <div class="protocol-tile">
              <div class="protocol-tile-head">
                <span class="protocol-tile-name">SOCKS</span>
                <span class="protocol-tile-state text-secondary">
                  {{ form.socks === 1 ? '启用' : '禁用' }}
                </span>
              </div>
              <div class="protocol-tile-switch">
                <NSwitch
                  size="small"
                  :value="form.socks === 1"
                  :disabled="protocolDisabled"
                  @update:value="(v: boolean) => setProtocol('socks', v)"
                />
                <span class="protocol-caption text-secondary">
                  {{ form.socks === 1 ? '已开启' : '已关闭' }}
                </span>
              </div>
            </div>
          </div>
        </div>

        <NAlert type="error" :bordered="false">
          请不要在出口节点执行屏蔽协议，否则可能影响转发；屏蔽协议仅需在入口节点执行。
        </NAlert>
        <NAlert type="info" :bordered="false">
          服务器ip是你要添加的服务器的ip地址，不是面板的ip地址。入口ip是用于展示在转发页面，面向用户的访问地址。实在理解不到说明你没这个需求，都填节点的服务器ip就行！
        </NAlert>
      </div>

      <template #footer>
        <div class="modal-footer">
          <NButton @click="showFormModal = false">取消</NButton>
          <NButton type="primary" :loading="submitLoading" @click="submitForm">
            {{ submitLoading ? '提交中...' : '确定' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 安装命令 弹窗 -->
    <NModal
      :show="showInstallModal"
      preset="card"
      :title="`安装命令 - ${currentNodeName}`"
      style="width: 680px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showInstallModal = v)"
    >
      <div class="install-body">
        <p class="install-tip">请复制以下安装命令到服务器上执行：</p>
        <div class="install-textarea-wrap">
          <NInput
            :value="installCommand"
            type="textarea"
            readonly
            class="fx-mono"
            :autosize="{ minRows: 6, maxRows: 10 }"
          />
          <NButton
            size="small"
            type="primary"
            class="install-copy-btn"
            @click="copyInstallFromModal"
          >
            <template #icon><NIcon :component="CopyOutline" /></template>
            复制
          </NButton>
        </div>
        <p class="install-hint text-secondary">
          💡 提示：如果复制按钮失效，请手动选择上方文本进行复制
        </p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showInstallModal = false">关闭</NButton>
        </div>
      </template>
    </NModal>

    <!-- 删除确认 弹窗 -->
    <NModal
      :show="showDeleteModal"
      preset="card"
      style="width: 460px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showDeleteModal = v)"
    >
      <template #header>
        <span class="delete-title">确认删除</span>
      </template>
      <div class="delete-body">
        <p>确定要删除节点 "{{ nodeToDelete?.name }}" 吗？</p>
        <p class="text-secondary">此操作不可恢复，请谨慎操作。</p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showDeleteModal = false">取消</NButton>
          <NButton type="error" :loading="deleteLoading" @click="confirmDelete">
            {{ deleteLoading ? '删除中...' : '确认删除' }}
          </NButton>
        </div>
      </template>
    </NModal>
  </PageContainer>
</template>

<style scoped>
.node-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.node-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.node-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 4px 0 0;
}

.node-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
}
.node-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.node-card-head-main {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}
.node-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.node-server {
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.node-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.node-label {
  font-size: 13px;
  color: var(--text-secondary);
  flex-shrink: 0;
}
.node-value {
  font-size: 13px;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  text-align: right;
}

.node-monitor {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding-top: 4px;
  border-top: 1px solid var(--border-soft);
}
.monitor-bar {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.monitor-bar-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.monitor-tiles {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.monitor-tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  border-radius: var(--radius-md);
  background: var(--bg-subtle);
}
.tile-head {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  color: var(--text-secondary);
}
.tile-value {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.monitor-tile.tile-up {
  background: rgba(37, 99, 235, 0.08);
}
.monitor-tile.tile-up .tile-head {
  color: var(--brand-500);
}
.monitor-tile.tile-down {
  background: rgba(24, 160, 88, 0.08);
}
.monitor-tile.tile-down .tile-head {
  color: var(--ok);
}

.node-actions {
  display: flex;
  gap: 8px;
  margin-top: 2px;
}
.node-btn {
  flex: 1;
}

/* 表单 */
.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
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
}

/* 屏蔽协议 */
.protocol-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px;
  border-radius: var(--radius-md);
  border: 1px solid var(--border-soft);
  background: var(--bg-subtle);
}
.protocol-block.protocol-disabled {
  opacity: 0.7;
}
.protocol-head {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.protocol-title {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.protocol-sub {
  font-size: 12px;
}
.protocol-tiles {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.protocol-tile {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  border-radius: var(--radius-md);
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
}
.protocol-tile-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.protocol-tile-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.protocol-tile-state {
  font-size: 12px;
}
.protocol-tile-switch {
  display: flex;
  align-items: center;
  gap: 8px;
}
.protocol-caption {
  font-size: 12px;
}

/* 安装命令 */
.install-body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.install-tip {
  margin: 0;
  font-size: 14px;
  color: var(--text-primary);
}
.install-textarea-wrap {
  position: relative;
}
.install-copy-btn {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 1;
}
.install-hint {
  margin: 0;
  font-size: 12px;
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}
.delete-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--danger, #e04141);
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
  .node-header {
    flex-direction: column;
    align-items: flex-start;
  }
  .protocol-tiles {
    grid-template-columns: 1fr;
  }
}
</style>
