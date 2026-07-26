<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NInput, NButton, NIcon } from 'naive-ui'
import { WarningOutline } from '@vicons/ionicons5'
import { updatePassword } from '@/api'
import { useToast } from '@/composables/useToast'
import { safeLogout } from '@/utils/logout'

const router = useRouter()
const toast = useToast()
const loading = ref(false)

const form = reactive({
  newUsername: '',
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

type FieldKey = keyof typeof form
const errors = reactive<Record<FieldKey, string>>({
  newUsername: '',
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

function clearError(key: FieldKey) {
  errors[key] = ''
}

function validate(): boolean {
  errors.newUsername = ''
  errors.currentPassword = ''
  errors.newPassword = ''
  errors.confirmPassword = ''

  let ok = true

  if (!form.newUsername) {
    errors.newUsername = '请输入新用户名'
    ok = false
  } else if (form.newUsername.length < 3) {
    errors.newUsername = '用户名长度至少3位'
    ok = false
  } else if (form.newUsername.length > 20) {
    errors.newUsername = '用户名长度不能超过20位'
    ok = false
  }

  if (!form.currentPassword) {
    errors.currentPassword = '请输入当前密码'
    ok = false
  }

  if (!form.newPassword) {
    errors.newPassword = '请输入新密码'
    ok = false
  } else if (form.newPassword.length < 6) {
    errors.newPassword = '新密码长度不能少于6位'
    ok = false
  } else if (form.newPassword.length > 20) {
    errors.newPassword = '新密码长度不能超过20位'
    ok = false
  }

  if (!form.confirmPassword) {
    errors.confirmPassword = '请再次输入新密码'
    ok = false
  } else if (form.newPassword !== form.confirmPassword) {
    errors.confirmPassword = '两次输入密码不一致'
    ok = false
  }

  return ok
}

async function submit() {
  if (loading.value) return
  if (!validate()) return

  loading.value = true
  try {
    const res = await updatePassword({ ...form })
    if (res.code === 0) {
      toast.success(res.msg || '账号密码修改成功')
      setTimeout(() => {
        toast.success('即将跳转到登陆页面，请重新登录')
        setTimeout(() => {
          safeLogout()
          router.push('/')
        }, 1000)
      }, 1000)
      return
    }
    toast.error(res.msg || '账号密码修改失败')
    loading.value = false
  } catch {
    toast.error('修改账号密码时发生错误')
    loading.value = false
  }
}
</script>

<template>
  <div class="cp-wrap">
    <div class="cp-card">
      <div class="cp-badge">
        <NIcon :component="WarningOutline" :size="34" />
      </div>

      <h1 class="cp-title">安全提醒</h1>
      <p class="cp-subtitle">检测到您使用的是默认账号密码，为了您的账户安全，请立即修改</p>

      <div class="cp-form">
        <div class="cp-field">
          <label class="cp-label">新用户名</label>
          <NInput
            v-model:value="form.newUsername"
            :status="errors.newUsername ? 'error' : undefined"
            type="text"
            placeholder="请输入新用户名（至少3位）"
            @update:value="clearError('newUsername')"
            @keyup.enter="submit"
          />
          <p v-if="errors.newUsername" class="cp-error">{{ errors.newUsername }}</p>
        </div>

        <div class="cp-field">
          <label class="cp-label">当前密码</label>
          <NInput
            v-model:value="form.currentPassword"
            :status="errors.currentPassword ? 'error' : undefined"
            type="password"
            show-password-on="click"
            placeholder="请输入当前密码"
            @update:value="clearError('currentPassword')"
            @keyup.enter="submit"
          />
          <p v-if="errors.currentPassword" class="cp-error">{{ errors.currentPassword }}</p>
        </div>

        <div class="cp-field">
          <label class="cp-label">新密码</label>
          <NInput
            v-model:value="form.newPassword"
            :status="errors.newPassword ? 'error' : undefined"
            type="password"
            show-password-on="click"
            placeholder="请输入新密码（至少6位）"
            @update:value="clearError('newPassword')"
            @keyup.enter="submit"
          />
          <p v-if="errors.newPassword" class="cp-error">{{ errors.newPassword }}</p>
        </div>

        <div class="cp-field">
          <label class="cp-label">确认新密码</label>
          <NInput
            v-model:value="form.confirmPassword"
            :status="errors.confirmPassword ? 'error' : undefined"
            type="password"
            show-password-on="click"
            placeholder="请再次输入新密码"
            @update:value="clearError('confirmPassword')"
            @keyup.enter="submit"
          />
          <p v-if="errors.confirmPassword" class="cp-error">{{ errors.confirmPassword }}</p>
        </div>

        <NButton
          type="warning"
          color="#f0a020"
          size="large"
          block
          :loading="loading"
          class="cp-submit"
          @click="submit"
        >
          {{ loading ? '修改中...' : '立即修改账号密码' }}
        </NButton>

        <div class="cp-notice">⚠️ 注意：修改账号密码后需要重新登录</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cp-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: calc(24px + var(--safe-area-top)) 20px calc(24px + var(--safe-area-bottom));
  background:
    radial-gradient(1200px 520px at 50% -10%, rgba(240, 160, 32, 0.12), transparent 60%),
    var(--bg-body);
}

.cp-card {
  width: 100%;
  max-width: 480px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-card);
  padding: 40px 34px 32px;
  text-align: center;
  animation: cp-rise 0.4s cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes cp-rise {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.cp-badge {
  width: 72px;
  height: 72px;
  margin: 0 auto 20px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--warn);
  background: rgba(240, 160, 32, 0.12);
  box-shadow: 0 0 0 8px rgba(240, 160, 32, 0.06);
}

.cp-title {
  margin: 0 0 10px;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.5px;
  color: var(--text-primary);
}

.cp-subtitle {
  margin: 0 auto 26px;
  max-width: 380px;
  font-size: 14px;
  line-height: 1.6;
  color: var(--text-secondary);
}

.cp-form {
  text-align: left;
}

.cp-field {
  margin-bottom: 16px;
}

.cp-label {
  display: block;
  margin-bottom: 7px;
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.cp-error {
  margin: 6px 2px 0;
  font-size: 12.5px;
  line-height: 1.4;
  color: var(--danger);
}

.cp-submit {
  margin-top: 10px;
  font-weight: 600;
  --n-height: 44px;
}

.cp-notice {
  margin-top: 18px;
  padding: 12px 14px;
  border-radius: var(--radius-md);
  font-size: 13px;
  line-height: 1.5;
  color: #b26a00;
  background: rgba(240, 160, 32, 0.1);
  border: 1px solid rgba(240, 160, 32, 0.28);
  text-align: center;
}

html.dark .cp-notice {
  color: #f6c667;
}

@media (max-width: 768px) {
  .cp-card {
    padding: 32px 22px 26px;
    border-radius: var(--radius-md);
  }
  .cp-title {
    font-size: 22px;
  }
}
</style>
