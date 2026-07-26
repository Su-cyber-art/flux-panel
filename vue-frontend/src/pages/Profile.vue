<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NIcon, NTag } from 'naive-ui'
import type { Component } from 'vue'
import {
  PersonOutline,
  SpeedometerOutline,
  PeopleOutline,
  SettingsOutline,
  LockClosedOutline,
  LogOutOutline,
  LogoGithub,
} from '@vicons/ionicons5'
import PageContainer from '@/components/PageContainer.vue'
import ChangePasswordModal from '@/components/ChangePasswordModal.vue'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { safeLogout } from '@/utils/logout'
import { isWebViewFunc } from '@/utils/panel'

const router = useRouter()
const auth = useAuthStore()
const config = useConfigStore()

const pwdModal = ref(false)

const username = computed(() => auth.name || 'Admin')
const isAdmin = computed(() => auth.isAdmin)
const joinDate = new Date().toLocaleDateString('zh-CN')

interface Tile {
  key: string
  label: string
  color: string
  icon: Component
  onClick: () => void
}

const version = computed(() => (isWebViewFunc() ? config.appVersion : config.version))

const tiles = computed<Tile[]>(() => {
  const list: Tile[] = []
  if (isAdmin.value) {
    list.push(
      { key: 'limit', label: '限速管理', color: '#f59e0b', icon: SpeedometerOutline, onClick: () => router.push('/limit') },
      { key: 'user', label: '用户管理', color: '#2563eb', icon: PeopleOutline, onClick: () => router.push('/user') },
      { key: 'config', label: '网站配置', color: '#8b5cf6', icon: SettingsOutline, onClick: () => router.push('/config') },
    )
  }
  list.push(
    { key: 'password', label: '修改密码', color: '#2563eb', icon: LockClosedOutline, onClick: () => (pwdModal.value = true) },
    { key: 'logout', label: '退出登录', color: '#e04141', icon: LogOutOutline, onClick: handleLogout },
  )
  return list
})

function handleLogout() {
  safeLogout()
  router.push('/')
}
</script>

<template>
  <PageContainer>
    <div class="profile">
      <!-- 用户卡片 -->
      <section class="user-card">
        <div class="avatar">
          <NIcon :component="PersonOutline" :size="34" />
        </div>
        <div class="user-meta">
          <div class="user-name">{{ username }}</div>
          <div class="user-tags">
            <NTag :type="isAdmin ? 'primary' : 'info'" round size="small" :bordered="false">
              {{ isAdmin ? '管理员' : '普通用户' }}
            </NTag>
            <span class="join-date">{{ joinDate }}</span>
          </div>
        </div>
      </section>

      <!-- 功能网格 -->
      <section class="tile-grid">
        <button
          v-for="tile in tiles"
          :key="tile.key"
          type="button"
          class="tile"
          :style="{ '--tint': tile.color }"
          @click="tile.onClick"
        >
          <span class="tile-icon">
            <NIcon :component="tile.icon" :size="24" />
          </span>
          <span class="tile-label">{{ tile.label }}</span>
        </button>
      </section>

      <!-- 页脚 -->
      <footer class="profile-footer">
        <a
          class="footer-link"
          href="https://github.com/bqlpfy/flux-panel"
          target="_blank"
          rel="noreferrer"
        >
          <NIcon :component="LogoGithub" :size="15" />
          <span>Powered by flux-panel</span>
        </a>
        <span class="footer-version">v{{ version }}</span>
      </footer>
    </div>

    <ChangePasswordModal v-model:show="pwdModal" />
  </PageContainer>
</template>

<style scoped>
.profile {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding-bottom: 120px;
}

/* 用户卡片 */
.user-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 22px;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow-card);
}
.avatar {
  flex: none;
  width: 62px;
  height: 62px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.32);
}
.user-meta {
  min-width: 0;
}
.user-name {
  font-size: 19px;
  font-weight: 700;
  color: var(--text-primary);
  line-height: 1.3;
}
.user-tags {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 7px;
}
.join-date {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 功能网格 */
.tile-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
}
.tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 20px 8px;
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow-card);
  cursor: pointer;
  transition: transform 0.18s ease, box-shadow 0.18s ease, border-color 0.18s ease;
  font-family: inherit;
}
.tile:hover {
  transform: translateY(-3px);
  box-shadow: var(--shadow-hover);
  border-color: color-mix(in srgb, var(--tint) 45%, transparent);
}
.tile:active {
  transform: translateY(-1px);
}
.tile-icon {
  width: 48px;
  height: 48px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--tint);
  background: color-mix(in srgb, var(--tint) 14%, transparent);
  transition: background 0.18s ease, transform 0.18s ease;
}
.tile:hover .tile-icon {
  background: color-mix(in srgb, var(--tint) 22%, transparent);
  transform: scale(1.06);
}
.tile-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

/* 页脚 */
.profile-footer {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 84px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  pointer-events: none;
}
.footer-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--text-secondary);
  text-decoration: none;
  pointer-events: auto;
  transition: color 0.18s ease;
}
.footer-link:hover {
  color: var(--brand-500);
}
.footer-version {
  font-size: 11px;
  color: var(--text-secondary);
  opacity: 0.72;
}

@media (max-width: 768px) {
  .user-card {
    padding: 18px;
  }
  .avatar {
    width: 56px;
    height: 56px;
  }
}
</style>
