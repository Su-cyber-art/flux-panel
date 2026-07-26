<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import { useRouter } from 'vue-router'
import { NCard, NButton, NInput, NSwitch, NSelect, NIcon, NDivider } from 'naive-ui'
import { SettingsOutline, SaveOutline } from '@vicons/ionicons5'
import PageContainer from '@/components/PageContainer.vue'
import { getConfigs, updateConfigs } from '@/api'
import { isAdmin } from '@/utils/auth'
import { useConfigStore, clearConfigCache } from '@/stores/config'
import { useToast } from '@/composables/useToast'

// ============ 类型与配置项 schema ============
interface ConfigOption {
  label: string
  value: string
  description?: string
}
interface ConfigItem {
  key: string
  label: string
  placeholder?: string
  description?: string
  type: 'input' | 'switch' | 'select'
  options?: ConfigOption[]
  dependsOn?: string
  dependsValue?: string
}

const CONFIG_KEYS = ['app_name', 'captcha_enabled', 'captcha_type', 'ip']

const CONFIG_ITEMS: ConfigItem[] = [
  {
    key: 'ip',
    label: '面板后端地址',
    type: 'input',
    placeholder: '请输入面板后端IP:PORT',
    description:
      '格式"ip:port",用于对接节点时使用,ip是你安装面板服务器的公网ip,端口是安装脚本内输入的后端端口。不要套CDN,不支持https,通讯数据有加密',
  },
  {
    key: 'app_name',
    label: '应用名称',
    type: 'input',
    placeholder: '请输入应用名称',
    description: '在浏览器标签页和导航栏显示的应用名称',
  },
  {
    key: 'captcha_enabled',
    label: '启用验证码',
    type: 'switch',
    description: '开启后，用户登录时需要完成验证码验证',
  },
  {
    key: 'captcha_type',
    label: '验证码类型',
    type: 'select',
    placeholder: '请选择验证码类型',
    description: '选择验证码的显示类型，不同类型有不同的安全级别',
    dependsOn: 'captcha_enabled',
    dependsValue: 'true',
    options: [
      { value: 'RANDOM', label: '随机类型', description: '系统随机选择验证码类型' },
      { value: 'SLIDER', label: '滑块验证码', description: '拖动滑块完成拼图验证' },
      { value: 'WORD_IMAGE_CLICK', label: '文字点选验证码', description: '按顺序点击指定文字' },
      { value: 'ROTATE', label: '旋转验证码', description: '旋转图片到正确角度' },
      { value: 'CONCAT', label: '拼图验证码', description: '拖动滑块完成图片拼接' },
    ],
  },
]

const router = useRouter()
const toast = useToast()
const configStore = useConfigStore()

// ============ 状态引导 ============
function getInitialConfigs(): Record<string, string> {
  const result: Record<string, string> = {}
  CONFIG_KEYS.forEach((k) => {
    const v = localStorage.getItem('vite_config_' + k)
    if (v != null) result[k] = v
  })
  return result
}

const initialConfigs = getInitialConfigs()
const hasCached = Object.keys(initialConfigs).length > 0

const configs = ref<Record<string, string>>({ ...initialConfigs })
const original = ref<Record<string, string>>({ ...initialConfigs })
const loading = ref(!hasCached)
const saving = ref(false)

function normalize(raw: Record<string, any>): Record<string, string> {
  const out: Record<string, string> = {}
  Object.entries(raw).forEach(([k, v]) => {
    out[k] = v == null ? '' : String(v)
  })
  return out
}

async function loadConfigs() {
  try {
    const res = await getConfigs()
    if (res.code === 0 && res.data) {
      const next = normalize(res.data)
      // 仅在与当前数据不同的时候才刷新状态，避免闪烁
      if (JSON.stringify(next) !== JSON.stringify(configs.value)) {
        configs.value = { ...next }
        original.value = { ...next }
      }
    } else if (!hasCached) {
      toast.error('加载配置出错，请重试')
    }
  } catch {
    if (!hasCached) toast.error('加载配置出错，请重试')
  } finally {
    loading.value = false
  }
}

