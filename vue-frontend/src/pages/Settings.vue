<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { NCard, NButton, NInput, NTag, NIcon } from 'naive-ui'
import {
  ChevronBackOutline,
  AddOutline,
  TrashOutline,
  ServerOutline,
  CheckmarkCircleOutline,
} from '@vicons/ionicons5'
import { useToast } from '@/composables/useToast'
import { reinitializeBaseURL } from '@/api'
import {
  getPanelAddresses,
  savePanelAddress,
  setCurrentPanelAddress,
  deletePanelAddress,
  validatePanelAddress,
} from '@/utils/panel'

interface PanelAddress {
  name: string
  address: string
  inx: any
}

const router = useRouter()
const toast = useToast()

const panelAddresses = ref<PanelAddress[]>([])
const newName = ref('')
const newAddress = ref('')

// 地址格式错误的多行提示（逐字保留）
const ADDRESS_HINT =
  '地址格式不正确，请检查：\n' +
  '• 必须是完整的URL格式\n' +
  '• 必须以 http:// 或 https:// 开头\n' +
  '• 支持域名、IPv4、IPv6 地址\n' +
  '• 端口号范围：1-65535\n' +
  '• 示例：http://192.168.1.100:3000'

// 每次桥接调用前挂载全局回调，供原生宿主回填最新列表
function loadPanelAddresses() {
  ;(window as any).setPanelAddresses = (list: PanelAddress[]) => {
    panelAddresses.value = Array.isArray(list) ? list : []
  }
  getPanelAddresses('setPanelAddresses')
}

function handleAdd() {
  const name = newName.value.trim()
  const address = newAddress.value.trim()

  if (!name || !address) {
    toast.error('请输入名称和地址')
    return
  }
  if (!validatePanelAddress(address)) {
    toast.error(ADDRESS_HINT)
    return
  }

  savePanelAddress(name, address)
  newName.value = ''
  newAddress.value = ''
  toast.success('添加成功')
  loadPanelAddresses()
}

function handleSetCurrent(name: string) {
  setCurrentPanelAddress(name)
  reinitializeBaseURL()
  loadPanelAddresses()
}

function handleDelete(name: string) {
  deletePanelAddress(name)
  reinitializeBaseURL()
  toast.success('删除成功')
  loadPanelAddresses()
}

onMounted(() => {
  loadPanelAddresses()
})
</script>

<template>
  <div class="settings-page">
    <!-- 顶部导航 -->
    <header class="settings-header safe-top">
      <button class="back-btn" type="button" aria-label="返回" @click="router.back()">
        <NIcon :component="ChevronBackOutline" :size="22" />
      </button>
      <h1 class="settings-title">面板设置</h1>
      <span class="header-spacer" />
    </header>

    <main class="settings-body safe-bottom">
      <!-- 卡片一：添加新面板地址 -->
      <NCard :bordered="false" class="settings-card fx-fade-up">
        <div class="card-head">
          <span class="card-head-icon add">
            <NIcon :component="AddOutline" :size="18" />
          </span>
          <h2 class="card-head-title">添加新面板地址</h2>
        </div>

        <div class="form">
          <div class="field">
            <label class="field-label">名称</label>
            <NInput
              v-model:value="newName"
              placeholder="请输入面板名称"
              clearable
              @keyup.enter="handleAdd"
            />
          </div>
          <div class="field">
            <label class="field-label">地址</label>
            <NInput
              v-model:value="newAddress"
              placeholder="http://192.168.1.100:3000"
              clearable
              @keyup.enter="handleAdd"
            />
          </div>
          <NButton type="primary" size="large" block strong @click="handleAdd">
            <template #icon>
              <NIcon :component="AddOutline" />
            </template>
            添加
          </NButton>
        </div>
      </NCard>

      <!-- 卡片二：已保存的面板地址 -->
      <NCard :bordered="false" class="settings-card fx-fade-up">
        <div class="card-head">
          <span class="card-head-icon list">
            <NIcon :component="ServerOutline" :size="18" />
          </span>
          <h2 class="card-head-title">已保存的面板地址</h2>
          <NTag v-if="panelAddresses.length" size="small" round :bordered="false" class="count-tag">
            {{ panelAddresses.length }}
          </NTag>
        </div>

        <div v-if="!panelAddresses.length" class="empty">
          <NIcon :component="ServerOutline" :size="34" class="empty-icon" />
          <p class="empty-text">暂无保存的面板地址</p>
        </div>

        <ul v-else class="addr-list">
          <li
            v-for="panel in panelAddresses"
            :key="panel.name"
            class="addr-item"
            :class="{ 'is-current': panel.inx }"
          >
            <div class="addr-info">
              <div class="addr-name-row">
                <span class="addr-name">{{ panel.name }}</span>
                <NTag
                  v-if="panel.inx"
                  size="small"
                  round
                  :bordered="false"
                  class="current-tag"
                >
                  <template #icon>
                    <NIcon :component="CheckmarkCircleOutline" />
                  </template>
                  当前
                </NTag>
              </div>
              <span class="addr-address fx-mono">{{ panel.address }}</span>
            </div>

            <div class="addr-actions">
              <NButton
                v-if="!panel.inx"
                size="small"
                type="primary"
                secondary
                round
                @click="handleSetCurrent(panel.name)"
              >
                设为当前
              </NButton>
              <NButton
                size="small"
                type="error"
                tertiary
                round
                @click="handleDelete(panel.name)"
              >
                <template #icon>
                  <NIcon :component="TrashOutline" />
                </template>
                删除
              </NButton>
            </div>
          </li>
        </ul>
      </NCard>
    </main>
  </div>
