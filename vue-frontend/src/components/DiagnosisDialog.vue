<script setup lang="ts">
import { computed } from 'vue'
import { Activity, Check, CircleAlert, CircleCheck, Info, LoaderCircle, RefreshCw, X } from '@lucide/vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import type { DiagnosisReport } from '@/types'
const props = defineProps<{ show: boolean; loading: boolean; report: DiagnosisReport | null; title?: string; subtitle?: string; typeLabel?: string }>()
const emit = defineEmits<{ (e: 'update:show', value: boolean): void; (e: 'retry'): void }>()
const results = computed(() => props.report?.results ?? [])
const passed = computed(() => results.value.filter(result => result.success).length)
const categories: Record<string, string> = { LISTENER: '入口监听', HOP: '逐跳建连', TARGET: '目标可达', LOOPBACK: '数据回环' }
function milliseconds(value?: number) { return value == null || value < 0 ? '—' : value.toFixed(value < 10 ? 2 : 1) }
</script>
<template>
  <Dialog :open="show" @update:open="value => emit('update:show', value)">
    <DialogContent class="flex max-h-[90dvh] flex-col gap-0 overflow-hidden p-0 sm:max-w-[640px]">
      <DialogHeader class="shrink-0 border-b p-6"><DialogTitle class="flex items-center gap-2"><Activity class="size-4" />{{ title || '连接诊断' }}</DialogTitle><DialogDescription>{{ subtitle || typeLabel || '检查节点连接与数据回环' }}</DialogDescription></DialogHeader>
      <div v-if="loading" class="flex min-h-0 flex-col items-center gap-3 overflow-y-auto px-6 py-16"><LoaderCircle class="size-6 animate-spin text-muted-foreground" /><p class="text-sm">正在检查连接…</p><p class="text-xs text-muted-foreground">逐跳建连与回环校验可能需要一些时间。</p></div>
      <div v-else class="min-h-0 overflow-y-auto px-6 py-4">
        <div class="mb-4 flex items-start gap-2 rounded-lg bg-muted/60 px-3 py-2.5 text-xs leading-relaxed text-muted-foreground"><Info class="mt-0.5 size-3.5 shrink-0" />当前检查使用 TCP，不能据此确认 UDP 是否畅通。</div>
        <div v-if="report?.truncated" class="mb-4 rounded-lg border border-amber-300/50 px-3 py-2.5 text-xs text-amber-600">诊断达到时间上限，部分检查未执行，可稍后重试。</div>
        <div v-if="results.length" class="mb-1 flex items-center justify-between py-1"><span class="text-sm font-medium">检查结果</span><Badge variant="outline">{{ passed }} / {{ results.length }} 通过</Badge></div>
        <div v-for="(result, index) in results" :key="index" class="border-b py-4 last:border-0">
          <div class="flex items-start gap-3">
            <CircleCheck v-if="result.success" class="mt-0.5 size-[18px] shrink-0 text-emerald-600 dark:text-emerald-400" /><CircleAlert v-else class="mt-0.5 size-[18px] shrink-0 text-destructive" />
            <div class="min-w-0 flex-1">
              <div class="flex flex-wrap items-center gap-2"><span class="text-sm font-medium">{{ result.description }}</span><span class="text-[11px] text-muted-foreground">{{ categories[result.category || ''] || result.category }}</span></div>
              <p class="mt-1.5 break-all font-mono text-[11px] text-muted-foreground">{{ result.nodeName || '节点' }}<template v-if="result.targetIp"> · {{ result.targetIp }}<template v-if="result.targetPort">:{{ result.targetPort }}</template></template></p>
              <p v-if="!result.success" class="mt-2 text-xs text-destructive">{{ result.message || '检查未通过' }}</p>
              <div v-else class="mt-3 grid grid-cols-3 gap-4">
                <div><div class="text-sm font-medium tabular-nums">{{ milliseconds(result.averageTime) }} <span class="text-[10px] font-normal text-muted-foreground">ms</span></div><span class="text-[11px] text-muted-foreground">平均延迟</span></div>
                <div><div class="text-sm font-medium tabular-nums">{{ milliseconds(result.jitter) }} <span class="text-[10px] font-normal text-muted-foreground">ms</span></div><span class="text-[11px] text-muted-foreground">抖动</span></div>
                <div><div class="text-sm font-medium tabular-nums">{{ result.packetLoss == null ? '—' : result.packetLoss.toFixed(1) }}<span class="text-[10px] font-normal text-muted-foreground"> %</span></div><span class="text-[11px] text-muted-foreground">探测丢包</span></div>
              </div>
              <p v-if="result.success && result.category === 'LOOPBACK'" class="mt-3 flex items-center gap-1 text-[11px] text-muted-foreground"><Check class="size-3" />{{ result.integrityOk ? '数据完整' : '完整性检查未通过' }} · {{ result.bytesVerified || 0 }} 字节 · {{ result.okRounds || 0 }}/{{ result.rounds || 0 }} 轮</p>
            </div>
          </div>
        </div>
        <p v-if="!results.length" class="py-12 text-center text-sm text-muted-foreground">暂无诊断结果。</p>
      </div>
      <DialogFooter class="shrink-0 border-t px-6 py-4"><Button variant="outline" @click="emit('update:show', false)">关闭</Button><Button :disabled="loading" @click="emit('retry')"><RefreshCw class="size-4" :class="{ 'animate-spin': loading }" />重新检查</Button></DialogFooter>
    </DialogContent>
  </Dialog>
</template>
