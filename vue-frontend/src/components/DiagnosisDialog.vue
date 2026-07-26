<script setup lang="ts">
import { computed } from 'vue'
import {
  NModal,
  NButton,
  NSpin,
  NTag,
  NEmpty,
  NAlert,
  NProgress,
} from 'naive-ui'
import type { DiagnosisReport, DiagnosisResultItem } from '@/types'

const props = defineProps<{
  show: boolean
  loading: boolean
  report: DiagnosisReport | null
  title?: string
  subtitle?: string
  typeLabel?: string
}>()

const emit = defineEmits<{
  (e: 'update:show', v: boolean): void
  (e: 'retry'): void
}>()

const categoryMeta: Record<string, { label: string; color: 'default' | 'info' | 'success' | 'warning' }> = {
  LISTENER: { label: '入口监听', color: 'info' },
  HOP: { label: '逐跳建连', color: 'default' },
  TARGET: { label: '目标可达', color: 'default' },
  LOOPBACK: { label: '数据回环', color: 'success' },
}

const results = computed<DiagnosisResultItem[]>(() => props.report?.results ?? [])
const summary = computed(() => {
  const r = results.value
  const passed = r.filter((x) => x.success).length
  return props.report?.summary ?? { total: r.length, passed, failed: r.length - passed }
})

function quality(avg?: number, loss?: number): { text: string; type: 'success' | 'info' | 'warning' | 'error' } | null {
  if (avg == null || loss == null || avg < 0) return null
  if (avg < 30 && loss === 0) return { text: '优秀', type: 'success' }
  if (avg < 50 && loss === 0) return { text: '很好', type: 'success' }
  if (avg < 100 && loss < 1) return { text: '良好', type: 'info' }
  if (avg < 150 && loss < 2) return { text: '一般', type: 'warning' }
  if (avg < 200 && loss < 5) return { text: '较差', type: 'warning' }
  return { text: '很差', type: 'error' }
}

