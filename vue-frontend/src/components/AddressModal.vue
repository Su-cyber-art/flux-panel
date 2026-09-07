<script setup lang="ts">
import { computed } from 'vue'
import { Copy } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { copyText } from '@/utils/clipboard'
import { useToast } from '@/composables/useToast'
const props = defineProps<{ show: boolean; title: string; addresses: string[] }>()
const emit = defineEmits<{ (e: 'update:show', value: boolean): void }>()
const toast = useToast()
const addresses = computed(() => props.addresses.filter(Boolean))
async function copy(value: string) {
  const result = await copyText(value)
  result ? toast.success('地址已复制') : toast.error('复制失败')
}
</script>
<template>
  <Dialog :open="show" @update:open="value => emit('update:show', value)">
    <DialogContent class="sm:max-w-lg">
      <DialogHeader><DialogTitle>{{ title }}</DialogTitle><DialogDescription>共 {{ addresses.length }} 个地址，点击右侧按钮复制。</DialogDescription></DialogHeader>
      <div class="max-h-[50dvh] divide-y overflow-y-auto rounded-lg border">
        <div v-for="address in addresses" :key="address" class="flex items-center gap-3 px-4 py-3">
          <code class="min-w-0 flex-1 break-all text-xs">{{ address }}</code><Button variant="ghost" size="icon" class="size-8" :aria-label="'复制 ' + address" @click="copy(address)"><Copy class="size-3.5" /></Button>
        </div>
      </div>
      <DialogFooter><Button variant="outline" @click="emit('update:show', false)">关闭</Button><Button @click="copy(addresses.join('\n'))"><Copy class="size-4" />复制全部</Button></DialogFooter>
    </DialogContent>
  </Dialog>
</template>
