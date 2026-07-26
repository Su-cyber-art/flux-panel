<script setup lang="ts">
defineProps<{ title?: string; description?: string }>()
</script>

<template>
  <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:64px 16px;gap:10px;text-align:center">
    <div style="width:64px;height:64px;border-radius:20px;display:flex;align-items:center;justify-content:center;background:var(--bg-subtle);color:var(--text-secondary)">
      <slot name="icon">
        <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round"><path d="M3 7v10a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2h-6l-2-2H5a2 2 0 0 0-2 2z"/></svg>
      </slot>
    </div>
    <div style="font-size:15px;font-weight:600;color:var(--text-primary)">{{ title || '暂无数据' }}</div>
    <div v-if="description" class="text-secondary" style="font-size:13px;max-width:360px">{{ description }}</div>
    <slot name="action" />
  </div>
</template>
