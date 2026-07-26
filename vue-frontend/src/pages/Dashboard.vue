<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { NProgress, NIcon } from 'naive-ui'
import {
  CloudOutline,
  PulseOutline,
  GitNetworkOutline,
  LayersOutline,
  TimeOutline,
} from '@vicons/ionicons5'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import AddressModal from '@/components/AddressModal.vue'
import { getUserPackageInfo } from '@/api'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import {
  formatFlow,
  formatNumber,
  formatResetTime,
  formatInAddress,
  formatRemoteAddress,
  hasMultiple,
} from '@/utils/format'

use([LineChart, GridComponent, TooltipComponent, CanvasRenderer])

/* ---------- 本地接口（与后端 UserPackageDto 对齐） ---------- */
interface UserInfo {
  flow: number
  inFlow: number
  outFlow: number
  num: number
  expTime?: string
  flowResetTime?: number | null
}
interface UserTunnel {
  id: number
  tunnelId: number
  tunnelName: string
  flow: number
  inFlow: number
  outFlow: number
  num: number
  expTime?: string
  flowResetTime?: number | null
  tunnelFlow: number
}
interface Forward {
  id: number
  name: string
  tunnelId: number
  tunnelName: string
  inIp: string
  inPort: number
  remoteAddr: string
  inFlow: number
  outFlow: number
}
interface StatisticsFlow {
  id: number
  userId: number
  flow: number
  totalFlow: number
  time: string
}
interface PackageData {
  userInfo: UserInfo
  tunnelPermissions: UserTunnel[]
  forwards: Forward[]
  statisticsFlows: StatisticsFlow[]
}

const toast = useToast()
const auth = useAuthStore()
const isAdmin = computed(() => auth.isAdmin)

const GIB = 1024 * 1024 * 1024

const loading = ref(true)
const userInfo = ref<UserInfo>({ flow: 0, inFlow: 0, outFlow: 0, num: 0 })
const userTunnels = ref<UserTunnel[]>([])
const forwardList = ref<Forward[]>([])
const statisticsFlows = ref<StatisticsFlow[]>([])

/* 地址弹窗 */
const addrModal = ref<{ show: boolean; title: string; addresses: string[] }>({
  show: false,
  title: '',
  addresses: [],
})

function reset() {
  userInfo.value = { flow: 0, inFlow: 0, outFlow: 0, num: 0 }
  userTunnels.value = []
  forwardList.value = []
  statisticsFlows.value = []
}