// ============ 变更追踪 ============
function handleConfigChange(key: string, value: string) {
  const next = { ...configs.value, [key]: value }
  // 启用验证码但类型未设置时，默认使用随机类型
  if (key === 'captcha_enabled' && value === 'true' && !next.captcha_type) {
    next.captcha_type = 'RANDOM'
  }
  configs.value = next
}

function fieldChanged(key: string): boolean {
  return (configs.value[key] ?? '') !== (original.value[key] ?? '')
}

const hasChanges = computed(() => {
  const keys = new Set([...Object.keys(configs.value), ...Object.keys(original.value)])
  for (const k of keys) {
    if ((configs.value[k] ?? '') !== (original.value[k] ?? '')) return true
  }
  return false
})

// ============ 依赖渲染 ============
function shouldShowItem(item: ConfigItem): boolean {
  return !item.dependsOn || configs.value[item.dependsOn] === item.dependsValue
}
const visibleItems = computed(() => CONFIG_ITEMS.filter(shouldShowItem))

function selectOptions(item: ConfigItem) {
  return (item.options || []).map((o) => ({ label: o.label, value: o.value, description: o.description }))
}
function renderSelectLabel(option: any) {
  return h('div', { style: 'padding:2px 0' }, [
    h('div', { style: 'font-weight:500' }, option.label as string),
    option.description
      ? h('div', { style: 'font-size:12px;color:var(--text-secondary);margin-top:2px' }, option.description as string)
      : null,
  ])
}

// ============ 保存 ============
async function handleSave() {
  saving.value = true
  try {
    const changedKeys = Array.from(
      new Set([...Object.keys(configs.value), ...Object.keys(original.value)]),
    ).filter((k) => (configs.value[k] ?? '') !== (original.value[k] ?? ''))
    const appNameChanged = (configs.value.app_name ?? '') !== (original.value.app_name ?? '')

    const res = await updateConfigs({ ...configs.value })
    if (res.code === 0) {
      toast.success('配置保存成功')
      clearConfigCache()
      original.value = { ...configs.value }
      if (appNameChanged) await configStore.syncAppName()
      window.dispatchEvent(new CustomEvent('configUpdated', { detail: { changedKeys } }))
    } else {
      toast.error('保存配置失败: ' + res.msg)
    }
  } catch {
    toast.error('保存配置出错，请重试')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  if (!isAdmin()) {
    toast.error('权限不足，只有管理员可以访问此页面')
    router.replace('/dashboard')
    return
  }
  setTimeout(loadConfigs, 100)
})
</script>

