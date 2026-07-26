<script setup lang="ts">
import { ref, computed, onMounted, h } from 'vue'
import {
  NButton,
  NModal,
  NInput,
  NInputNumber,
  NSelect,
  NTag,
  NProgress,
  NDatePicker,
  NRadioGroup,
  NRadio,
  NDataTable,
  NIcon,
  type DataTableColumns,
} from 'naive-ui'
import {
  SearchOutline,
  AddOutline,
  CreateOutline,
  RefreshOutline,
  SettingsOutline,
  TrashOutline,
  PersonOutline,
} from '@vicons/ionicons5'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useToast } from '@/composables/useToast'
import {
  getAllUsers,
  createUser,
  updateUser,
  deleteUser,
  resetUserFlow,
  getTunnelList,
  getSpeedLimitList,
  getUserTunnelList,
  assignUserTunnel,
  updateUserTunnel,
  removeUserTunnel,
} from '@/api'
import { formatFlowPlain, formatDate } from '@/utils/format'
import type { User, UserForm, UserTunnel, UserTunnelForm, Tunnel, SpeedLimitRule } from '@/types'

const toast = useToast()

// 语义色，供 NProgress / 内联样式使用
const COLOR_OK = '#18a058'
const COLOR_WARN = '#f0a020'
const COLOR_DANGER = '#e04141'

// ============ 状态 ============
const loading = ref(true)
const users = ref<User[]>([])
const tunnels = ref<Tunnel[]>([])
const speedLimits = ref<SpeedLimitRule[]>([])
const searchKeyword = ref('')

// ============ 工具方法（模块级语义） ============
type StatusChip = { type: 'success' | 'warning' | 'error'; text: string }

function getExpireStatus(expTime?: number): StatusChip {
  if (!expTime) return { type: 'success', text: '正常' }
  const now = Date.now()
  if (expTime < now) return { type: 'error', text: '已过期' }
  const diffDays = Math.ceil((expTime - now) / (1000 * 60 * 60 * 24))
  if (diffDays <= 7) return { type: 'warning', text: `${diffDays}天后过期` }
  return { type: 'success', text: '正常' }
}

function getUserStatus(user: User): StatusChip {
  return user.status === 1 ? { type: 'success', text: '正常' } : { type: 'error', text: '禁用' }
}

function calculateUserTotalUsedFlow(user: User): number {
  return (user.inFlow || 0) + (user.outFlow || 0)
}
function calculateTunnelUsedFlow(t: UserTunnel): number {
  return (t.inFlow || 0) + (t.outFlow || 0)
}

function usagePercent(flow: number, used: number): number {
  if (!flow || flow <= 0) return 0
  return Math.min((used / (flow * 1024 * 1024 * 1024)) * 100, 100)
}
function usageColor(pct: number): string {
  if (pct > 90) return COLOR_DANGER
  if (pct > 70) return COLOR_WARN
  return COLOR_OK
}

function resetTimeText(v?: number): string {
  return v === 0 ? '不重置' : `每月${v}号`
}

const clamp = (v: number | null): number => Math.min(Math.max(Number(v) || 0, 1), 99999)

// 流量重置日期下拉：0 不重置 + 1..31
const resetDayOptions = computed(() => [
  { label: '不重置', value: 0 },
  ...Array.from({ length: 31 }, (_, i) => ({
    label: `每月${i + 1}号（0点重置）`,
    value: i + 1,
  })),
])

// 到期时间：NDatePicker 返回当日 0 点时间戳，规范化到 23:59:59
function normalizeExp(ts: number | null): number | null {
  if (ts == null) return null
  const d = new Date(ts)
  d.setHours(23, 59, 59, 0)
  return d.getTime()
}

// ============ 搜索（后端忽略 keyword，这里同时做客户端过滤以获得更好体验） ============
const filteredUsers = computed(() => {
  const kw = searchKeyword.value.trim().toLowerCase()
  if (!kw) return users.value
  return users.value.filter(
    (u) => (u.name || '').toLowerCase().includes(kw) || (u.user || '').toLowerCase().includes(kw)
  )
})

