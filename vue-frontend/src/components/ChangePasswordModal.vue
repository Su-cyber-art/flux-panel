<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { LoaderCircle } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
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
  <Dialog :open="props.show" @update:open="onShow">
    <DialogContent class="overflow-hidden sm:max-w-md">
      <DialogHeader><DialogTitle>修改账户信息</DialogTitle><DialogDescription>修改后需要使用新的账户信息重新登录。</DialogDescription></DialogHeader>
      <form class="flex min-h-0 flex-col gap-4" @submit.prevent="submit">
        <div class="min-h-0 space-y-4 overflow-y-auto">
          <div class="space-y-2"><label for="new-account-name" class="field-label">新用户名</label><Input id="new-account-name" v-model="form.newUsername" autocomplete="username" placeholder="至少 3 个字符" /></div>
          <div class="space-y-2"><label for="current-password" class="field-label">当前密码</label><Input id="current-password" v-model="form.currentPassword" type="password" autocomplete="current-password" /></div>
          <div class="space-y-2"><label for="new-password" class="field-label">新密码</label><Input id="new-password" v-model="form.newPassword" type="password" autocomplete="new-password" placeholder="至少 6 个字符" /></div>
          <div class="space-y-2"><label for="confirm-password" class="field-label">确认新密码</label><Input id="confirm-password" v-model="form.confirmPassword" type="password" autocomplete="new-password" /></div>
        </div>
        <DialogFooter class="pt-2"><Button type="button" variant="outline" @click="onShow(false)">取消</Button><Button type="submit" :disabled="loading"><LoaderCircle v-if="loading" class="size-4 animate-spin" />保存修改</Button></DialogFooter>
      </form>
    </DialogContent>
  </Dialog>
</template>