async function load() {
  loading.value = true
  try {
    const res = await getUserPackageInfo()
    if (res.code === 0 && res.data) {
      const d = res.data as Partial<PackageData>
      userInfo.value = d.userInfo || { flow: 0, inFlow: 0, outFlow: 0, num: 0 }
      userTunnels.value = d.tunnelPermissions || []
      forwardList.value = d.forwards || []
      statisticsFlows.value = d.statisticsFlows || []
    } else {
      toast.error(res.msg || '获取套餐信息失败')
    }
  } catch {
    toast.error('获取套餐信息失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  reset()
  try {
    localStorage.setItem('e', '/dashboard')
  } catch {}
  load()
})

/* ---------- 进度条工具 ---------- */
function barColor(pct: number): string {
  if (pct >= 90) return '#ef4444'
  if (pct >= 70) return '#f97316'
  return '#2563eb'
}

/* ---------- Section A 指标 ---------- */
const usedFlow = computed(() => (userInfo.value.inFlow || 0) + (userInfo.value.outFlow || 0))
const flowUnlimited = computed(() => userInfo.value.flow === 99999)
const flowPct = computed(() => {
  if (flowUnlimited.value) return 0
  const total = (userInfo.value.flow || 0) * GIB
  if (!total) return 0
  return Math.min((usedFlow.value / total) * 100, 100)
})
const numUnlimited = computed(() => userInfo.value.num === 99999)
const forwardPct = computed(() => {
  if (numUnlimited.value) return 0
  const num = userInfo.value.num || 0
  if (!num) return 0
  return Math.min((forwardList.value.length / num) * 100, 100)
})

/* ---------- Section C 每隧道计算 ---------- */
function tunnelUsed(t: UserTunnel) {
  return (t.inFlow || 0) + (t.outFlow || 0)
}
function tunnelFlowPct(t: UserTunnel) {
  if (t.flow === 99999) return 0
  const total = (t.flow || 0) * GIB
  if (!total) return 0
  return Math.min((tunnelUsed(t) / total) * 100, 100)
}
function tunnelForwardCount(t: UserTunnel) {
  return forwardList.value.filter((f) => f.tunnelId === t.tunnelId).length
}
function tunnelForwardPct(t: UserTunnel) {
  if (t.num === 99999) return 0
  const num = t.num || 0
  if (!num) return 0
  return Math.min((tunnelForwardCount(t) / num) * 100, 100)
}

/* ---------- 到期状态芯片 ---------- */
interface ExpStatus {
  text: string
  color: string
  bg: string
  border: string
}
function getExpStatus(expTime?: string): ExpStatus {
  const green: Omit<ExpStatus, 'text'> = {
    color: '#16a34a',
    bg: 'rgba(34,197,94,0.12)',
    border: 'rgba(34,197,94,0.28)',
  }
  const gray: Omit<ExpStatus, 'text'> = {
    color: 'var(--text-secondary)',
    bg: 'var(--bg-subtle)',
    border: 'var(--border-soft)',
  }
  const red: Omit<ExpStatus, 'text'> = {
    color: '#dc2626',
    bg: 'rgba(239,68,68,0.12)',
    border: 'rgba(239,68,68,0.28)',
  }
  const orange: Omit<ExpStatus, 'text'> = {
    color: '#ea580c',
    bg: 'rgba(249,115,22,0.12)',
    border: 'rgba(249,115,22,0.28)',
  }
  if (!expTime) return { text: '永久', ...green }
  const exp = new Date(expTime)
  if (isNaN(exp.getTime())) return { text: '无效', ...gray }
  const now = new Date()
  if (exp.getTime() <= now.getTime()) return { text: '已过期', ...red }
  const diffDays = Math.ceil((exp.getTime() - now.getTime()) / (1000 * 60 * 60 * 24))
  const text = `${diffDays}天后过期`
  if (diffDays <= 7) return { text, ...red }
  if (diffDays <= 30) return { text, ...orange }
  return { text, ...green }
}

/* ---------- Section B 24 小时流量图 ---------- */
const chartData = computed(() => {
  const now = new Date()
  const list: { time: string; flow: number }[] = []
  for (let i = 23; i >= 0; i--) {
    const d = new Date(now.getTime() - i * 60 * 60 * 1000)
    const label = `${String(d.getHours()).padStart(2, '0')}:00`
    const match = statisticsFlows.value.find((s) => s.time === label)
    list.push({ time: label, flow: match ? match.flow || 0 : 0 })
  }
  return list
})
const chartEmpty = computed(() => statisticsFlows.value.length === 0)

function yFmt(v: number): string {
  if (!v) return '0'
  if (v < 1024) return `${v}B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)}K`
  if (v < 1024 * 1024 * 1024) return `${(v / 1024 / 1024).toFixed(1)}M`
  return `${(v / 1024 / 1024 / 1024).toFixed(1)}G`
}

const chartOption = computed(() => ({
  grid: { left: 8, right: 16, top: 16, bottom: 8, containLabel: true },
  tooltip: {
    trigger: 'axis',
    backgroundColor: 'rgba(15,23,42,0.92)',
    borderWidth: 0,
    padding: [8, 12],
    textStyle: { color: '#f8fafc', fontSize: 12 },
    formatter: (params: any) => {
      const p = Array.isArray(params) ? params[0] : params
      return `时间: ${p.axisValue}<br/>流量: ${formatFlow(Number(p.value) || 0)}`
    },
  },
  xAxis: {
    type: 'category',
    boundaryGap: false,
    data: chartData.value.map((d) => d.time),
    axisTick: { show: false },
    axisLine: { lineStyle: { color: 'rgba(148,163,184,0.3)' } },
    axisLabel: { color: '#94a3b8', fontSize: 12 },
  },
  yAxis: {
    type: 'value',
    splitLine: { lineStyle: { color: 'rgba(148,163,184,0.15)', type: 'dashed' } },
    axisLabel: { color: '#94a3b8', fontSize: 12, formatter: (v: number) => yFmt(v) },
  },
  series: [
    {
      type: 'line',
      smooth: true,
      showSymbol: false,
      data: chartData.value.map((d) => d.flow),
      lineStyle: { color: '#8b5cf6', width: 3 },
      itemStyle: { color: '#8b5cf6' },
      areaStyle: {
        color: {
          type: 'linear',
          x: 0,
          y: 0,
          x2: 0,
          y2: 1,
          colorStops: [
            { offset: 0, color: 'rgba(139,92,246,0.35)' },
            { offset: 1, color: 'rgba(139,92,246,0.02)' },
          ],
        },
      },
      emphasis: { focus: 'series' },
    },
  ],
}))

/* ---------- Section D 分组 ---------- */
const forwardGroups = computed(() => {
  const map = new Map<string, Forward[]>()
  for (const f of forwardList.value) {
    const key = f.tunnelName || '未知隧道'
    if (!map.has(key)) map.set(key, [])
    map.get(key)!.push(f)
  }
  return Array.from(map.entries()).map(([name, items]) => ({ name, items }))
})

function openEntry(f: Forward) {
  if (!hasMultiple(f.inIp)) return
  const addrs = f.inIp
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
    .map((ip) => (ip.includes(':') && !ip.startsWith('[') ? `[${ip}]:${f.inPort}` : `${ip}:${f.inPort}`))
  addrModal.value = { show: true, title: '入口地址', addresses: addrs }
}
function openTarget(f: Forward) {
  if (!hasMultiple(f.remoteAddr)) return
  const addrs = f.remoteAddr
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)
  addrModal.value = { show: true, title: '出口地址', addresses: addrs }
}
</script>