// ============ 数据加载 ============
async function loadUsers() {
  try {
    const res = await getAllUsers({ current: 1, size: 10, keyword: searchKeyword.value })
    if (res.code === 0) {
      users.value = res.data || []
    } else {
      toast.error(res.msg || '获取用户列表失败')
    }
  } catch (e) {
    console.warn('获取用户列表失败:', e)
    toast.error('获取用户列表失败')
  }
}

async function loadTunnels() {
  try {
    const res = await getTunnelList()
    if (res.code === 0) tunnels.value = res.data || []
    else console.warn('获取隧道列表失败:', res.msg)
  } catch (e) {
    console.warn('获取隧道列表失败:', e)
  }
}

async function loadSpeedLimits() {
  try {
    const res = await getSpeedLimitList()
    if (res.code === 0) speedLimits.value = res.data || []
    else console.warn('获取限速规则失败:', res.msg)
  } catch (e) {
    console.warn('获取限速规则失败:', e)
  }
}

async function loadAll() {
  loading.value = true
  await Promise.all([loadUsers(), loadTunnels(), loadSpeedLimits()])
  loading.value = false
}

function handleSearch() {
  loadUsers()
}

// ============ 用户表单（新增 / 编辑） ============
const showUserModal = ref(false)
const submittingUser = ref(false)
const userForm = ref<UserForm>(defaultUserForm())
const isEditUser = computed(() => userForm.value.id != null)

function defaultUserForm(): UserForm {
  return {
    name: '',
    user: '',
    pwd: '',
    status: 1,
    flow: 100,
    num: 10,
    expTime: null,
    flowResetTime: 0,
  }
}

function openCreateUser() {
  userForm.value = defaultUserForm()
  showUserModal.value = true
}

function openEditUser(u: User) {
  userForm.value = {
    id: u.id,
    name: u.name || '',
    user: u.user,
    pwd: '',
    status: u.status,
    flow: u.flow,
    num: u.num,
    expTime: u.expTime ?? null,
    flowResetTime: u.flowResetTime ?? 0,
  }
  showUserModal.value = true
}

async function handleSubmitUser() {
  const f = userForm.value
  if (!f.user || (!f.pwd && !isEditUser.value) || !f.expTime) {
    toast.error('请填写完整信息')
    return
  }
  submittingUser.value = true
  try {
    const body: Record<string, any> = { ...f, expTime: f.expTime }
    if (isEditUser.value && !f.pwd) delete body.pwd
    const res = isEditUser.value ? await updateUser(body) : await createUser(body)
    if (res.code === 0) {
      toast.success(isEditUser.value ? '更新成功' : '创建成功')
      showUserModal.value = false
      await loadUsers()
    } else {
      toast.error(res.msg || (isEditUser.value ? '更新失败' : '创建失败'))
    }
  } catch (e) {
    console.warn('提交用户失败:', e)
    toast.error(isEditUser.value ? '更新失败' : '创建失败')
  } finally {
    submittingUser.value = false
  }
}

// ============ 删除用户 ============
const showDeleteUser = ref(false)
const userToDelete = ref<User | null>(null)

function openDeleteUser(u: User) {
  userToDelete.value = u
  showDeleteUser.value = true
}

