<script setup lang="ts">
import { computed } from 'vue'
import { NModal, NButton, NTag } from 'naive-ui'
import { copyText } from '@/utils/clipboard'
import { useToast } from '@/composables/useToast'

const props = defineProps<{
  show: boolean
  title: string
  addresses: string[]
}>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()
const toast = useToast()

const list = computed(() => props.addresses.filter(Boolean))

async function copyOne(addr: string) {
  const ok = await copyText(addr)
  ok ? toast.success('已复制') : toast.error('复制失败')
}
async function copyAll() {
  const ok = await copyText(list.value.join('\n'))
  ok ? toast.success('已复制全部') : toast.error('复制失败')
}
</script>

<template>
  <NModal
    :show="show"
    preset="card"
    :title="`${title} (${list.length}个)`"
    style="width: 520px; max-width: 94vw"
    :bordered="false"
    @update:show="(v: boolean) => emit('update:show', v)"
  >
    <div style="display:flex;justify-content:flex-end;margin-bottom:10px">
      <NButton size="small" type="primary" secondary @click="copyAll">复制全部</NButton>
    </div>
    <div style="display:flex;flex-direction:column;gap:8px;max-height:52vh;overflow-y:auto">
      <div
        v-for="(addr, i) in list"
        :key="i"
        style="display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 12px;background:var(--bg-subtle);border-radius:10px"
      >
        <NTag :bordered="false" class="fx-mono" style="background:transparent">{{ addr }}</NTag>
        <NButton size="tiny" quaternary @click="copyOne(addr)">复制</NButton>
      </div>
    </div>
    <template #footer>
      <div style="display:flex;justify-content:flex-end">
        <NButton @click="emit('update:show', false)">关闭</NButton>
      </div>
    </template>
  </NModal>
</template>
