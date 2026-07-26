<script setup lang="ts">
import { reactive, ref, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { NInput, NButton } from 'naive-ui'
import Logo from '@/components/Logo.vue'
import { useToast } from '@/composables/useToast'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { login, checkCaptcha } from '@/api'
import { getBaseURL } from '@/api/http'
import { isWebViewFunc } from '@/utils/panel'
import bgImage from '@/assets/bg.jpg'

const router = useRouter()
const toast = useToast()
const auth = useAuthStore()
const config = useConfigStore()

const form = reactive({
  username: '',
  password: '',
  captchaId: '',
})
const errors = reactive<{ username?: string; password?: string }>({})

const loading = ref(false)
const showCaptcha = ref(false)
const captchaContainer = ref<HTMLElement | null>(null)
let tacInstance: any = null

const isWebView = isWebViewFunc()
const buttonLabel = computed(() =>
  loading.value ? (showCaptcha.value ? '验证中...' : '登录中...') : '登录',
)
const versionText = computed(() => `v${isWebView ? config.appVersion : config.version}`)
const captchaFilterStyle = computed(() => ({
  filter: document.documentElement.classList.contains('dark')
    ? 'brightness(0.8) contrast(0.9)'
    : 'none',
}))

// 表单校验
function validateForm(): boolean {
  const next: { username?: string; password?: string } = {}
  if (!form.username.trim()) {
    next.username = '请输入用户名'
  }
  if (!form.password.trim()) {
    next.password = '请输入密码'
  } else if (form.password.length < 6) {
    next.password = '密码长度至少6位'
  }
  errors.username = next.username
  errors.password = next.password
  return !next.username && !next.password
}

// 输入时清除该字段错误
function onUsername(value: string) {
  form.username = value
  if (errors.username) errors.username = undefined
}
function onPassword(value: string) {
  form.password = value
  if (errors.password) errors.password = undefined
}

// 初始化验证码
async function initCaptcha() {
  if (!window.TAC || !captchaContainer.value) return
  try {
    if (tacInstance) {
      tacInstance.destroyWindow()
      tacInstance = null
    }

    // 使用 axios 的 baseURL，确保在 WebView 中使用正确的面板地址
    const baseURL = getBaseURL()

    const captchaConfig = {
      requestCaptchaDataUrl: `${baseURL}captcha/generate`,
      validCaptchaUrl: `${baseURL}captcha/verify`,
      bindEl: '#captcha-container',
      validSuccess: (res: any, _c: any, tac: any) => {
        form.captchaId = res.data.validToken
        showCaptcha.value = false
        tac.destroyWindow()
        performLogin()
      },
      validFail: (_res: any, _c: any, tac: any) => {
        tac.reloadCaptcha()
      },
      btnCloseFun: (_event: any, tac: any) => {
        showCaptcha.value = false
        tac.destroyWindow()
        loading.value = false
      },
      btnRefreshFun: (_event: any, tac: any) => {
        tac.reloadCaptcha()
      },
    }

    // 检测暗黑模式：跟随全局 html.dark 主题
    const isDark = document.documentElement.classList.contains('dark')
    const trackColor = isDark ? '#4a5568' : '#7db0be'

    const style = {
      bgUrl: bgImage,
      logoUrl: null,
      moveTrackMaskBgColor: trackColor,
      moveTrackMaskBorderColor: trackColor,
    }

    tacInstance = new window.TAC(captchaConfig, style)
    tacInstance.init()
  } catch (error) {
    console.error('初始化验证码失败:', error)
    toast.error('验证码初始化失败，请刷新页面重试')
    showCaptcha.value = false
    loading.value = false
  }
}

// 执行登录请求
async function performLogin() {
  try {
    const response = await login({
      username: form.username.trim(),
      password: form.password,
      captchaId: form.captchaId,
    })

    if (response.code !== 0) {
      toast.error(response.msg || '登录失败')
      return
    }

    const { token, role_id, name, requirePasswordChange } = response.data
    auth.setSession(token, role_id, name)

    if (requirePasswordChange) {
      toast.success('检测到默认密码，即将跳转到修改密码页面')
      router.push('/change-password')
      return
    }

    toast.success('登录成功')
    router.push('/dashboard')
  } catch (error) {
    console.error('登录错误:', error)
    toast.error('网络错误，请稍后重试')
  } finally {
    loading.value = false
  }
}

// 提交登录（先检查是否需要验证码）
async function handleLogin() {
  if (loading.value && !showCaptcha.value) return
  if (!validateForm()) return

  loading.value = true
  try {
    const checkResponse = await checkCaptcha()

    if (checkResponse.code !== 0) {
      toast.error('检查验证码状态失败，请重试' + checkResponse.msg)
      loading.value = false
      return
    }

    if (checkResponse.data === 0) {
      // 无需验证码，直接登录
      await performLogin()
    } else {
      // 需要验证码，展示弹层，延时初始化以确保容器已挂载
      showCaptcha.value = true
      setTimeout(() => {
        initCaptcha()
      }, 100)
    }
  } catch (error) {
    console.error('检查验证码状态错误:', error)
    toast.error('网络错误，请稍后重试' + error)
    loading.value = false
  }
}

function onEnter() {
  if (!loading.value) handleLogin()
}

onUnmounted(() => {
  if (tacInstance) {
    tacInstance.destroyWindow()
    tacInstance = null
  }
})
</script>

<template>
  <div class="login-page">
    <section class="login-card">
      <header class="login-head">
        <div class="logo-ring">
          <Logo :size="40" />
        </div>
        <h1 class="login-title">登录</h1>
        <p class="login-subtitle">请输入您的账号信息</p>
      </header>

      <div class="login-form">
        <div class="field">
          <label class="field-label" for="login-username">用户名</label>
          <NInput
            id="login-username"
            :value="form.username"
            size="large"
            placeholder="请输入用户名"
            :status="errors.username ? 'error' : undefined"
            :disabled="loading"
            @update:value="onUsername"
            @keyup.enter="onEnter"
          />
          <p v-if="errors.username" class="field-error">{{ errors.username }}</p>
        </div>

        <div class="field">
          <label class="field-label" for="login-password">密码</label>
          <NInput
            id="login-password"
            :value="form.password"
            type="password"
            show-password-on="click"
            size="large"
            placeholder="请输入密码"
            :status="errors.password ? 'error' : undefined"
            :disabled="loading"
            @update:value="onPassword"
            @keyup.enter="onEnter"
          />
          <p v-if="errors.password" class="field-error">{{ errors.password }}</p>
        </div>

        <NButton
          class="login-submit"
          type="primary"
          size="large"
          block
          :loading="loading"
          :disabled="loading"
          @click="handleLogin"
        >
          {{ buttonLabel }}
        </NButton>
      </div>
    </section>

    <!-- 版权信息 -->
    <footer class="login-footer">
      <p>
        Powered by
        <a
          href="https://github.com/Su-cyber-art/flux-panel"
          target="_blank"
          rel="noopener noreferrer"
        >flux-panel</a>
      </p>
      <p class="login-version">{{ versionText }}</p>
    </footer>

    <!-- 验证码弹层 -->
    <div v-if="showCaptcha" class="captcha-overlay">
      <div class="captcha-backdrop" />
      <div
        id="captcha-container"
        ref="captchaContainer"
        class="captcha-box"
        :style="captchaFilterStyle"
      />
    </div>
  </div>
</template>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  overflow: hidden;
  background: var(--bg-subtle);
}
@supports (min-height: 100dvh) {
  .login-page {
    min-height: 100dvh;
  }
}