async function confirmDeleteUser() {
  if (!userToDelete.value) return
  try {
    const res = await deleteUser(userToDelete.value.id)
    if (res.code === 0) {
      toast.success('删除成功')
      showDeleteUser.value = false
      userToDelete.value = null
      await loadUsers()
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch (e) {
    console.warn('删除用户失败:', e)
    toast.error('删除失败')
  }
}

// ============ 重置用户流量 ============
const showResetUser = ref(false)
const resettingUser = ref(false)
const userToReset = ref<User | null>(null)

function openResetUser(u: User) {
  userToReset.value = u
  showResetUser.value = true
}

async function confirmResetUser() {
  if (!userToReset.value) return
  resettingUser.value = true
  try {
    const res = await resetUserFlow({ id: userToReset.value.id, type: 1 })
    if (res.code === 0) {
      toast.success('流量重置成功')
      showResetUser.value = false
      userToReset.value = null
      await loadUsers()
    } else {
      toast.error(res.msg || '重置失败')
    }
  } catch (e) {
    console.warn('重置用户流量失败:', e)
    toast.error('重置失败')
  } finally {
    resettingUser.value = false
  }
}

// ============ 隧道权限管理 ============
const showTunnelModal = ref(false)
const currentUser = ref<User | null>(null)
const userTunnels = ref<UserTunnel[]>([])
const tunnelListLoading = ref(false)

// 分配新权限 子表单
const assignForm = ref<UserTunnelForm>(defaultAssignForm())
const assignLoading = ref(false)

function defaultAssignForm(): UserTunnelForm {
  return { tunnelId: null, flow: 100, num: 10, expTime: null, flowResetTime: 0, speedId: 'null', status: 1 }
}

const availableTunnels = computed(() =>
  tunnels.value
    .filter((t) => !userTunnels.value.some((ut) => ut.tunnelId === t.id))
    .map((t) => ({ label: t.name, value: t.id }))
)

function speedOptionsFor(tunnelId: number | null) {
  return [
    { label: '不限速', value: 'null' as const },
    ...speedLimits.value
      .filter((s) => s.tunnelId === tunnelId)
      .map((s) => ({ label: s.name, value: s.id })),
  ]
}
const assignSpeedOptions = computed(() => speedOptionsFor(assignForm.value.tunnelId))

async function loadUserTunnels(userId: number) {
  tunnelListLoading.value = true
  try {
    const res = await getUserTunnelList({ userId })
    if (res.code === 0) userTunnels.value = res.data || []
    else toast.error(res.msg || '获取隧道权限列表失败')
  } catch (e) {
    console.warn('获取隧道权限列表失败:', e)
    toast.error('获取隧道权限列表失败')
  } finally {
    tunnelListLoading.value = false
  }
}

function openTunnelModal(u: User) {
  currentUser.value = u
  assignForm.value = defaultAssignForm()
  userTunnels.value = []
  showTunnelModal.value = true
  loadUserTunnels(u.id)
}

function onAssignTunnelChange(v: number | null) {
  assignForm.value.tunnelId = v
  assignForm.value.speedId = 'null'
}

async function handleAssignTunnel() {
  const f = assignForm.value
  if (!f.tunnelId || !f.expTime || !currentUser.value) {
    toast.error('请填写完整信息')
    return
  }
  assignLoading.value = true
  try {
    const res = await assignUserTunnel({
      userId: currentUser.value.id,
      tunnelId: f.tunnelId,
      flow: f.flow,
      num: f.num,
      expTime: f.expTime,
      flowResetTime: f.flowResetTime,
      speedId: f.speedId === 'null' ? null : f.speedId,
    })
    if (res.code === 0) {
      toast.success('分配成功')
      assignForm.value = defaultAssignForm()
      await loadUserTunnels(currentUser.value.id)
    } else {
      toast.error(res.msg || '分配失败')
    }
  } catch (e) {
    console.warn('分配隧道权限失败:', e)
    toast.error('分配失败')
  } finally {
    assignLoading.value = false
  }
}

// 编辑隧道权限
const showEditTunnel = ref(false)
const editingTunnel = ref(false)
const editTunnelForm = ref<UserTunnelForm & { tunnelName?: string }>(defaultEditTunnelForm())
const editSpeedOptions = computed(() => speedOptionsFor(editTunnelForm.value.tunnelId))

function defaultEditTunnelForm() {
  return {
    id: undefined as number | undefined,
    tunnelId: null as number | null,
    tunnelName: '',
    flow: 100,
    num: 10,
    expTime: null as number | null,
    flowResetTime: 0,
    speedId: 'null' as number | 'null',
    status: 1,
  }
}

function openEditTunnel(row: UserTunnel) {
  editTunnelForm.value = {
    id: row.id,
    tunnelId: row.tunnelId,
    tunnelName: row.tunnelName,
    flow: row.flow,
    num: row.num,
    expTime: row.expTime ?? null,
    flowResetTime: row.flowResetTime ?? 0,
    speedId: row.speedId ?? 'null',
    status: row.status,
  }
  showEditTunnel.value = true
}

async function handleUpdateTunnel() {
  const f = editTunnelForm.value
  editingTunnel.value = true
  try {
    const res = await updateUserTunnel({
      id: f.id,
      flow: f.flow,
      num: f.num,
      expTime: f.expTime,
      flowResetTime: f.flowResetTime,
      speedId: f.speedId === 'null' ? null : f.speedId,
      status: f.status,
    })
    if (res.code === 0) {
      toast.success('更新成功')
      showEditTunnel.value = false
      if (currentUser.value) await loadUserTunnels(currentUser.value.id)
    } else {
      toast.error(res.msg || '更新失败')
    }
  } catch (e) {
    console.warn('更新隧道权限失败:', e)
    toast.error('更新失败')
  } finally {
    editingTunnel.value = false
  }
}

// 删除隧道权限
const showDeleteTunnel = ref(false)
const tunnelToDelete = ref<UserTunnel | null>(null)

function openDeleteTunnel(row: UserTunnel) {
  tunnelToDelete.value = row
  showDeleteTunnel.value = true
}

async function confirmDeleteTunnel() {
  if (!tunnelToDelete.value) return
  try {
    const res = await removeUserTunnel({ id: tunnelToDelete.value.id })
    if (res.code === 0) {
      toast.success('删除成功')
      showDeleteTunnel.value = false
      tunnelToDelete.value = null
      if (currentUser.value) await loadUserTunnels(currentUser.value.id)
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch (e) {
    console.warn('删除隧道权限失败:', e)
    toast.error('删除失败')
  }
}

// 重置隧道流量
const showResetTunnel = ref(false)
const resettingTunnel = ref(false)
const tunnelToReset = ref<UserTunnel | null>(null)

function openResetTunnel(row: UserTunnel) {
  tunnelToReset.value = row
  showResetTunnel.value = true
}

async function confirmResetTunnel() {
  if (!tunnelToReset.value) return
  resettingTunnel.value = true
  try {
    const res = await resetUserFlow({ id: tunnelToReset.value.id, type: 2 })
    if (res.code === 0) {
      toast.success('隧道流量重置成功')
      showResetTunnel.value = false
      tunnelToReset.value = null
      if (currentUser.value) await loadUserTunnels(currentUser.value.id)
    } else {
      toast.error(res.msg || '重置失败')
    }
  } catch (e) {
    console.warn('重置隧道流量失败:', e)
    toast.error('重置失败')
  } finally {
    resettingTunnel.value = false
  }
}

// ============ 已有权限表格列 ============
const tunnelColumns: DataTableColumns<UserTunnel> = [
  { title: '隧道名称', key: 'tunnelName', render: (row) => row.tunnelName },
  {
    title: '流量统计',
    key: 'flow',
    render: (row) =>
      h('div', { class: 'cell-flow' }, [
        h('div', null, [
          h('span', { class: 'cell-label' }, '限制: '),
          formatFlowPlain(row.flow, 'gb'),
        ]),
        h('div', null, [
          h('span', { class: 'cell-label' }, '已用: '),
          h('span', { style: { color: COLOR_DANGER } }, formatFlowPlain(calculateTunnelUsedFlow(row))),
        ]),
      ]),
  },
  { title: '转发数量', key: 'num', render: (row) => String(row.num) },
  {
    title: '状态',
    key: 'status',
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.status === 1 ? 'success' : 'error' },
        { default: () => (row.status === 1 ? '正常' : '禁用') }
      ),
  },
  {
    title: '限速规则',
    key: 'speed',
    render: (row) =>
      h(
        NTag,
        { size: 'small', bordered: false, type: row.speedLimitName ? 'warning' : 'success' },
        { default: () => row.speedLimitName || '不限速' }
      ),
  },
  { title: '重置时间', key: 'reset', render: (row) => resetTimeText(row.flowResetTime) },
  { title: '到期时间', key: 'exp', render: (row) => formatDate(row.expTime) },
  {
    title: '操作',
    key: 'actions',
    render: (row) =>
      h('div', { class: 'cell-actions' }, [
        h(
          NButton,
          { size: 'small', type: 'primary', quaternary: true, title: '编辑', onClick: () => openEditTunnel(row) },
          { icon: () => h(NIcon, { component: CreateOutline }) }
        ),
        h(
          NButton,
          { size: 'small', type: 'warning', quaternary: true, title: '重置流量', onClick: () => openResetTunnel(row) },
          { icon: () => h(NIcon, { component: RefreshOutline }) }
        ),
        h(
          NButton,
          { size: 'small', type: 'error', quaternary: true, title: '删除', onClick: () => openDeleteTunnel(row) },
          { icon: () => h(NIcon, { component: TrashOutline }) }
        ),
      ]),
  },
]

onMounted(loadAll)
</script>

<template>
  <PageContainer :loading="loading">
    <!-- 头部：搜索 + 新增 -->
    <div class="user-header">
      <div class="user-search">
        <NInput
          v-model:value="searchKeyword"
          placeholder="搜索用户名"
          clearable
          @keyup.enter="handleSearch"
        >
          <template #prefix>
            <NIcon :component="SearchOutline" />
          </template>
        </NInput>
        <NButton type="primary" @click="handleSearch">
          <template #icon><NIcon :component="SearchOutline" /></template>
        </NButton>
      </div>
      <NButton type="primary" @click="openCreateUser">
        <template #icon><NIcon :component="AddOutline" /></template>
        新增
      </NButton>
    </div>

    <!-- 用户卡片网格 -->
    <div v-if="filteredUsers.length" class="fx-grid">
      <div v-for="u in filteredUsers" :key="u.id" class="fx-card user-card">
        <div class="user-card-head">
          <div class="user-ident">
            <span class="user-name">{{ u.name || u.user }}</span>
            <span class="user-account">@{{ u.user }}</span>
          </div>
          <NTag size="small" :bordered="false" :type="getUserStatus(u).type">
            {{ getUserStatus(u).text }}
          </NTag>
        </div>

        <div class="user-stat">
          <div class="stat-row">
            <span class="stat-label">流量限制</span>
            <span class="stat-value">{{ formatFlowPlain(u.flow, 'gb') }}</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">已使用</span>
            <span class="stat-value" :style="{ color: COLOR_DANGER }">
              {{ formatFlowPlain(calculateUserTotalUsedFlow(u)) }}
            </span>
          </div>
          <NProgress
            type="line"
            :height="6"
            :show-indicator="false"
            :percentage="usagePercent(u.flow, calculateUserTotalUsedFlow(u))"
            :color="usageColor(usagePercent(u.flow, calculateUserTotalUsedFlow(u)))"
            :aria-label="`流量使用 ${usagePercent(u.flow, calculateUserTotalUsedFlow(u)).toFixed(1)}%`"
          />
        </div>

        <div class="user-meta">
          <div class="stat-row">
            <span class="stat-label">转发数量</span>
            <span class="stat-value">{{ u.num }}</span>
          </div>
          <div class="stat-row">
            <span class="stat-label">重置日期</span>
            <span class="stat-value">{{ resetTimeText(u.flowResetTime) }}</span>
          </div>
          <div v-if="u.expTime" class="stat-row">
            <span class="stat-label">过期时间</span>
            <span v-if="getExpireStatus(u.expTime).type === 'success'" class="stat-value">
              {{ formatDate(u.expTime) }}
            </span>
            <NTag v-else size="small" :bordered="false" :type="getExpireStatus(u.expTime).type">
              {{ getExpireStatus(u.expTime).text }}
            </NTag>
          </div>
        </div>

        <div class="user-actions">
          <NButton size="small" type="primary" secondary @click="openEditUser(u)">
            <template #icon><NIcon :component="CreateOutline" /></template>
            编辑
          </NButton>
          <NButton size="small" type="warning" secondary @click="openResetUser(u)">
            <template #icon><NIcon :component="RefreshOutline" /></template>
            重置
          </NButton>
          <NButton size="small" type="success" secondary @click="openTunnelModal(u)">
            <template #icon><NIcon :component="SettingsOutline" /></template>
            权限
          </NButton>
          <NButton size="small" type="error" secondary @click="openDeleteUser(u)">
            <template #icon><NIcon :component="TrashOutline" /></template>
            删除
          </NButton>
        </div>
      </div>
    </div>

    <EmptyState v-else title="暂无用户数据" description="还没有创建任何用户，点击上方按钮开始创建">
      <template #icon>
        <NIcon :component="PersonOutline" size="30" />
      </template>
    </EmptyState>

    <!-- 用户表单弹窗 -->
    <NModal
      :show="showUserModal"
      preset="card"
      :title="isEditUser ? '编辑用户' : '新增用户'"
      style="width: 640px; max-width: 95vw"
      :bordered="false"
      @update:show="(v: boolean) => (showUserModal = v)"
    >
      <div class="form-grid-2">
        <div class="form-item">
          <label class="form-label">用户名</label>
          <NInput v-model:value="userForm.user" placeholder="请输入用户名" />
        </div>
        <div class="form-item">
          <label class="form-label">密码</label>
          <NInput
            v-model:value="userForm.pwd"
            type="password"
            show-password-on="click"
            :placeholder="isEditUser ? '留空则不修改密码' : '请输入密码'"
          />
        </div>
        <div class="form-item">
          <label class="form-label">流量限制(GB)</label>
          <NInputNumber
            :value="userForm.flow"
            :min="1"
            :max="99999"
            style="width: 100%"
            @update:value="(v) => (userForm.flow = clamp(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">转发数量</label>
          <NInputNumber
            :value="userForm.num"
            :min="1"
            :max="99999"
            style="width: 100%"
            @update:value="(v) => (userForm.num = clamp(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">流量重置日期</label>
          <NSelect
            :value="userForm.flowResetTime"
            :options="resetDayOptions"
            @update:value="(v) => (userForm.flowResetTime = Number(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">过期时间</label>
          <NDatePicker
            type="date"
            style="width: 100%"
            :value="userForm.expTime"
            @update:value="(v) => (userForm.expTime = normalizeExp(v))"
          />
        </div>
        <div class="form-item form-item-full">
          <label class="form-label">状态</label>
          <NRadioGroup v-model:value="userForm.status">
            <NRadio :value="1">正常</NRadio>
            <NRadio :value="0">禁用</NRadio>
          </NRadioGroup>
        </div>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showUserModal = false">取消</NButton>
          <NButton type="primary" :loading="submittingUser" @click="handleSubmitUser">确定</NButton>
        </div>
      </template>
    </NModal>

    <!-- 删除用户弹窗 -->
    <NModal
      :show="showDeleteUser"
      preset="card"
      style="width: 460px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showDeleteUser = v)"
    >
      <template #header><span class="danger-title">确认删除用户</span></template>
      <div class="confirm-body">
        <p>确定要删除用户 "{{ userToDelete?.user }}" 吗？</p>
        <p class="text-secondary">此操作不可撤销，用户的所有数据将被永久删除。</p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showDeleteUser = false">取消</NButton>
          <NButton type="error" @click="confirmDeleteUser">确认删除</NButton>
        </div>
      </template>
    </NModal>

    <!-- 重置用户流量弹窗 -->
    <NModal
      :show="showResetUser"
      preset="card"
      style="width: 480px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showResetUser = v)"
    >
      <template #header><span class="warn-title">确认重置流量</span></template>
      <div class="confirm-body">
        <p>确定要重置用户 "{{ userToReset?.user }}" 的流量吗？</p>
        <p class="text-secondary">
          该操作只会重置账号流量不会重置隧道权限流量，重置后该用户的上下行流量将归零，此操作不可撤销。
        </p>
        <div class="flow-box">
          <div class="flow-box-title">当前流量使用情况：</div>
          <div class="flow-box-row">
            <span>上行流量：</span>
            <span class="fx-mono">{{ userToReset ? formatFlowPlain(userToReset.inFlow || 0) : '-' }}</span>
          </div>
          <div class="flow-box-row">
            <span>下行流量：</span>
            <span class="fx-mono">{{ userToReset ? formatFlowPlain(userToReset.outFlow || 0) : '-' }}</span>
          </div>
          <div class="flow-box-row">
            <span>总计：</span>
            <span class="fx-mono">
              {{ userToReset ? formatFlowPlain(calculateUserTotalUsedFlow(userToReset)) : '-' }}
            </span>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showResetUser = false">取消</NButton>
          <NButton type="warning" :loading="resettingUser" @click="confirmResetUser">确认重置</NButton>
        </div>
      </template>
    </NModal>

    <!-- 隧道权限管理弹窗 -->
    <NModal
      :show="showTunnelModal"
      preset="card"
      :title="`用户 ${currentUser?.user} 的隧道权限管理`"
      style="width: 960px; max-width: 95vw"
      :bordered="false"
      :mask-closable="false"
      @update:show="(v: boolean) => (showTunnelModal = v)"
    >
      <!-- 分配新权限 -->
      <div class="section-title">分配新权限</div>
      <div class="form-grid-2">
        <div class="form-item">
          <label class="form-label">选择隧道</label>
          <NSelect
            :value="assignForm.tunnelId"
            :options="availableTunnels"
            placeholder="请选择隧道"
            @update:value="onAssignTunnelChange"
          />
        </div>
        <div class="form-item">
          <label class="form-label">限速规则</label>
          <NSelect
            :value="assignForm.speedId"
            :options="assignSpeedOptions"
            :disabled="!assignForm.tunnelId"
            @update:value="(v) => (assignForm.speedId = v as number | 'null')"
          />
        </div>
        <div class="form-item">
          <label class="form-label">流量限制(GB)</label>
          <NInputNumber
            :value="assignForm.flow"
            :min="1"
            :max="99999"
            style="width: 100%"
            @update:value="(v) => (assignForm.flow = clamp(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">转发数量</label>
          <NInputNumber
            :value="assignForm.num"
            :min="1"
            :max="99999"
            style="width: 100%"
            @update:value="(v) => (assignForm.num = clamp(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">流量重置日期</label>
          <NSelect
            :value="assignForm.flowResetTime"
            :options="resetDayOptions"
            @update:value="(v) => (assignForm.flowResetTime = Number(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">到期时间</label>
          <NDatePicker
            type="date"
            style="width: 100%"
            :value="assignForm.expTime"
            @update:value="(v) => (assignForm.expTime = normalizeExp(v))"
          />
        </div>
      </div>
      <div class="assign-btn-row">
        <NButton type="primary" :loading="assignLoading" @click="handleAssignTunnel">分配权限</NButton>
      </div>

      <!-- 已有权限 -->
      <div class="section-title section-title-gap">已有权限</div>
      <NDataTable
        :columns="tunnelColumns"
        :data="userTunnels"
        :loading="tunnelListLoading"
        :bordered="false"
        size="small"
      >
        <template #empty>暂无隧道权限</template>
      </NDataTable>

      <template #footer>
        <div class="modal-footer">
          <NButton @click="showTunnelModal = false">关闭</NButton>
        </div>
      </template>
    </NModal>

    <!-- 编辑隧道权限弹窗 -->
    <NModal
      :show="showEditTunnel"
      preset="card"
      :title="`编辑隧道权限 - ${editTunnelForm.tunnelName}`"
      style="width: 640px; max-width: 95vw"
      :bordered="false"
      :mask-closable="false"
      @update:show="(v: boolean) => (showEditTunnel = v)"
    >
      <div class="form-grid-2">
        <div class="form-item">
          <label class="form-label">流量限制(GB)</label>
          <NInputNumber
            :value="editTunnelForm.flow"
            :min="1"
            :max="99999"
            style="width: 100%"
            @update:value="(v) => (editTunnelForm.flow = clamp(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">转发数量</label>
          <NInputNumber
            :value="editTunnelForm.num"
            :min="1"
            :max="99999"
            style="width: 100%"
            @update:value="(v) => (editTunnelForm.num = clamp(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">限速规则</label>
          <NSelect
            :value="editTunnelForm.speedId"
            :options="editSpeedOptions"
            @update:value="(v) => (editTunnelForm.speedId = v as number | 'null')"
          />
        </div>
        <div class="form-item">
          <label class="form-label">流量重置日期</label>
          <NSelect
            :value="editTunnelForm.flowResetTime"
            :options="resetDayOptions"
            @update:value="(v) => (editTunnelForm.flowResetTime = Number(v))"
          />
        </div>
        <div class="form-item">
          <label class="form-label">到期时间</label>
          <NDatePicker
            type="date"
            style="width: 100%"
            :value="editTunnelForm.expTime"
            @update:value="(v) => (editTunnelForm.expTime = v == null ? Date.now() : normalizeExp(v))"
          />
        </div>
        <div class="form-item form-item-full">
          <label class="form-label">状态</label>
          <NRadioGroup v-model:value="editTunnelForm.status">
            <NRadio :value="1">正常</NRadio>
            <NRadio :value="0">禁用</NRadio>
          </NRadioGroup>
        </div>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showEditTunnel = false">取消</NButton>
          <NButton type="primary" :loading="editingTunnel" @click="handleUpdateTunnel">确定</NButton>
        </div>
      </template>
    </NModal>

    <!-- 删除隧道权限弹窗 -->
    <NModal
      :show="showDeleteTunnel"
      preset="card"
      style="width: 480px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showDeleteTunnel = v)"
    >
      <template #header><span class="danger-title">确认删除隧道权限</span></template>
      <div class="confirm-body">
        <p>确定要删除用户 {{ currentUser?.user }} 对隧道 "{{ tunnelToDelete?.tunnelName }}" 的权限吗？</p>
        <p class="text-secondary">删除后该用户将无法使用此隧道创建转发，此操作不可撤销。</p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showDeleteTunnel = false">取消</NButton>
          <NButton type="error" @click="confirmDeleteTunnel">确认删除</NButton>
        </div>
      </template>
    </NModal>

    <!-- 重置隧道流量弹窗 -->
    <NModal
      :show="showResetTunnel"
      preset="card"
      style="width: 480px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showResetTunnel = v)"
    >
      <template #header><span class="warn-title">确认重置隧道流量</span></template>
      <div class="confirm-body">
        <p>确定要重置用户 {{ currentUser?.user }} 对隧道 "{{ tunnelToReset?.tunnelName }}" 的流量吗？</p>
        <p class="text-secondary">
          该操作只会重置隧道权限流量不会重置账号流量，重置后该隧道权限的上下行流量将归零，此操作不可撤销。
        </p>
        <div class="flow-box">
          <div class="flow-box-title">当前流量使用情况：</div>
          <div class="flow-box-row">
            <span>上行流量：</span>
            <span class="fx-mono">{{ tunnelToReset ? formatFlowPlain(tunnelToReset.inFlow || 0) : '-' }}</span>
          </div>
          <div class="flow-box-row">
            <span>下行流量：</span>
            <span class="fx-mono">{{ tunnelToReset ? formatFlowPlain(tunnelToReset.outFlow || 0) : '-' }}</span>
          </div>
          <div class="flow-box-row">
            <span>总计：</span>
            <span class="fx-mono">
              {{ tunnelToReset ? formatFlowPlain(calculateTunnelUsedFlow(tunnelToReset)) : '-' }}
            </span>
          </div>
        </div>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showResetTunnel = false">取消</NButton>
          <NButton type="warning" :loading="resettingTunnel" @click="confirmResetTunnel">确认重置</NButton>
        </div>
      </template>
    </NModal>
  </PageContainer>
</template>

<style scoped>
.user-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.user-search {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  max-width: 420px;
}
.user-search :deep(.n-input) {
  flex: 1;
}

.user-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
  background: var(--bg-elevated);
  border: 1px solid var(--border-soft);
  box-shadow: var(--shadow-card);
}
.user-card-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 10px;
}
.user-ident {
  display: flex;
  flex-direction: column;
  min-width: 0;
}
.user-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.user-account {
  font-size: 12px;
  color: var(--text-secondary);
}

