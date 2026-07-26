<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NIcon } from 'naive-ui'
import Logo from '@/components/Logo.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import { h5Tabs } from '@/config/menu'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()

const tabs = computed(() => h5Tabs.filter((t) => !t.adminOnly || auth.isAdmin))

function go(path: string) {
  if (route.path !== path) router.push(path)
}
</script>

<template>
  <div class="h5-shell">
    <header class="h5-header safe-top">
      <Logo :size="22" />
      <span class="h5-title">{{ config.name }}</span>
      <div style="margin-left:auto"><ThemeToggle /></div>
    </header>

    <main class="h5-content">
      <slot />
    </main>

    <div class="h5-spacer safe-bottom" />
    <nav class="h5-tabbar safe-bottom">
      <a
        v-for="tab in tabs"
        :key="tab.path"
        class="h5-tab"
        :class="{ active: route.path === tab.path }"
        @click="go(tab.path)"
      >
        <NIcon :component="tab.icon" :size="21" />
        <span>{{ tab.label }}</span>
      </a>
    </nav>
  </div>
</template>

<style scoped>
.h5-shell {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: var(--bg-body);
}
.h5-header {
  height: 54px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 16px;
  background: var(--bg-elevated);
  border-bottom: 1px solid var(--border-soft);
  position: sticky;
  top: 0;
  z-index: 20;
}
.h5-title {
  font-weight: 700;
  font-size: 16px;
}
.h5-content {
  flex: 1;
}
.h5-spacer {
  height: 64px;
}
.h5-tabbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  height: 64px;
  display: flex;
  background: color-mix(in srgb, var(--bg-elevated) 92%, transparent);
  backdrop-filter: blur(12px);
  border-top: 1px solid var(--border-soft);
  z-index: 30;
}
.h5-tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  font-size: 11px;
  color: var(--text-secondary);
  cursor: pointer;
  transition: color 0.18s ease;
}
.h5-tab.active {
  color: var(--brand-500);
}
</style>
