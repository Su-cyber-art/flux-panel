<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ChevronLeft, ChevronRight, CircleAlert, CircleCheck, CirclePause, Download, Layers2, Network, Plus, RefreshCw, Search, SlidersHorizontal, Upload, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import ForwardTable from '@/features/forwards/ForwardTable.vue'
import ForwardDialogs from '@/features/forwards/ForwardDialogs.vue'
import { useForwardManagement } from '@/features/forwards/useForwardManagement'
import { useAuthStore } from '@/stores/auth'

const controller = useForwardManagement()
const vm = reactive(controller)
const auth = useAuthStore()
const query = ref('')
const tunnelFilter = ref('all')
const statusFilter = ref('all')
const page = ref(1)
const pageSize = ref(10)
const sourceRows = computed(() => vm.viewMode === 'direct' ? vm.sortedForwards
  : vm.userGroups.flatMap(group => group.tunnelGroups.flatMap(tunnel => tunnel.forwards)))
const rows = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return sourceRows.value.filter(row =>
    (!keyword || [row.name, row.tunnelName, row.inIp, row.inPort, row.remoteAddr, row.userName]
      .some(value => String(value ?? '').toLowerCase().includes(keyword))) &&
    (tunnelFilter.value === 'all' || row.tunnelId === Number(tunnelFilter.value)) &&
    (statusFilter.value === 'all' || row.status === Number(statusFilter.value)))
})
const visibleRows = computed(() => rows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value))
const pages = computed(() => Math.max(1, Math.ceil(rows.value.length / pageSize.value)))
const hasFilters = computed(() => !!query.value || tunnelFilter.value !== 'all' || statusFilter.value !== 'all' || vm.syncFilter !== 'ALL')
const canReorder = computed(() => vm.viewMode === 'direct' && !hasFilters.value)
const ownedRows = computed(() => vm.viewMode === 'direct'
  ? vm.forwards.filter(row => vm.currentUserId == null || row.userId === vm.currentUserId) : vm.forwards)
const stats = computed(() => [
  { label: '全部转发', value: ownedRows.value.length, icon: Layers2, note: '当前视图中的转发规则' },
  { label: '已启用', value: ownedRows.value.filter(row => row.status === 1).length, icon: CircleCheck, note: '已提交启用状态' },
  { label: '已暂停', value: ownedRows.value.filter(row => row.status === 0).length, icon: CirclePause, note: '按需恢复转发服务' },
  { label: '同步异常', value: ownedRows.value.filter(row => row.syncTaskStatus === 'FAILED').length, icon: CircleAlert, note: '等待处理的节点同步' },
])
watch([query, tunnelFilter, statusFilter, pageSize, () => vm.viewMode, () => vm.syncFilter], () => { page.value = 1 })
watch(pages, value => { page.value = Math.min(page.value, value) })
function resetFilters() {
  query.value = ''
  tunnelFilter.value = 'all'
  statusFilter.value = 'all'
  vm.syncFilter = 'ALL'
}
</script>