.user-stat,
.user-meta {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.stat-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.stat-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.stat-value {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}

.user-actions {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 2px;
}

/* 表单 */
.form-grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.form-item-full {
  grid-column: 1 / -1;
}
.form-label {
  font-size: 13px;
  font-weight: 500;
  color: var(--text-primary);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
}

/* 弹窗标题 / 正文 */
.danger-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--danger);
}
.warn-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--warn);
}
.confirm-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
  color: var(--text-primary);
}
.confirm-body p {
  margin: 0;
}

.flow-box {
  margin-top: 6px;
  padding: 12px;
  border-radius: var(--radius-md);
  background: rgba(240, 160, 32, 0.1);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.flow-box-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}
.flow-box-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  color: var(--text-secondary);
}

/* 隧道权限管理 */
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin-bottom: 12px;
}
.section-title-gap {
  margin-top: 24px;
}
.assign-btn-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 14px;
  margin-bottom: 4px;
}

/* 表格单元 */
.cell-flow {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
:deep(.cell-label) {
  color: var(--text-secondary);
}
.cell-actions {
  display: flex;
  gap: 4px;
}

@media (max-width: 768px) {
  .user-header {
    flex-direction: column;
    align-items: stretch;
  }
  .user-search {
    max-width: none;
  }
  .form-grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