</template>

<style scoped>
.settings-page {
  min-height: 100vh;
  min-height: 100dvh;
  background: var(--bg-body);
  display: flex;
  flex-direction: column;
}

/* ---------- 顶部导航 ---------- */
.settings-header {
  position: sticky;
  top: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 16px;
  background: color-mix(in srgb, var(--bg-elevated) 82%, transparent);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--border-soft);
}

.back-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 1px solid var(--border-soft);
  border-radius: 12px;
  background: var(--bg-elevated);
  color: var(--text-primary);
  cursor: pointer;
  transition: background 0.18s ease, transform 0.18s ease, border-color 0.18s ease;
}
.back-btn:hover {
  background: var(--bg-subtle);
  border-color: var(--brand-400);
  color: var(--brand-500);
}
.back-btn:active {
  transform: scale(0.94);
}

.settings-title {
  flex: 1;
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--text-primary);
}
.header-spacer {
  width: 38px;
}

/* ---------- 内容区 ---------- */
.settings-body {
  flex: 1;
  width: 100%;
  max-width: 640px;
  margin: 0 auto;
  padding: 20px 16px 40px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.settings-card {
  border-radius: var(--radius-lg);
  background: var(--bg-elevated);
  box-shadow: var(--shadow-card);
}

.card-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
}
.card-head-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 9px;
  color: #fff;
}
.card-head-icon.add {
  background: linear-gradient(135deg, var(--brand-400), var(--brand-600));
}
.card-head-icon.list {
  background: linear-gradient(135deg, #22c55e, #15803d);
}
.card-head-title {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
}
.count-tag {
  margin-left: auto;
  background: var(--bg-subtle);
  color: var(--text-secondary);
  font-weight: 600;
}

/* ---------- 表单 ---------- */
.form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
}

/* ---------- 空状态 ---------- */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 36px 0;
  color: var(--text-secondary);
}
.empty-icon {
  opacity: 0.5;
}
.empty-text {
  margin: 0;
  font-size: 14px;
}

/* ---------- 地址列表 ---------- */
.addr-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.addr-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-md);
  background: var(--bg-subtle);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}
.addr-item:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-card);
}
.addr-item.is-current {
  border-color: color-mix(in srgb, var(--ok) 55%, transparent);
  background: color-mix(in srgb, var(--ok) 8%, var(--bg-elevated));
}

.addr-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.addr-name-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.addr-name {
  font-size: 15px;
  font-weight: 650;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.current-tag {
  background: var(--ok);
  color: #fff;
  font-weight: 600;
}
.addr-address {
  font-size: 12.5px;
  color: var(--text-secondary);
  word-break: break-all;
}

.addr-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

@media (max-width: 520px) {
  .addr-item {
    flex-direction: column;
    align-items: stretch;
  }
  .addr-actions {
    justify-content: flex-end;
  }
}
</style>