<template>
  <div class="forward-workspace mx-auto w-full max-w-[1600px] space-y-7 px-5 py-7 sm:px-8 lg:px-10 lg:py-9">
    <div class="flex flex-wrap items-start justify-between gap-5">
      <div>
        <div class="mb-2 flex items-center gap-2 text-xs font-medium text-muted-foreground"><Network class="size-3.5" />网络管理</div>
        <h1 class="text-[28px] leading-tight font-semibold tracking-tight sm:text-3xl">转发管理</h1>
        <p class="mt-2 text-sm text-muted-foreground">连接节点与服务，让每一条转发清晰可控。</p>
      </div>
      <div class="flex items-center gap-2 pt-1">
        <DropdownMenu>
          <DropdownMenuTrigger as-child><Button variant="outline" class="gap-2"><Upload class="size-4" />导入 / 导出</Button></DropdownMenuTrigger>
          <DropdownMenuContent align="end" class="w-44">
            <DropdownMenuItem @select="vm.openImport"><Upload class="size-4" />导入转发</DropdownMenuItem>
            <DropdownMenuItem @select="vm.openExport"><Download class="size-4" />导出转发</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
        <Button class="gap-2" @click="vm.openCreate"><Plus class="size-4" />新建转发</Button>
      </div>
    </div>
    <div class="grid grid-cols-4 overflow-hidden rounded-xl border bg-card">
      <div v-for="(stat, index) in stats" :key="stat.label" class="relative px-3 py-4 sm:px-6 sm:py-5" :class="index ? 'border-l' : ''">
        <div class="flex items-center justify-between gap-3 text-xs text-muted-foreground">
          <span>{{ stat.label }}</span><component :is="stat.icon" class="hidden size-4 sm:block" :class="index === 3 && stat.value ? 'text-red-500' : ''" />
        </div>
        <Skeleton v-if="vm.loading" class="mt-3 h-8 w-14" />
        <div v-else class="mt-2.5 text-2xl leading-none font-semibold tracking-tight tabular-nums sm:text-[29px]">{{ stat.value }}</div>
        <p class="mt-2.5 hidden text-xs text-muted-foreground lg:block">{{ stat.note }}</p>
      </div>
    </div>
    <section class="space-y-4" aria-label="转发规则">
      <div class="flex flex-wrap items-center justify-between gap-3">
        <div class="inline-flex h-9 items-center rounded-lg bg-muted p-1" aria-label="同步状态筛选">
          <button v-for="filter in vm.filterButtons" :key="filter.value" type="button"
            class="h-7 rounded-md px-3 text-xs font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            :class="vm.syncFilter === filter.value ? 'bg-background text-foreground shadow-xs' : 'text-muted-foreground hover:text-foreground'"
            :aria-pressed="vm.syncFilter === filter.value" @click="vm.syncFilter = filter.value">{{ filter.label }}</button>
        </div>
        <Button variant="ghost" size="sm" class="gap-2 text-muted-foreground" @click="vm.toggleViewMode">
          <Layers2 class="size-3.5" /><span class="sm:hidden">{{ vm.viewMode === 'direct' ? '我的' : '分组' }}</span><span class="hidden sm:inline">{{ vm.viewMode === 'direct' ? '我的转发' : (auth.isAdmin ? '按用户分组' : '按隧道分组') }}</span><SlidersHorizontal class="hidden size-3.5 sm:block" />
        </Button>
      </div>
      <div class="flex flex-wrap items-center gap-2.5">
        <div class="relative min-w-48 flex-1 sm:max-w-80">
          <Search class="pointer-events-none absolute top-2.5 left-3 size-4 text-muted-foreground" />
          <Input v-model="query" class="h-9 pl-9" placeholder="搜索名称、地址或端口…" aria-label="搜索转发" />
        </div>
        <Select v-model="tunnelFilter">
          <SelectTrigger class="h-9 w-[148px] sm:w-[160px]" aria-label="筛选隧道"><Network class="mr-1 size-3.5 text-muted-foreground" /><SelectValue placeholder="全部隧道" /></SelectTrigger>
          <SelectContent><SelectItem value="all">全部隧道</SelectItem><SelectItem v-for="tunnel in vm.tunnels" :key="tunnel.id" :value="String(tunnel.id)">{{ tunnel.name }}</SelectItem></SelectContent>
        </Select>
        <Select v-model="statusFilter">
          <SelectTrigger class="h-9 w-[116px] sm:w-[130px]" aria-label="筛选运行状态"><SelectValue placeholder="全部状态" /></SelectTrigger>
          <SelectContent><SelectItem value="all">全部状态</SelectItem><SelectItem value="1">已启用</SelectItem><SelectItem value="0">已暂停</SelectItem><SelectItem value="-2">删除中</SelectItem><SelectItem value="-1">异常</SelectItem></SelectContent>
        </Select>
        <Button v-if="hasFilters" variant="ghost" size="sm" @click="resetFilters"><X class="size-3.5" />清除</Button>
        <div class="ml-auto flex items-center gap-2">
          <span class="hidden text-xs text-muted-foreground sm:inline">{{ rows.length }} 条规则</span>
          <Tooltip><TooltipTrigger as-child>
            <Button variant="outline" size="icon" class="size-9" aria-label="刷新转发列表" :disabled="vm.refreshing" @click="vm.loadData(false)">
              <RefreshCw class="size-4" :class="{ 'animate-spin': vm.refreshing }" />
            </Button>
          </TooltipTrigger><TooltipContent>刷新列表</TooltipContent></Tooltip>
        </div>
      </div>
      <div v-if="vm.loadError" role="alert" class="flex items-center gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-950/30 dark:text-amber-300">
        <CircleAlert class="size-4 shrink-0" /><span class="flex-1">{{ vm.loadError }}<span v-if="vm.forwards.some(row => row.syncTaskStatus)">，正在自动重试。</span></span>
        <Button variant="ghost" size="sm" :disabled="vm.refreshing" @click="vm.loadData(false)">重试</Button>
      </div>
      <ForwardTable :controller="controller" :items="visibleRows" :can-reorder="canReorder" :has-filters="hasFilters" @reset-filters="resetFilters" />
      <div class="flex flex-wrap items-center justify-between gap-3 text-xs text-muted-foreground">
        <span>{{ rows.length ? (page - 1) * pageSize + 1 : 0 }}–{{ Math.min(page * pageSize, rows.length) }} / {{ rows.length }} 条转发</span>
        <div class="flex items-center gap-3">
          <span class="hidden sm:inline">每页</span>
          <Select :model-value="String(pageSize)" @update:model-value="value => pageSize = Number(value)">
            <SelectTrigger class="h-8 w-16" aria-label="每页条数"><SelectValue /></SelectTrigger>
            <SelectContent><SelectItem value="10">10</SelectItem><SelectItem value="20">20</SelectItem><SelectItem value="50">50</SelectItem></SelectContent>
          </Select>
          <span class="min-w-12 text-center tabular-nums">{{ page }} / {{ pages }}</span>
          <div class="flex gap-1.5"><Button variant="outline" size="icon" class="size-8" :disabled="page <= 1" aria-label="上一页" @click="page--"><ChevronLeft class="size-4" /></Button><Button variant="outline" size="icon" class="size-8" :disabled="page >= pages" aria-label="下一页" @click="page++"><ChevronRight class="size-4" /></Button></div>
        </div>
      </div>
    </section>
    <ForwardDialogs :controller="controller" />
  </div>
</template>
