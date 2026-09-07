<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { AlertTriangle, Check, ChevronDown, Copy, Download, LoaderCircle, Network, Plus, Upload, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select'
import { Sheet, SheetContent, SheetDescription, SheetFooter, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { AlertDialog, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog'
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from '@/components/ui/collapsible'
import AddressModal from '@/components/AddressModal.vue'
import DiagnosisDialog from '@/components/DiagnosisDialog.vue'
import type { ForwardController } from './useForwardManagement'

const props = defineProps<{ controller: ForwardController }>()
const vm = reactive(props.controller)
const advanced = ref(false)
watch(() => vm.showFormModal, open => { if (open) advanced.value = !!vm.form.interfaceName || vm.showStrategy })
watch(() => vm.showStrategy, show => { if (show) advanced.value = true })
</script>

<template>
  <Sheet :open="vm.showFormModal" @update:open="value => value ? vm.showFormModal = true : vm.closeFormModal()">
    <SheetContent class="flex flex-col gap-0 p-0 data-[side=right]:w-full data-[side=right]:sm:max-w-[480px]">
      <SheetHeader class="border-b px-6 py-6">
        <div class="mb-2 flex size-10 items-center justify-center rounded-xl border bg-muted/40"><Network class="size-5" /></div>
        <SheetTitle class="text-xl">{{ vm.isEdit ? '编辑转发' : '新建转发' }}</SheetTitle>
        <SheetDescription>设置入口和目标地址，通过隧道连接你的服务。</SheetDescription>
      </SheetHeader>
      <form class="flex min-h-0 flex-1 flex-col" @submit.prevent="vm.handleSubmit">
        <div class="min-h-0 flex-1 space-y-6 overflow-y-auto p-6">
          <div class="space-y-2">
            <label class="field-label" for="forward-name">转发名称 <span class="text-destructive">*</span></label>
            <Input id="forward-name" v-model="vm.form.name" placeholder="例如：生产环境 · Web 服务" :aria-invalid="!!vm.formErrors.name" autocomplete="off" />
            <p v-if="vm.formErrors.name" class="field-error" role="alert">{{ vm.formErrors.name }}</p>
          </div>
          <div class="space-y-2">
            <label class="field-label" for="forward-tunnel">关联隧道 <span class="text-destructive">*</span></label>
            <Select :model-value="vm.form.tunnelId == null ? undefined : String(vm.form.tunnelId)" @update:model-value="value => vm.onTunnelChange(Number(value))">
              <SelectTrigger id="forward-tunnel" class="w-full" :aria-invalid="!!vm.formErrors.tunnelId"><SelectValue placeholder="选择一个可用隧道" /></SelectTrigger>
              <SelectContent><SelectItem v-for="tunnel in vm.tunnels" :key="tunnel.id" :value="String(tunnel.id)">{{ tunnel.name }}</SelectItem><div v-if="!vm.tunnels.length" class="p-3 text-sm text-muted-foreground">暂无可用隧道</div></SelectContent>
            </Select>
            <p v-if="vm.formErrors.tunnelId" class="field-error" role="alert">{{ vm.formErrors.tunnelId }}</p>
          </div>
          <div class="space-y-2">
            <label class="field-label" for="forward-port">入口端口 <span class="ml-1 text-xs font-normal text-muted-foreground">可选</span></label>
            <Input id="forward-port" type="number" min="1" max="65535" step="1" :model-value="vm.form.inPort ?? ''"
              @update:model-value="value => vm.form.inPort = value === '' ? null : Number(value)" placeholder="自动分配可用端口"
              :aria-invalid="vm.portDescription.tone === 'err' || !!vm.formErrors.inPort" />
            <p class="flex items-center gap-1.5 text-xs" :class="vm.portDescription.tone === 'err' ? 'text-destructive' : vm.portDescription.tone === 'ok' ? 'text-emerald-600 dark:text-emerald-400' : 'text-muted-foreground'">
              <LoaderCircle v-if="vm.portDescription.tone === 'checking'" class="size-3 animate-spin" /><Check v-if="vm.portDescription.tone === 'ok'" class="size-3" />{{ vm.portDescription.text }}
            </p>
            <p v-if="vm.formErrors.inPort" class="field-error" role="alert">{{ vm.formErrors.inPort }}</p>
          </div>
          <div class="space-y-2">
            <label class="field-label" for="forward-target">目标地址 <span class="text-destructive">*</span></label>
            <Textarea id="forward-target" v-model="vm.form.remoteAddr" class="min-h-28 font-mono text-sm" placeholder="192.168.1.100:8080&#10;example.com:3000" :aria-invalid="!!vm.formErrors.remoteAddr" />
            <p class="text-xs leading-relaxed text-muted-foreground">填写 IP 或域名及端口，多个目标每行填写一个。</p>
            <p v-if="vm.formErrors.remoteAddr" class="field-error" role="alert">{{ vm.formErrors.remoteAddr }}</p>
          </div>
          <Collapsible v-model:open="advanced" class="border-t pt-4">
            <CollapsibleTrigger as-child><Button type="button" variant="ghost" class="-ml-3 gap-2 text-sm"><ChevronDown class="size-4 transition-transform" :class="{ 'rotate-180': advanced }" />高级设置</Button></CollapsibleTrigger>
            <CollapsibleContent class="space-y-5 pt-4">
              <div v-if="vm.showStrategy" class="space-y-2">
                <label class="field-label" for="forward-strategy">负载策略</label>
                <Select v-model="vm.form.strategy"><SelectTrigger id="forward-strategy" class="w-full"><SelectValue /></SelectTrigger><SelectContent><SelectItem v-for="strategy in vm.strategyOptions" :key="strategy.value" :value="strategy.value">{{ strategy.label }}</SelectItem></SelectContent></Select>
              </div>
              <div class="space-y-2">
                <label class="field-label" for="forward-interface">出口网卡 / IP</label>
                <Input id="forward-interface" v-model="vm.form.interfaceName" placeholder="例如：eth0，留空使用默认出口" />
                <p class="text-xs text-muted-foreground">多出口服务器可指定连接目标时使用的网卡或 IP。</p>
              </div>
            </CollapsibleContent>
          </Collapsible>
        </div>
        <SheetFooter class="flex-row items-center justify-end gap-2 border-t bg-background px-6 py-4">
          <Button type="button" variant="outline" @click="vm.closeFormModal">取消</Button>
          <Button type="submit" class="min-w-28" :disabled="vm.submitLoading || vm.isPortSubmissionBlocked">
            <LoaderCircle v-if="vm.submitLoading" class="size-4 animate-spin" /><Plus v-else-if="!vm.isEdit" class="size-4" />{{ vm.isEdit ? '保存修改' : '创建转发' }}
          </Button>
        </SheetFooter>
      </form>
    </SheetContent>
  </Sheet>

  <AlertDialog v-model:open="vm.showDelete">
    <AlertDialogContent class="sm:max-w-md">
      <AlertDialogHeader><AlertDialogTitle>删除这条转发？</AlertDialogTitle><AlertDialogDescription>「{{ vm.forwardToDelete?.name }}」将停止提供转发服务。节点配置删除完成后，这条记录会从列表中移除。</AlertDialogDescription></AlertDialogHeader>
      <AlertDialogFooter><AlertDialogCancel :disabled="vm.deleting">取消</AlertDialogCancel><Button variant="destructive" :disabled="vm.deleting" @click="vm.confirmDelete"><LoaderCircle v-if="vm.deleting" class="size-4 animate-spin" />确认删除</Button></AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
  <AlertDialog v-model:open="vm.showForceDelete">
    <AlertDialogContent class="sm:max-w-md">
      <AlertDialogHeader><AlertDialogTitle>强制删除转发记录？</AlertDialogTitle><AlertDialogDescription>{{ vm.forceDeleteReason }}。强制删除仅清理面板记录，节点上可能仍有转发服务，需要另行清理。</AlertDialogDescription></AlertDialogHeader>
      <AlertDialogFooter><AlertDialogCancel :disabled="vm.deleting">保留记录</AlertDialogCancel><Button variant="destructive" :disabled="vm.deleting" @click="vm.confirmForceDelete"><LoaderCircle v-if="vm.deleting" class="size-4 animate-spin" />仍要强制删除</Button></AlertDialogFooter>
    </AlertDialogContent>
  </AlertDialog>
  <Dialog v-model:open="vm.showPortWarning"><DialogContent class="sm:max-w-md"><DialogHeader><DialogTitle>入口端口不可用</DialogTitle><DialogDescription>{{ vm.portWarningMessage }}</DialogDescription></DialogHeader><DialogFooter><Button @click="vm.showPortWarning = false">返回修改</Button></DialogFooter></DialogContent></Dialog>

  <Dialog :open="vm.showExport" @update:open="value => value ? vm.showExport = true : vm.closeExport()">
    <DialogContent class="sm:max-w-xl">
      <DialogHeader><DialogTitle>导出转发</DialogTitle><DialogDescription>按选定隧道导出规则，便于迁移或备份。</DialogDescription></DialogHeader>
      <div class="space-y-4 py-2">
        <Select :model-value="vm.exportTunnelId == null ? undefined : String(vm.exportTunnelId)" @update:model-value="value => vm.exportTunnelId = Number(value)">
          <SelectTrigger class="w-full" aria-label="导出隧道"><SelectValue placeholder="选择要导出的隧道" /></SelectTrigger>
          <SelectContent><SelectItem v-for="tunnel in vm.tunnels" :key="tunnel.id" :value="String(tunnel.id)">{{ tunnel.name }}</SelectItem></SelectContent>
        </Select>
        <p class="text-xs text-muted-foreground">每行格式：目标地址 | 转发名称 | 入口端口</p>
        <Textarea v-if="vm.exportData" :model-value="vm.exportData" readonly class="min-h-48 font-mono text-xs" aria-label="导出的转发数据" />
      </div>
      <DialogFooter><Button variant="outline" @click="vm.closeExport">关闭</Button><Button v-if="vm.exportData" variant="outline" @click="vm.copyExport"><Copy class="size-4" />复制数据</Button><Button :disabled="!vm.exportTunnelId" @click="vm.executeExport"><Download class="size-4" />{{ vm.exportData ? '重新生成' : '生成数据' }}</Button></DialogFooter>
    </DialogContent>
  </Dialog>
  <Dialog :open="vm.showImport" @update:open="value => { if (!vm.importLoading) vm.showImport = value }">
    <DialogContent class="max-h-[90dvh] overflow-y-auto sm:max-w-xl">
      <DialogHeader><DialogTitle>导入转发</DialogTitle><DialogDescription>粘贴已有规则，批量创建到指定隧道。</DialogDescription></DialogHeader>
      <div class="space-y-4 py-2">
        <Select :model-value="vm.importTunnelId == null ? undefined : String(vm.importTunnelId)" :disabled="vm.importLoading" @update:model-value="value => vm.importTunnelId = Number(value)">
          <SelectTrigger class="w-full" aria-label="导入隧道"><SelectValue placeholder="选择目标隧道" /></SelectTrigger>
          <SelectContent><SelectItem v-for="tunnel in vm.tunnels" :key="tunnel.id" :value="String(tunnel.id)">{{ tunnel.name }}</SelectItem></SelectContent>
        </Select>
        <Textarea v-model="vm.importData" :disabled="vm.importLoading" class="min-h-44 font-mono text-xs" aria-label="导入的转发数据" placeholder="example.com:8080|Web 服务|10001&#10;192.168.1.10:22|SSH 服务|" />
        <p class="text-xs leading-relaxed text-muted-foreground">每行填写「目标地址|转发名称|入口端口」。入口端口留空则自动分配，多个目标用逗号分隔。</p>
        <div v-if="vm.importResults.length" class="overflow-hidden rounded-lg border">
          <div class="flex justify-between border-b bg-muted/40 px-3 py-2 text-xs"><span>导入结果</span><span class="text-muted-foreground">{{ vm.importSuccessCount }} / {{ vm.importResults.length }} 成功</span></div>
          <div class="max-h-40 overflow-y-auto"><div v-for="(result, index) in vm.importResults" :key="index" class="flex items-start gap-2 border-b px-3 py-2 text-xs last:border-0">
            <Check v-if="result.success" class="mt-0.5 size-3 shrink-0 text-emerald-600" /><X v-else class="mt-0.5 size-3 shrink-0 text-destructive" />
            <span class="min-w-0 flex-1"><span class="block truncate font-mono">{{ result.line }}</span><span class="mt-1 block text-muted-foreground">{{ result.message }}</span></span>
          </div></div>
        </div>
      </div>
      <DialogFooter><Button variant="outline" :disabled="vm.importLoading" @click="vm.showImport = false">关闭</Button><Button :disabled="vm.importLoading || !vm.importTunnelId || !vm.importData.trim()" @click="vm.executeImport"><LoaderCircle v-if="vm.importLoading" class="size-4 animate-spin" /><Upload v-else class="size-4" />开始导入</Button></DialogFooter>
    </DialogContent>
  </Dialog>

  <AddressModal v-model:show="vm.addressModal.show" :title="vm.addressModal.title" :addresses="vm.addressModal.addresses" />
  <DiagnosisDialog v-model:show="vm.diagnosis.show" :loading="vm.diagnosis.loading" :report="vm.diagnosis.report" title="连接诊断" :subtitle="vm.diagnosis.current?.name" type-label="转发服务" @retry="vm.onDiagnoseRetry" />
</template>