function fmtMs(v?: number): string {
  if (v == null || v < 0) return '-'
  return v.toFixed(v < 10 ? 2 : 0)
}
function fmtBytes(v?: number): string {
  if (!v) return '-'
  if (v < 1024) return `${v} B`
  if (v < 1024 * 1024) return `${(v / 1024).toFixed(1)} KB`
  return `${(v / 1024 / 1024).toFixed(2)} MB`
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    style="width: 680px; max-width: 94vw"
    :title="title || '诊断结果'"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <template #header-extra>
      <NTag v-if="typeLabel" type="primary" size="small" round>{{ typeLabel }}</NTag>
    </template>

    <div v-if="subtitle" class="text-secondary" style="margin: -6px 0 12px; font-size: 13px">
      {{ subtitle }}
    </div>

    <div v-if="loading" style="display:flex;flex-direction:column;align-items:center;gap:14px;padding:52px 0">
      <NSpin size="large" />
      <span class="text-secondary">正在进行真实链路诊断...</span>
      <span class="text-secondary" style="font-size:12px">逐跳建连 · 端到端数据回环校验，请稍候</span>
    </div>

    <template v-else>
      <div
        v-if="results.length"
        style="display:flex;gap:8px;align-items:center;margin-bottom:14px;flex-wrap:wrap"
      >
        <NTag :type="summary.failed === 0 ? 'success' : 'warning'" round size="small">
          通过 {{ summary.passed }} / {{ summary.total }}
        </NTag>
        <NTag v-if="summary.failed > 0" type="error" round size="small">失败 {{ summary.failed }}</NTag>
        <div style="flex:1;min-width:120px">
          <NProgress
            type="line"
            :height="6"
            :show-indicator="false"
            :percentage="summary.total ? Math.round((summary.passed / summary.total) * 100) : 0"
            :status="summary.failed === 0 ? 'success' : 'warning'"
          />
        </div>
      </div>

      <div style="display:flex;flex-direction:column;gap:12px;max-height:56vh;overflow-y:auto;padding-right:4px">
        <section
          v-for="(r, i) in results"
          :key="i"
          class="diagnosis-item"
          :style="{
            borderColor: r.success ? 'rgba(24,160,88,.4)' : 'rgba(224,65,65,.45)',
          }"
        >
          <div style="display:flex;justify-content:space-between;align-items:flex-start;gap:12px">
            <div style="min-width:0">
              <div style="display:flex;align-items:center;gap:8px;flex-wrap:wrap">
                <NTag
                  v-if="r.category && categoryMeta[r.category]"
                  size="tiny"
                  :type="categoryMeta[r.category].color"
                  round
                >
                  {{ categoryMeta[r.category].label }}
                </NTag>
                <span style="font-weight:600">{{ r.description }}</span>
              </div>
              <div class="text-secondary" style="font-size:12px;margin-top:3px">
                节点: {{ r.nodeName || '-' }}
                <template v-if="r.targetIp">
                  · 目标: <span class="fx-mono">{{ r.targetIp }}{{ r.targetPort ? ':' + r.targetPort : '' }}</span>
                </template>
              </div>
            </div>
            <NTag :type="r.success ? 'success' : 'error'" size="small" round>
              {{ r.success ? '通过' : '失败' }}
            </NTag>
          </div>

          <div
            v-if="r.success"
            class="metric-grid"
          >
            <div class="fx-metric">
              <div class="fx-metric-v">{{ fmtMs(r.averageTime) }}</div>
              <div class="fx-metric-l">平均延迟(ms)</div>
            </div>
            <div class="fx-metric">
              <div class="fx-metric-v">{{ fmtMs(r.jitter) }}</div>
              <div class="fx-metric-l">抖动(ms)</div>
            </div>
            <div class="fx-metric">
              <div class="fx-metric-v">{{ (r.packetLoss ?? 0).toFixed(1) }}</div>
              <div class="fx-metric-l">丢包率(%)</div>
            </div>
            <div class="fx-metric">
              <NTag v-if="quality(r.averageTime, r.packetLoss)" :type="quality(r.averageTime, r.packetLoss)!.type" size="small" round>
                {{ quality(r.averageTime, r.packetLoss)!.text }}
              </NTag>
              <span v-else class="fx-metric-v">-</span>
              <div class="fx-metric-l">链路质量</div>
            </div>
          </div>

          <div
            v-if="r.success && r.category === 'LOOPBACK'"
            style="display:flex;gap:8px;flex-wrap:wrap;margin-top:10px"
          >
            <NTag size="small" :type="r.integrityOk ? 'success' : 'error'" round>
              {{ r.integrityOk ? '数据完整' : '数据异常' }}
            </NTag>
            <NTag size="small" type="info" round>校验 {{ fmtBytes(r.bytesVerified) }}</NTag>
            <NTag size="small" round>往返 {{ r.okRounds ?? 0 }}/{{ r.rounds ?? 0 }} 轮</NTag>
          </div>

          <NAlert
            v-if="!r.success"
            type="error"
            :bordered="false"
            style="margin-top:10px"
            title="错误详情"
          >
            {{ r.message || '连接失败' }}
          </NAlert>
        </section>
      </div>

      <NEmpty v-if="!results.length" description="暂无诊断数据" style="padding:40px 0" />
    </template>

    <template #footer>
      <div style="display:flex;justify-content:flex-end;gap:10px">
        <NButton @click="emit('update:show', false)">关闭</NButton>
        <NButton type="primary" :loading="loading" @click="emit('retry')">重新诊断</NButton>
      </div>
    </template>
  </NModal>
</template>

<style scoped>
.diagnosis-item {
  padding: 12px;
  border: 1px solid;
  border-left-width: 3px;
  border-radius: 8px;
  background: var(--bg-elevated);
}
.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  margin-top: 12px;
}
.fx-metric {
  text-align: center;
  padding: 8px 4px;
  background: var(--bg-subtle);
  border-radius: 8px;
}
.fx-metric-v {
  font-size: 20px;
  font-weight: 700;
  color: var(--brand-500);
  line-height: 1.2;
}
.fx-metric-l {
  font-size: 11px;
  color: var(--text-secondary);
  margin-top: 2px;
}
@media (max-width: 520px) {
  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