<template>
  <PageContainer :loading="loading" loading-text="加载配置中...">
    <!-- 页面标题 -->
    <div class="page-head">
      <NIcon :component="SettingsOutline" class="page-head-icon" />
      <div>
        <h1 class="page-title">网站配置</h1>
        <p class="page-subtitle">管理网站的基本信息和显示设置</p>
      </div>
    </div>

    <NCard class="fx-card config-card" :bordered="false">
      <!-- 卡片头部 -->
      <div class="card-head">
        <div class="card-head-text">
          <h2 class="card-title">基本设置</h2>
          <p class="card-subtitle">配置网站的基本信息，这些设置会影响网站的显示效果</p>
        </div>
        <NButton
          type="primary"
          :loading="saving"
          :disabled="!hasChanges"
          @click="handleSave"
        >
          <template #icon>
            <NIcon :component="SaveOutline" />
          </template>
          {{ saving ? '保存中...' : '保存配置' }}
        </NButton>
      </div>

      <NDivider style="margin:16px 0" />

      <!-- 配置项列表 -->
      <div class="item-list">
        <template v-for="(item, index) in visibleItems" :key="item.key">
          <div class="config-item">
            <div class="item-label-col">
              <div class="item-label">{{ item.label }}</div>
              <div v-if="item.description" class="item-desc">{{ item.description }}</div>
            </div>
            <div class="item-control-col">
              <!-- input -->
              <div v-if="item.type === 'input'" :class="{ 'ctrl-changed': fieldChanged(item.key) }">
                <NInput
                  :value="configs[item.key] || ''"
                  :placeholder="item.placeholder"
                  @update:value="(v: string) => handleConfigChange(item.key, v)"
                />
              </div>

              <!-- switch -->
              <div
                v-else-if="item.type === 'switch'"
                class="switch-wrap"
                :class="{ 'switch-changed': fieldChanged(item.key) }"
              >
                <NSwitch
                  :value="configs[item.key] === 'true' ? 'true' : 'false'"
                  checked-value="true"
                  unchecked-value="false"
                  @update:value="(v: string) => handleConfigChange(item.key, v)"
                />
                <span class="switch-caption">
                  {{ configs[item.key] === 'true' ? '已启用' : '已禁用' }}
                </span>
              </div>

              <!-- select -->
              <div v-else :class="{ 'ctrl-changed': fieldChanged(item.key) }">
                <NSelect
                  :value="configs[item.key] || null"
                  :options="selectOptions(item)"
                  :placeholder="item.placeholder"
                  :render-label="renderSelectLabel"
                  @update:value="(v: string) => handleConfigChange(item.key, v)"
                />
              </div>
            </div>
          </div>
          <NDivider v-if="index < visibleItems.length - 1" style="margin:14px 0" />
        </template>
      </div>
    </NCard>

    <!-- 底部变更提示 -->
    <div v-if="hasChanges" class="change-banner">
      <span class="pulse-dot" />
      <span>检测到配置变更，请记得保存您的修改</span>
    </div>
  </PageContainer>
</template>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 20px;
}
.page-head-icon {
  width: 32px;
  height: 32px;
  color: var(--brand-500);
}
.page-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--text-primary);
}
.page-subtitle {
  margin: 2px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.config-card {
  background: var(--bg-elevated);
}
.card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}
.card-title {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
}
.card-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--text-secondary);
}

.item-list {
  display: flex;
  flex-direction: column;
}
.config-item {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
}
.item-label-col {
  flex: 1 1 auto;
  min-width: 0;
}
.item-label {
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}
.item-desc {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--text-secondary);
}
.item-control-col {
  flex: 0 0 340px;
  max-width: 340px;
}

/* 变更字段：warning 高亮边框 */
.ctrl-changed :deep(.n-input),
.ctrl-changed :deep(.n-base-selection) {
  --n-border: 1px solid var(--warn);
  --n-border-hover: 1px solid var(--warn);
  --n-border-focus: 1px solid var(--warn);
  --n-box-shadow-focus: 0 0 0 2px rgba(240, 160, 32, 0.2);
}

.switch-wrap {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 4px 10px;
  border: 1px solid transparent;
  border-radius: 10px;
  transition: border-color 0.2s ease;
}
.switch-wrap.switch-changed {
  border-color: var(--warn);
}
.switch-caption {
  font-size: 13px;
  color: var(--text-secondary);
}

.change-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 18px;
  padding: 14px 18px;
  border-radius: var(--radius-md);
  border: 1px solid var(--warn);
  background: rgba(240, 160, 32, 0.08);
  color: var(--text-primary);
  font-size: 13px;
}
.pulse-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--warn);
  animation: pulse-dot 1.4s ease-in-out infinite;
}
@keyframes pulse-dot {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.35;
    transform: scale(0.7);
  }
}

@media (max-width: 768px) {
  .config-item {
    flex-direction: column;
    gap: 10px;
  }
  .item-control-col {
    flex: 1 1 auto;
    max-width: none;
    width: 100%;
  }
}
</style>