<template>
  <PageContainer :loading="loading" loading-text="正在加载数据...">
    <div class="dash">
      <!-- ===================== Section A：指标卡 ===================== -->
      <section class="stat-grid">
        <!-- 总流量 -->
        <div class="fx-card stat-card">
          <div class="stat-top">
            <span class="stat-icon" style="--tint: #2563eb">
              <NIcon :component="CloudOutline" :size="20" />
            </span>
            <div class="stat-meta">
              <div class="stat-label">总流量</div>
              <div class="stat-value">{{ formatFlow(userInfo.flow, 'gb') }}</div>
            </div>
          </div>
        </div>

        <!-- 已用流量 -->
        <div class="fx-card stat-card">
          <div class="stat-top">
            <span class="stat-icon" style="--tint: #16a34a">
              <NIcon :component="PulseOutline" :size="20" />
            </span>
            <div class="stat-meta">
              <div class="stat-label">已用流量</div>
              <div class="stat-value">{{ formatFlow(usedFlow) }}</div>
            </div>
          </div>
          <div class="bar-wrap">
            <div v-if="flowUnlimited" class="bar-unlimited"></div>
            <NProgress
              v-else
              type="line"
              :percentage="flowPct"
              :show-indicator="false"
              :height="6"
              :border-radius="4"
              :color="barColor(flowPct)"
              rail-color="var(--bg-subtle)"
            />
          </div>
          <div class="bar-foot">
            <span>{{ flowUnlimited ? '无限制' : `${flowPct.toFixed(1)}%` }}</span>
            <span v-if="userInfo.flowResetTime != null" class="foot-reset">
              <NIcon :component="TimeOutline" :size="13" />
              {{ formatResetTime(userInfo.flowResetTime) }}
            </span>
          </div>
        </div>

        <!-- 转发配额 -->
        <div class="fx-card stat-card">
          <div class="stat-top">
            <span class="stat-icon" style="--tint: #8b5cf6">
              <NIcon :component="GitNetworkOutline" :size="20" />
            </span>
            <div class="stat-meta">
              <div class="stat-label">转发配额</div>
              <div class="stat-value">{{ formatNumber(userInfo.num || 0) }}</div>
            </div>
          </div>
        </div>

        <!-- 已用转发 -->
        <div class="fx-card stat-card">
          <div class="stat-top">
            <span class="stat-icon" style="--tint: #f97316">
              <NIcon :component="LayersOutline" :size="20" />
            </span>
            <div class="stat-meta">
              <div class="stat-label">已用转发</div>
              <div class="stat-value">{{ forwardList.length }}</div>
            </div>
          </div>
          <div class="bar-wrap">
            <div v-if="numUnlimited" class="bar-unlimited"></div>
            <NProgress
              v-else
              type="line"
              :percentage="forwardPct"
              :show-indicator="false"
              :height="6"
              :border-radius="4"
              :color="barColor(forwardPct)"
              rail-color="var(--bg-subtle)"
            />
          </div>
          <div class="bar-foot">
            <span>{{ numUnlimited ? '无限制' : `${forwardPct.toFixed(1)}%` }}</span>
          </div>
        </div>
      </section>

      <!-- ===================== Section B：24 小时流量统计 ===================== -->
      <section class="panel">
        <header class="panel-head">
          <h2 class="panel-title">24小时流量统计</h2>
        </header>
        <div v-if="chartEmpty">
          <EmptyState title="暂无流量统计数据" />
        </div>
        <div v-else class="chart-box">
          <VChart :option="chartOption" autoresize />
        </div>
      </section>

      <!-- ===================== Section C：隧道权限（仅普通用户） ===================== -->
      <section v-if="!isAdmin" class="panel">
        <header class="panel-head">
          <h2 class="panel-title">隧道权限</h2>
          <span class="badge">{{ userTunnels.length }}</span>
        </header>
        <div v-if="userTunnels.length === 0">
          <EmptyState title="暂无隧道权限" />
        </div>
        <div v-else class="tunnel-list">
          <div v-for="t in userTunnels" :key="t.id" class="fx-card tunnel-row">
            <div class="tunnel-head">
              <div class="tunnel-name">{{ t.tunnelName }} ID: {{ t.id }}</div>
              <div class="chips">
                <span
                  class="chip"
                  :style="
                    t.tunnelFlow === 1
                      ? { color: '#1d4ed8', background: 'rgba(59,130,246,0.14)', border: '1px solid rgba(59,130,246,0.26)' }
                      : { color: '#ea580c', background: 'rgba(249,115,22,0.12)', border: '1px solid rgba(249,115,22,0.26)' }
                  "
                >
                  {{ t.tunnelFlow === 1 ? '单向计费' : '双向计费' }}
                </span>
                <span
                  class="chip"
                  :style="{
                    color: getExpStatus(t.expTime).color,
                    background: getExpStatus(t.expTime).bg,
                    border: `1px solid ${getExpStatus(t.expTime).border}`,
                  }"
                >
                  {{ getExpStatus(t.expTime).text }}
                </span>
                <span v-if="t.flowResetTime != null" class="reset-text">
                  {{ formatResetTime(t.flowResetTime) }}
                </span>
              </div>
            </div>
            <div class="tunnel-grid">
              <div class="metric">
                <div class="metric-label">流量配额</div>
                <div class="metric-value">{{ formatFlow(t.flow, 'gb') }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">已用流量</div>
                <div class="metric-value">{{ formatFlow(tunnelUsed(t)) }}</div>
                <div class="bar-wrap sm">
                  <div v-if="t.flow === 99999" class="bar-unlimited"></div>
                  <NProgress
                    v-else
                    type="line"
                    :percentage="tunnelFlowPct(t)"
                    :show-indicator="false"
                    :height="5"
                    :border-radius="4"
                    :color="barColor(tunnelFlowPct(t))"
                    rail-color="var(--bg-subtle)"
                  />
                </div>
              </div>
              <div class="metric">
                <div class="metric-label">转发配额</div>
                <div class="metric-value">{{ formatNumber(t.num) }}</div>
              </div>
              <div class="metric">
                <div class="metric-label">已用转发</div>
                <div class="metric-value">{{ tunnelForwardCount(t) }}</div>
                <div class="bar-wrap sm">
                  <div v-if="t.num === 99999" class="bar-unlimited"></div>
                  <NProgress
                    v-else
                    type="line"
                    :percentage="tunnelForwardPct(t)"
                    :show-indicator="false"
                    :height="5"
                    :border-radius="4"
                    :color="barColor(tunnelForwardPct(t))"
                    rail-color="var(--bg-subtle)"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ===================== Section D：转发配置 ===================== -->
      <section class="panel">
        <header class="panel-head">
          <h2 class="panel-title">转发配置</h2>
          <span class="badge">{{ forwardList.length }}</span>
        </header>
        <div v-if="forwardList.length === 0">
          <EmptyState title="暂无转发配置" />
        </div>
        <div v-else class="group-list">
          <div v-for="g in forwardGroups" :key="g.name" class="group">
            <div class="group-head">
              <span class="group-name">{{ g.name }}</span>
              <span class="chip chip-muted">{{ g.items.length }} 个转发</span>
            </div>
            <div class="fx-grid">
              <div v-for="f in g.items" :key="f.id" class="fx-card fwd-card">
                <div class="fwd-name">{{ f.name }}</div>
                <div
                  class="addr code-green"
                  :class="{ clickable: hasMultiple(f.inIp) }"
                  :title="formatInAddress(f.inIp, f.inPort)"
                  @click="openEntry(f)"
                >
                  {{ formatInAddress(f.inIp, f.inPort) }}
                </div>
                <div class="arrow">↓</div>
                <div
                  class="addr code-blue"
                  :class="{ clickable: hasMultiple(f.remoteAddr) }"
                  :title="formatRemoteAddress(f.remoteAddr)"
                  @click="openTarget(f)"
                >
                  {{ formatRemoteAddress(f.remoteAddr) }}
                </div>
                <div class="fwd-foot">
                  <div class="foot-cell">
                    <div class="foot-label">上传</div>
                    <div class="foot-val up">{{ formatFlow(f.inFlow) }}</div>
                  </div>
                  <div class="foot-cell">
                    <div class="foot-label">下载</div>
                    <div class="foot-val down">{{ formatFlow(f.outFlow) }}</div>
                  </div>
                  <div class="foot-cell">
                    <div class="foot-label">计费</div>
                    <div class="foot-val bill">{{ formatFlow((f.inFlow || 0) + (f.outFlow || 0)) }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>

    <AddressModal
      v-model:show="addrModal.show"
      :title="addrModal.title"
      :addresses="addrModal.addresses"
    />
  </PageContainer>
</template>

<style scoped>
.dash {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-bottom: 40px;
}

/* ---------- Section A ---------- */
.stat-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 16px;
}
@media (min-width: 992px) {
  .stat-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
.stat-card {
  padding: 18px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.stat-top {
  display: flex;
  align-items: center;
  gap: 12px;
}
.stat-icon {
  flex: none;
  width: 42px;
  height: 42px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--tint);
  background: color-mix(in srgb, var(--tint) 14%, transparent);
}
.stat-meta {
  min-width: 0;
}
.stat-label {
  font-size: 12px;
  color: var(--text-secondary);
  line-height: 1.4;
}
.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.2;
  word-break: break-all;
}
.bar-wrap {
  width: 100%;
}
.bar-wrap.sm {
  margin-top: 6px;
}
.bar-unlimited {
  height: 6px;
  border-radius: 4px;
  background: linear-gradient(90deg, #2563eb, #8b5cf6);
  opacity: 0.6;
}
.bar-wrap.sm .bar-unlimited {
  height: 5px;
}
.bar-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12px;
  color: var(--text-secondary);
}
.foot-reset {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

/* ---------- 面板通用 ---------- */
.panel {
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: 20px;
}
.panel-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 16px;
}
.panel-title {
  margin: 0;
  font-size: 16px;
  font-weight: 700;
  color: var(--text-primary);
}
.badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  padding: 0 8px;
  border-radius: 11px;
  font-size: 12px;
  font-weight: 600;
  color: var(--brand-500);
  background: color-mix(in srgb, var(--brand-500) 14%, transparent);
}

