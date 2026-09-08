<script setup lang="ts">
import { reactive } from 'vue'
import { useMediaQuery } from '@vueuse/core'
import { Activity, ArrowDown, ArrowDownLeft, ArrowUp, ArrowUpRight, Check, CircleAlert, CirclePause, Copy, Ellipsis, GripVertical, LoaderCircle, Network, Pencil, RefreshCw, Trash2 } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Badge } from '@/components/ui/badge'
import { Switch } from '@/components/ui/switch'
import { Skeleton } from '@/components/ui/skeleton'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table'
import { Tooltip, TooltipContent, TooltipTrigger } from '@/components/ui/tooltip'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { formatFlow, formatInAddress, formatRemoteAddress } from '@/utils/format'
import type { Forward } from '@/types'
import type { ForwardController } from './useForwardManagement'

const props = defineProps<{ controller: ForwardController; items: Forward[]; canReorder: boolean; hasFilters: boolean }>()
const emit = defineEmits<{ (e: 'reset-filters'): void }>()
const vm = reactive(props.controller)
// Match the table's Tailwind breakpoints so hidden controls remain available in the menu.
const switchVisible = useMediaQuery('(min-width: 40rem)')
const entryVisible = useMediaQuery('(min-width: 48rem)')
const dragVisible = useMediaQuery('(min-width: 64rem)')
const targetVisible = useMediaQuery('(min-width: 80rem)')
function indexOf(row: Forward) { return vm.sortedForwards.findIndex(item => item.id === row.id) }
function move(row: Forward, direction: number) {
  const index = indexOf(row)
  if (props.canReorder && index + direction >= 0 && index + direction < vm.sortedForwards.length)
    vm.handleDragEnd(index, index + direction)
}
function groupStarts(row: Forward, index: number) {
  if (vm.viewMode !== 'grouped') return false
  const previous = props.items[index - 1]
  return !previous || previous.userId !== row.userId || previous.tunnelId !== row.tunnelId
}
function syncTone(row: Forward) {
  if (row.syncTaskStatus === 'FAILED') return 'text-red-600 dark:text-red-400'
  if (row.syncTaskStatus) return 'text-amber-600 dark:text-amber-400'
  return 'text-muted-foreground'
}
</script>