/* 登录卡片 */
.login-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 420px;
  padding: 40px 34px 30px;
  border-radius: 8px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow-card);
  animation: card-in 0.5s cubic-bezier(0.2, 0.7, 0.2, 1) both;
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(16px) scale(0.98); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

.login-head {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  margin-bottom: 26px;
}
.logo-ring {
  display: grid;
  place-items: center;
  width: 68px;
  height: 68px;
  border-radius: 8px;
  margin-bottom: 16px;
  background: var(--bg-subtle);
  border: 1px solid var(--border-soft);
  box-shadow: 0 10px 26px rgba(37, 99, 235, 0.22);
}
.login-title {
  margin: 0;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: 0;
  color: var(--text-primary);
}
.login-subtitle {
  margin: 8px 0 0;
  font-size: 13.5px;
  color: var(--text-secondary);
}

/* 表单 */
.login-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.field {
  display: flex;
  flex-direction: column;
  gap: 7px;
}
.field-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-secondary);
  padding-left: 2px;
}
.field-error {
  margin: 0;
  padding-left: 2px;
  font-size: 12.5px;
  color: var(--danger);
}

.login-form :deep(.n-input) {
  border-radius: var(--radius-md);
  --n-height: 46px;
}
.login-form :deep(.n-input .n-input__border),
.login-form :deep(.n-input .n-input__state-border) {
  border-radius: var(--radius-md);
}

.login-submit {
  margin-top: 6px;
  height: 48px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0;
  border-radius: var(--radius-md);
}
.login-submit :deep(.n-button__border),
.login-submit :deep(.n-button__state-border) {
  border: none;
}
.login-submit:not(.n-button--disabled) {
  background: var(--brand-600);
  box-shadow: 0 10px 24px rgba(37, 99, 235, 0.32);
  transition: transform 0.18s ease, box-shadow 0.18s ease, filter 0.18s ease;
}
.login-submit:not(.n-button--disabled):hover {
  filter: brightness(1.05);
  transform: translateY(-1px);
  box-shadow: 0 14px 30px rgba(37, 99, 235, 0.4);
}
.login-submit:not(.n-button--disabled):active {
  transform: translateY(0);
}

/* 页脚 */
.login-footer {
  position: fixed;
  inset-inline: 0;
  bottom: calc(16px + var(--safe-area-bottom));
  text-align: center;
  color: var(--text-secondary);
  z-index: 1;
  pointer-events: none;
}
.login-footer p {
  margin: 0;
  font-size: 12px;
  opacity: 0.85;
}
.login-footer a {
  color: var(--brand-500);
  text-decoration: none;
  pointer-events: auto;
  transition: color 0.18s ease;
}
.login-footer a:hover {
  color: var(--brand-600);
}
.login-version {
  margin-top: 3px !important;
  opacity: 0.6 !important;
}

/* 验证码容器 */
.captcha-box {
  position: relative;
  z-index: 1;
  display: flex;
  justify-content: center;
}

@media (max-width: 480px) {
  .login-card {
    padding: 32px 22px 26px;
  }
  .login-title {
    font-size: 23px;
  }
}
</style>
