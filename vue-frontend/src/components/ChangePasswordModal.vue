<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { NModal, NButton, NInput, NFormItem } from 'naive-ui'
import { updatePassword } from '@/api'
import { useToast } from '@/composables/useToast'
import { safeLogout } from '@/utils/logout'

const props = defineProps<{ show: boolean }>()
const emit = defineEmits<{ (e: 'update:show', v: boolean): void }>()

const router = useRouter()
const toast = useToast()
const loading = ref(false)

const form = reactive({
  newUsername: '',
  currentPassword: '',
  newPassword: '',
  confirmPassword: '',
})

function reset() {
  form.newUsername = ''
  form.currentPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
}

function validate(): string | null {
  if (!form.newUsername) return '请输入新用户名'
  if (form.newUsername.length < 3) return '用户名长度至少3位'
  if (!form.currentPassword) return '请输入当前密码'
  if (!form.newPassword) return '请输入新密码'
  if (form.newPassword.length < 6) return '新密码长度不能少于6位'
  if (form.newPassword !== form.confirmPassword) return '两次输入密码不一致'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    toast.error(err)
    return
  }
  loading.value = true
  try {
    const res = await updatePassword({ ...form })
    if (res.code === 0) {
      toast.success('密码修改成功，请重新登录')
      emit('update:show', false)
      setTimeout(() => {
        safeLogout()
        router.push('/')
      }, 800)
    } else {
      toast.error(res.msg || '密码修改失败')
    }
  } catch {
    toast.error('修改密码时发生错误')
  } finally {
    loading.value = false
  }
}

function onShow(v: boolean) {
  if (!v) reset()
  emit('update:show', v)
}
</script>

<template>
  <NModal
    :show="props.show"
    preset="card"
    title="修改密码"
    style="width: 460px; max-width: 94vw"
    :bordered="false"
    @update:show="onShow"
  >
    <NFormItem label="新用户名" :show-feedback="false" style="margin-bottom:14px">
      <NInput v-model:value="form.newUsername" placeholder="请输入新用户名（至少3位）" />
    </NFormItem>
    <NFormItem label="当前密码" :show-feedback="false" style="margin-bottom:14px">
      <NInput v-model:value="form.currentPassword" type="password" show-password-on="click" placeholder="请输入当前密码" />
    </NFormItem>
    <NFormItem label="新密码" :show-feedback="false" style="margin-bottom:14px">
      <NInput v-model:value="form.newPassword" type="password" show-password-on="click" placeholder="请输入新密码（至少6位）" />
    </NFormItem>
    <NFormItem label="确认密码" :show-feedback="false">
      <NInput
        v-model:value="form.confirmPassword"
        type="password"
        show-password-on="click"
        placeholder="请再次输入新密码"
        @keyup.enter="submit"
      />
    </NFormItem>
    <template #footer>
      <div style="display:flex;justify-content:flex-end;gap:10px">
        <NButton @click="onShow(false)">取消</NButton>
        <NButton type="primary" :loading="loading" @click="submit">确定</NButton>
      </div>
    </template>
  </NModal>
</template>
