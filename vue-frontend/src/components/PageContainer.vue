<script setup lang="ts">
defineProps<{ loading?: boolean; loadingText?: string }>()
</script>

<template>
  <div class="fx-page">
    <div v-if="loading" style="display:flex;flex-direction:column;align-items:center;justify-content:center;padding:80px 0;gap:14px">
      <n-spin size="large" />
      <span class="text-secondary">{{ loadingText || '正在加载...' }}</span>
    </div>
    <div v-else class="fx-fade-up">
      <slot />
    </div>
  </div>
</template>