/* ---------- Section B ---------- */
.chart-box {
  width: 100%;
  height: 300px;
}
.chart-box :deep(canvas) {
  border-radius: 8px;
}
.chart-box > * {
  width: 100%;
  height: 100%;
}

/* ---------- Section C ---------- */
.tunnel-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.tunnel-row {
  padding: 16px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-soft);
}
.tunnel-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}
.tunnel-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  align-items: center;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
  line-height: 1.6;
}
.chip-muted {
  color: var(--text-secondary);
  background: var(--bg-subtle);
  border: 1px solid var(--border-soft);
}
.reset-text {
  font-size: 12px;
  color: var(--text-secondary);
}
.tunnel-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
@media (min-width: 768px) {
  .tunnel-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
.metric {
  min-width: 0;
}
.metric-label {
  font-size: 12px;
  color: var(--text-secondary);
}
.metric-value {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-top: 2px;
  word-break: break-all;
}

/* ---------- Section D ---------- */
.group-list {
  display: flex;
  flex-direction: column;
  gap: 22px;
}
.group-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}
.group-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.fwd-card {
  padding: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow-card);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.fwd-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 2px;
  word-break: break-all;
}
.addr {
  font-family: 'JetBrains Mono', 'SFMono-Regular', ui-monospace, Menlo, Consolas, monospace;
  font-size: 13px;
  padding: 7px 10px;
  border-radius: 8px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.code-green {
  color: #15803d;
  background: rgba(34, 197, 94, 0.12);
}
.code-blue {
  color: #1d4ed8;
  background: rgba(59, 130, 246, 0.12);
}
:global(html.dark) .code-green {
  color: #4ade80;
}
:global(html.dark) .code-blue {
  color: #60a5fa;
}
.clickable {
  cursor: pointer;
  transition: filter 0.16s ease, transform 0.16s ease;
}
.clickable:hover {
  filter: brightness(1.05);
  transform: translateY(-1px);
}
.arrow {
  text-align: center;
  color: var(--text-secondary);
  font-size: 15px;
  line-height: 1;
}
.fwd-foot {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-top: 6px;
  padding-top: 10px;
  border-top: 1px solid var(--border-soft);
}
.foot-cell {
  text-align: center;
  min-width: 0;
}
.foot-label {
  font-size: 11px;
  color: var(--text-secondary);
}
.foot-val {
  font-size: 12px;
  font-weight: 600;
  margin-top: 2px;
  word-break: break-all;
}
.foot-val.up {
  color: #16a34a;
}
.foot-val.down {
  color: #ea580c;
}
.foot-val.bill {
  color: var(--brand-500);
}

@media (max-width: 768px) {
  .panel {
    padding: 16px;
  }
  .chart-box {
    height: 260px;
  }
}
</style>
