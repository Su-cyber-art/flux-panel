<script setup lang="ts">
import { computed, ref, h } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { NButton, NIcon, NDropdown, NAvatar } from 'naive-ui'
import { MenuOutline, ChevronDownOutline, LogOutOutline, KeyOutline, CloseOutline } from '@vicons/ionicons5'
import Logo from '@/components/Logo.vue'
import ThemeToggle from '@/components/ThemeToggle.vue'
import ChangePasswordModal from '@/components/ChangePasswordModal.vue'
import { menuItems } from '@/config/menu'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { safeLogout } from '@/utils/logout'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()

const drawerOpen = ref(false)
const pwdModal = ref(false)

const visibleMenu = computed(() => menuItems.filter((m) => !m.adminOnly || auth.isAdmin))
const currentTitle = computed(() => (route.meta.title as string) || config.name)

const userMenuOptions = [
  { label: '修改密码', key: 'pwd', icon: () => iconRender(KeyOutline) },
  { label: '退出登录', key: 'logout', icon: () => iconRender(LogOutOutline) },
]

function iconRender(cmp: any) {
  return h(NIcon, { component: cmp })
}

function onUserMenu(key: string) {
  if (key === 'pwd') pwdModal.value = true
  else if (key === 'logout') {
    safeLogout()
    auth.logout()
    router.push('/')
  }
}

function navigate(path: string) {
  drawerOpen.value = false
  if (route.path !== path) router.push(path)
}
</script>

<template>
  <div class="admin-shell">
    <!-- 侧边栏 -->
    <aside class="sidebar" :class="{ open: drawerOpen }">
      <div class="brand">
        <Logo :size="26" />
        <div class="brand-text">
          <span class="brand-name">{{ config.name }}</span>
          <span class="brand-ver">v{{ config.version }}</span>
        </div>
        <NButton class="drawer-close" quaternary circle size="small" @click="drawerOpen = false">
          <template #icon><NIcon :component="CloseOutline" /></template>
        </NButton>
      </div>

      <nav class="menu">
        <a
          v-for="item in visibleMenu"
          :key="item.path"
          class="menu-item"
          :class="{ active: route.path === item.path }"
          @click="navigate(item.path)"
        >
          <NIcon :component="item.icon" :size="19" />
          <span>{{ item.label }}</span>
        </a>
      </nav>

      <div class="side-footer">
        <a href="https://github.com/Su-cyber-art/flux-panel" target="_blank" rel="noopener noreferrer">
          Powered by flux-panel
        </a>
      </div>
    </aside>

    <div v-if="drawerOpen" class="backdrop" @click="drawerOpen = false" />

    <!-- 主区域 -->
    <div class="main">
      <header class="topbar">
        <NButton class="hamburger" quaternary circle @click="drawerOpen = true">
          <template #icon><NIcon :component="MenuOutline" :size="20" /></template>
        </NButton>
        <div class="page-title">{{ currentTitle }}</div>
        <div class="topbar-actions">
          <ThemeToggle />
          <NDropdown trigger="click" :options="userMenuOptions" @select="onUserMenu" placement="bottom-end">
            <div class="user-chip">
              <NAvatar round size="small" :style="{ background: 'linear-gradient(135deg,#3b82f6,#1d4ed8)' }">
                {{ (auth.name || 'A').charAt(0).toUpperCase() }}
              </NAvatar>
              <span class="user-name">{{ auth.name }}</span>
              <NIcon :component="ChevronDownOutline" :size="14" />
            </div>
          </NDropdown>
        </div>
      </header>

      <main class="content">
        <slot />
      </main>
    </div>

    <ChangePasswordModal v-model:show="pwdModal" />
  </div>
</template>

<style scoped>
.admin-shell {
  display: flex;
  min-height: 100vh;
}
.sidebar {
  width: 260px;
  flex-shrink: 0;
  background: var(--bg-elevated);
  border-right: 1px solid var(--border-soft);
  display: flex;
  flex-direction: column;
  position: sticky;
  top: 0;
  height: 100vh;
  z-index: 50;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--border-soft);
}
.brand-text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
}
.brand-name {
  font-weight: 700;
  font-size: 16px;
}
.brand-ver {
  font-size: 11px;
  color: var(--text-secondary);
}
.drawer-close {
  display: none;
  margin-left: auto;
}
.menu {
  flex: 1;
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  overflow-y: auto;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border-radius: 12px;
  color: var(--text-secondary);
  cursor: pointer;
  font-weight: 500;
  transition: background 0.18s ease, color 0.18s ease;
  min-height: 44px;
}
.menu-item:hover {
  background: var(--bg-subtle);
  color: var(--text-primary);
}
.menu-item.active {
  background: linear-gradient(135deg, rgba(37, 99, 235, 0.14), rgba(37, 99, 235, 0.06));
  color: var(--brand-500);
  font-weight: 600;
}
.side-footer {
  padding: 14px 20px;
  border-top: 1px solid var(--border-soft);
  font-size: 12px;
}
.side-footer a {
  color: var(--text-secondary);
  text-decoration: none;
}
.side-footer a:hover {
  color: var(--brand-500);
}
.backdrop {
  display: none;
}
.main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  height: 100vh;
}
.topbar {
  height: 60px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 0 20px;
  background: color-mix(in srgb, var(--bg-elevated) 82%, transparent);
  backdrop-filter: blur(10px);
  border-bottom: 1px solid var(--border-soft);
  position: sticky;
  top: 0;
  z-index: 20;
}
.hamburger {
  display: none;
}
.page-title {
  font-size: 17px;
  font-weight: 600;
}
.topbar-actions {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 6px;
}
.user-chip {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 5px 10px 5px 5px;
  border-radius: 999px;
  cursor: pointer;
  transition: background 0.18s ease;
}
.user-chip:hover {
  background: var(--bg-subtle);
}
.user-name {
  font-size: 14px;
  font-weight: 500;
}
.content {
  flex: 1;
  overflow-y: auto;
  background: var(--bg-body);
}
@media (max-width: 900px) {
  .sidebar {
    position: fixed;
    left: 0;
    transform: translateX(-100%);
    transition: transform 0.3s cubic-bezier(0.16, 1, 0.3, 1);
    box-shadow: 0 0 40px rgba(0, 0, 0, 0.2);
  }
  .sidebar.open {
    transform: translateX(0);
  }
  .drawer-close {
    display: inline-flex;
  }
  .backdrop {
    display: block;
    position: fixed;
    inset: 0;
    background: rgba(2, 6, 23, 0.4);
    backdrop-filter: blur(2px);
    z-index: 40;
  }
  .hamburger {
    display: inline-flex;
  }
  .main {
    height: 100vh;
  }
}
</style>