<template>
  <div class="overflow-hidden rounded-xl border bg-card">
    <Table class="w-full">
      <TableHeader>
        <TableRow class="h-11 bg-muted/40 hover:bg-muted/40">
          <TableHead class="hidden w-10 pr-0 pl-3 lg:table-cell"><span class="sr-only">排序</span></TableHead>
          <TableHead class="min-w-[120px] pl-4 sm:min-w-40 sm:pl-5 lg:pl-2">名称</TableHead>
          <TableHead class="w-[100px] sm:w-[120px]">状态</TableHead>
          <TableHead class="hidden md:table-cell">入口地址</TableHead>
          <TableHead class="hidden xl:table-cell">目标地址</TableHead>
          <TableHead class="hidden w-[120px] lg:table-cell">已用流量</TableHead>
          <TableHead class="w-12 pr-4 text-right sm:w-[124px] sm:pr-5"><span class="sr-only">操作</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody v-if="vm.loading">
        <TableRow v-for="index in 5" :key="index" class="h-[78px]"><TableCell colspan="7" class="px-5"><div class="flex items-center gap-8"><Skeleton class="h-8 w-40" /><Skeleton class="h-6 w-20" /><Skeleton class="hidden h-6 w-52 sm:block" /></div></TableCell></TableRow>
      </TableBody>
      <TableBody v-else-if="items.length">
        <template v-for="(row, index) in items" :key="row.id">
          <TableRow v-if="groupStarts(row, index)" class="bg-muted/35 hover:bg-muted/35">
            <TableCell colspan="7" class="px-5 py-2 text-xs font-medium text-muted-foreground">{{ row.userName || '当前用户' }} <span class="mx-2 text-border">/</span> {{ row.tunnelName }}</TableCell>
          </TableRow>
          <TableRow class="group h-[78px] transition-colors" :class="{ 'opacity-50': vm.dragIndex === indexOf(row) && canReorder }"
            @dragover.prevent @drop="canReorder && vm.onCardDrop(indexOf(row), $event)">
            <TableCell class="hidden pr-0 pl-3 lg:table-cell">
              <button v-if="canReorder" type="button" class="flex size-6 cursor-grab items-center justify-center rounded text-muted-foreground/40 hover:bg-muted hover:text-muted-foreground active:cursor-grabbing"
                :aria-label="'排序：' + row.name + '，可拖动或按上下方向键移动'" draggable="true" @dragstart="vm.onGripDragStart(indexOf(row), $event)" @dragend="vm.dragIndex = null"
                @keydown.up.prevent="move(row, -1)" @keydown.down.prevent="move(row, 1)"><GripVertical class="size-3.5" /></button>
            </TableCell>
            <TableCell class="max-w-[150px] py-4 pl-4 sm:max-w-[220px] sm:pl-5 lg:pl-2">
              <button class="block max-w-full truncate text-left text-sm font-medium hover:underline underline-offset-4" :disabled="row.deleteRequested" @click="vm.openEdit(row)">{{ row.name }}</button>
              <div class="mt-1.5 flex items-center gap-1.5 overflow-hidden text-xs text-muted-foreground">
                <Network class="size-3 shrink-0" /><span class="truncate">{{ row.tunnelName }}</span><span class="hidden shrink-0 text-[10px] text-muted-foreground/65 sm:inline">· TCP / UDP</span>
              </div>
              <div class="mt-1.5 truncate font-mono text-[11px] text-muted-foreground md:hidden">{{ formatInAddress(row.inIp, row.inPort) }}</div>
            </TableCell>
            <TableCell class="py-4">
              <Badge variant="outline" class="gap-1.5 px-2 py-0.5 font-normal">
                <span class="size-1.5 rounded-full" :class="row.status === 1 ? 'bg-emerald-500' : row.status === -1 ? 'bg-red-500' : 'bg-zinc-400'" />
                {{ row.status === 1 ? '已启用' : vm.getStatusDisplay(row.status).text }}
              </Badge>
              <Tooltip><TooltipTrigger as-child>
                <component :is="row.syncTaskStatus === 'FAILED' ? 'button' : 'span'" class="mt-1.5 flex items-center gap-1 text-[11px]" :class="syncTone(row)" :aria-label="row.name + '：' + vm.getSyncDisplay(row).description"
                  :disabled="vm.retryingForwardId === row.id" @click="row.syncTaskStatus === 'FAILED' && vm.handleRetrySync(row)">
                  <LoaderCircle v-if="row.syncTaskStatus === 'PROCESSING' || vm.retryingForwardId === row.id" class="size-3 animate-spin" />
                  <CircleAlert v-else-if="row.syncTaskStatus === 'FAILED'" class="size-3" />
                  <Check v-else-if="!row.syncTaskStatus && row.syncStatus === 'SYNCED'" class="size-3" />
                  {{ vm.getSyncDisplay(row).text }}
                </component>
              </TooltipTrigger><TooltipContent class="max-w-72">{{ vm.getSyncDisplay(row).description }}<template v-if="row.syncTaskStatus === 'FAILED'"> · 点击重试</template></TooltipContent></Tooltip>
            </TableCell>
            <TableCell class="hidden max-w-[210px] md:table-cell">
              <button class="group/address flex max-w-full items-center gap-2 text-left font-mono text-xs" :aria-label="'复制 ' + row.name + ' 入口地址'" @click="vm.showAddressModal(row.inIp, row.inPort, '入口端口')">
                <span class="truncate">{{ formatInAddress(row.inIp, row.inPort) }}</span><Copy class="size-3 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover/address:opacity-100" />
              </button>
              <div class="mt-1.5 text-[11px] text-muted-foreground">{{ vm.getStrategyDisplay(row.strategy).text }}策略</div>
            </TableCell>
            <TableCell class="hidden max-w-[220px] xl:table-cell">
              <button class="group/address flex max-w-full items-center gap-2 text-left font-mono text-xs" :aria-label="'复制 ' + row.name + ' 目标地址'" @click="vm.showAddressModal(row.remoteAddr, null, '目标地址')">
                <span class="truncate">{{ formatRemoteAddress(row.remoteAddr) }}</span><Copy class="size-3 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover/address:opacity-100" />
              </button>
              <div class="mt-1.5 text-[11px] text-muted-foreground">{{ row.remoteAddr.split(',').filter(Boolean).length }} 个目标地址</div>
            </TableCell>
            <TableCell class="hidden lg:table-cell">
              <div class="flex items-center gap-1.5 text-xs tabular-nums"><ArrowUpRight class="size-3 text-muted-foreground" />{{ formatFlow(row.inFlow) }}</div>
              <div class="mt-1.5 flex items-center gap-1.5 text-xs text-muted-foreground tabular-nums"><ArrowDownLeft class="size-3" />{{ formatFlow(row.outFlow) }}</div>
            </TableCell>
            <TableCell class="pr-4">
              <div class="flex flex-col items-end justify-end gap-1 sm:flex-row sm:items-center sm:gap-2">
                <Switch class="hidden shrink-0 sm:inline-flex" :model-value="row.serviceRunning" :aria-label="(row.serviceRunning ? '暂停 ' : '恢复 ') + row.name"
                  :disabled="vm.togglingIds.has(row.id) || row.deleteRequested || ![0, 1].includes(row.status)" @update:model-value="vm.handleServiceToggle(row)" />
                <Tooltip><TooltipTrigger as-child>
                  <Button variant="ghost" size="icon" class="size-7 shrink-0" :aria-label="'连接诊断：' + row.name" :disabled="row.deleteRequested || vm.diagnosis.loading" @click="vm.handleDiagnose(row)">
                    <LoaderCircle v-if="vm.diagnosis.loading && vm.diagnosis.current?.id === row.id" class="size-4 animate-spin" /><Activity v-else class="size-4" />
                  </Button>
                </TooltipTrigger><TooltipContent>连接诊断</TooltipContent></Tooltip>
                <DropdownMenu>
                  <DropdownMenuTrigger as-child><Button variant="ghost" size="icon" class="size-7" :aria-label="'操作 ' + row.name"><Ellipsis class="size-4" /></Button></DropdownMenuTrigger>
                  <DropdownMenuContent align="end" class="w-44">
                    <DropdownMenuLabel class="max-w-40 truncate text-xs text-muted-foreground">{{ row.name }}</DropdownMenuLabel>
                    <DropdownMenuItem :disabled="row.deleteRequested" @select="vm.openEdit(row)"><Pencil class="size-4" />编辑转发</DropdownMenuItem>
                    <DropdownMenuItem v-if="!switchVisible" :disabled="row.deleteRequested || vm.togglingIds.has(row.id) || ![0, 1].includes(row.status)" @select="vm.handleServiceToggle(row)"><CirclePause class="size-4" />{{ row.serviceRunning ? '暂停服务' : '恢复服务' }}</DropdownMenuItem>
                    <DropdownMenuItem v-if="row.syncTaskStatus === 'FAILED'" @select="vm.handleRetrySync(row)"><RefreshCw class="size-4" />重试同步</DropdownMenuItem>
                    <DropdownMenuSeparator v-if="!targetVisible" />
                    <DropdownMenuItem v-if="!entryVisible" @select="vm.showAddressModal(row.inIp, row.inPort, '入口端口')"><Copy class="size-4" />复制入口地址</DropdownMenuItem>
                    <DropdownMenuItem v-if="!targetVisible" @select="vm.showAddressModal(row.remoteAddr, null, '目标地址')"><Copy class="size-4" />复制目标地址</DropdownMenuItem>
                    <template v-if="canReorder && !dragVisible"><DropdownMenuItem :disabled="indexOf(row) === 0" @select="move(row, -1)"><ArrowUp class="size-4" />上移</DropdownMenuItem><DropdownMenuItem :disabled="indexOf(row) === vm.sortedForwards.length - 1" @select="move(row, 1)"><ArrowDown class="size-4" />下移</DropdownMenuItem></template>
                    <DropdownMenuSeparator />
                    <DropdownMenuItem v-if="row.deleteRequested" class="text-destructive focus:text-destructive" @select="vm.requestForceDelete(row)"><Trash2 class="size-4" />强制删除记录</DropdownMenuItem>
                    <DropdownMenuItem v-else class="text-destructive focus:text-destructive" @select="vm.openDelete(row)"><Trash2 class="size-4" />删除转发</DropdownMenuItem>
                  </DropdownMenuContent>
                </DropdownMenu>
              </div>
            </TableCell>
          </TableRow>
        </template>
      </TableBody>
      <TableBody v-else><TableRow class="hover:bg-transparent"><TableCell colspan="7" class="h-64 text-center">
        <Network class="mx-auto mb-4 size-9 text-muted-foreground/50" />
        <h2 class="text-base font-medium">{{ hasFilters ? '没有匹配的转发' : '从第一条转发开始' }}</h2>
        <p class="mt-2 text-sm text-muted-foreground">{{ hasFilters ? '尝试其他关键词，或清除筛选条件。' : '选择隧道并添加目标地址，即可建立连接。' }}</p>
        <Button class="mt-5" :variant="hasFilters ? 'outline' : 'default'" @click="hasFilters ? emit('reset-filters') : vm.openCreate()">{{ hasFilters ? '清除筛选' : '新建转发' }}</Button>
      </TableCell></TableRow></TableBody>
    </Table>
  </div>
</template>
