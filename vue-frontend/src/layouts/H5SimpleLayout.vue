<script setup lang="ts">
import { useRouter } from 'vue-router'
import { NButton, NIcon } from 'naive-ui'
import { ChevronBackOutline } from '@vicons/ionicons5'
import Logo from '@/components/Logo.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { useConfigStore } from '@/stores/config'

const router = useRouter()
const config = useConfigStore()
</script>

<template>
  <div class="h5s-shell">
    <header class="h5s-header safe-top">
      <NButton quaternary circle size="small" @click="router.push('/profile')">
        <template #icon><NIcon :component="ChevronBackOutline" :size="20" /></template>
      </NButton>
      <Logo :size="20" />
      <span class="h5s-title">{{ config.name }}</span>
      <div style="margin-left:auto"><ThemeToggle /></div>
    </header>
    <main class="h5s-content">
      <slot />
    </main>
  </div>
</template>

<style scoped>
.h5s-shell {
  min-height: 100vh;
  background: var(--bg-body);
}
.h5s-header {
  height: 54px;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 12px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border-soft);
  position: sticky;
  top: 0;
  z-index: 20;
}
.h5s-title {
  font-weight: 700;
  font-size: 15px;
}
</style>
