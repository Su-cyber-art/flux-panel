<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { NButton, NModal, NInput, NInputNumber, NSelect, NTag } from 'naive-ui'
import PageContainer from '@/components/PageContainer.vue'
import EmptyState from '@/components/EmptyState.vue'
import { useToast } from '@/composables/useToast'
import {
  getSpeedLimitList,
  getTunnelList,
  createSpeedLimit,
  updateSpeedLimit,
  deleteSpeedLimit,
} from '@/api'
import type { SpeedLimitRule, SpeedLimitForm } from '@/types'

interface Tunnel {
  id: number
  name: string
}

const toast = useToast()

const loading = ref(true)
const rules = ref<SpeedLimitRule[]>([])
const tunnels = ref<Tunnel[]>([])

const tunnelOptions = computed(() =>
  tunnels.value.map((t) => ({ label: t.name, value: t.id }))
)

function defaultForm(): SpeedLimitForm {
  return { name: '', speed: 100, tunnelId: null, tunnelName: '', status: 1 }
}

// ============ 加载 ============
async function load() {
  loading.value = true
  try {
    const [rulesRes, tunnelsRes] = await Promise.all([getSpeedLimitList(), getTunnelList()])
    if (rulesRes.code === 0) {
      rules.value = rulesRes.data || []
    } else {
      toast.error(rulesRes.msg || '获取限速规则失败')
    }
    if (tunnelsRes.code === 0) {
      tunnels.value = tunnelsRes.data || []
    } else {
      console.warn('获取隧道列表失败:', tunnelsRes.msg)
    }
  } catch (e) {
    console.warn('加载数据失败:', e)
    toast.error('加载数据失败')
  } finally {
    loading.value = false
  }
}

// ============ 新增 / 编辑 ============
const showModal = ref(false)
const submitting = ref(false)
const form = ref<SpeedLimitForm>(defaultForm())
const isEdit = computed(() => form.value.id != null)

function openCreate() {
  form.value = defaultForm()
  showModal.value = true
}

function openEdit(rule: SpeedLimitRule) {
  form.value = {
    id: rule.id,
    name: rule.name,
    speed: rule.speed,
    tunnelId: rule.tunnelId ?? null,
    tunnelName: rule.tunnelName || '',
    status: rule.status,
  }
  showModal.value = true
}

function onTunnelSelect(value: number | null) {
  form.value.tunnelId = value
  form.value.tunnelName = tunnels.value.find((t) => t.id === value)?.name || ''
}

function validate(): string | null {
  const name = (form.value.name || '').trim()
  if (!name) return '请输入规则名称'
  if (name.length < 2 || name.length > 50) return '规则名称长度应在2-50个字符之间'
  if (!form.value.speed || form.value.speed < 1) return '请输入有效的速度限制（≥1 Mbps）'
  if (!form.value.tunnelId) return '请选择要绑定的隧道'
  return null
}

async function submit() {
  const err = validate()
  if (err) {
    toast.error(err)
    return
  }
  submitting.value = true
  try {
    let res
    if (isEdit.value) {
      res = await updateSpeedLimit({ ...form.value })
    } else {
      const { id, ...rest } = form.value
      res = await createSpeedLimit(rest)
    }
    if (res.code === 0) {
      toast.success(isEdit.value ? '修改成功' : '创建成功')
      showModal.value = false
      await load()
    } else {
      toast.error(res.msg || '操作失败')
    }
  } catch (e) {
    console.warn('操作失败:', e)
    toast.error('操作失败')
  } finally {
    submitting.value = false
  }
}

// ============ 删除 ============
const showDelete = ref(false)
const deleting = ref(false)
const ruleToDelete = ref<SpeedLimitRule | null>(null)

function openDelete(rule: SpeedLimitRule) {
  ruleToDelete.value = rule
  showDelete.value = true
}

async function confirmDelete() {
  if (!ruleToDelete.value) return
  deleting.value = true
  try {
    const res = await deleteSpeedLimit(ruleToDelete.value.id)
    if (res.code === 0) {
      toast.success('删除成功')
      showDelete.value = false
      ruleToDelete.value = null
      await load()
    } else {
      toast.error(res.msg || '删除失败')
    }
  } catch (e) {
    console.warn('删除失败:', e)
    toast.error('删除失败')
  } finally {
    deleting.value = false
  }
}

onMounted(load)
</script>

<template>
  <PageContainer :loading="loading">
    <div class="limit-header">
      <div>
        <h1 class="limit-title">限速规则</h1>
        <p class="limit-subtitle">管理隧道的速度限制规则</p>
      </div>
      <NButton type="primary" @click="openCreate">新增</NButton>
    </div>

    <div v-if="rules.length" class="fx-grid">
      <div v-for="rule in rules" :key="rule.id" class="fx-card rule-card">
        <div class="rule-card-head">
          <span class="rule-name">{{ rule.name }}</span>
          <NTag
            size="small"
            :bordered="false"
            :type="rule.status === 1 ? 'success' : 'error'"
          >
            {{ rule.status === 1 ? '运行' : '异常' }}
          </NTag>
        </div>

        <div class="rule-row">
          <span class="rule-label">速度限制</span>
          <NTag size="small" :bordered="false" type="default">{{ rule.speed }} Mbps</NTag>
        </div>

        <div class="rule-row">
          <span class="rule-label">绑定隧道</span>
          <NTag v-if="rule.tunnelName" size="small" :bordered="false" type="primary">
            {{ rule.tunnelName }}
          </NTag>
          <span v-else class="rule-unbound">未绑定</span>
        </div>

        <div class="rule-actions">
          <NButton size="small" type="primary" secondary class="rule-btn" @click="openEdit(rule)">
            编辑
          </NButton>
          <NButton size="small" type="error" secondary class="rule-btn" @click="openDelete(rule)">
            删除
          </NButton>
        </div>
      </div>
    </div>

    <EmptyState
      v-else
      title="暂无限速规则"
      description="还没有创建任何限速规则，点击上方按钮开始创建"
    />

    <!-- 新增 / 编辑 弹窗 -->
    <NModal
      :show="showModal"
      preset="card"
      :title="isEdit ? '编辑限速规则' : '新增限速规则'"
      style="width: 520px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showModal = v)"
    >
      <div class="form-grid">
        <div class="form-item">
          <label class="form-label">规则名称</label>
          <NInput v-model:value="form.name" placeholder="请输入限速规则名称" />
        </div>

        <div class="form-item">
          <label class="form-label">速度限制</label>
          <NInputNumber
            v-model:value="form.speed"
            :min="1"
            placeholder="请输入速度限制"
            style="width: 100%"
          >
            <template #suffix>Mbps</template>
          </NInputNumber>
        </div>

        <div class="form-item">
          <label class="form-label">绑定隧道</label>
          <NSelect
            :value="form.tunnelId"
            :options="tunnelOptions"
            placeholder="请选择要绑定的隧道"
            @update:value="onTunnelSelect"
          />
        </div>
      </div>

      <template #footer>
        <div class="modal-footer">
          <NButton @click="showModal = false">取消</NButton>
          <NButton type="primary" :loading="submitting" @click="submit">
            {{ isEdit ? '保存修改' : '创建规则' }}
          </NButton>
        </div>
      </template>
    </NModal>

    <!-- 删除确认 弹窗 -->
    <NModal
      :show="showDelete"
      preset="card"
      style="width: 460px; max-width: 94vw"
      :bordered="false"
      @update:show="(v: boolean) => (showDelete = v)"
    >
      <template #header>
        <span class="delete-title">确认删除</span>
      </template>
      <div class="delete-body">
        <p>确定要删除限速规则 "{{ ruleToDelete?.name }}" 吗？</p>
        <p class="text-secondary">此操作无法撤销，删除后该规则将永久消失。</p>
      </div>
      <template #footer>
        <div class="modal-footer">
          <NButton @click="showDelete = false">取消</NButton>
          <NButton type="error" :loading="deleting" @click="confirmDelete">确认删除</NButton>
        </div>
      </template>
    </NModal>
  </PageContainer>
</template>

<style scoped>
.limit-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 20px;
}
.limit-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0;
}
.limit-subtitle {
  font-size: 13px;
  color: var(--text-secondary);
  margin: 4px 0 0;
}

.rule-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 16px;
}
.rule-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.rule-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rule-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}
.rule-label {
  font-size: 13px;
  color: var(--text-secondary);
}
.rule-unbound {
  font-size: 13px;
  color: var(--text-secondary);
}
.rule-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
.rule-btn {
  flex: 1;
}

.form-grid {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
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

.delete-title {
  font-size: 16px;
  font-weight: 600;
  color: var(--error-color, #d03050);
}
.delete-body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
  color: var(--text-primary);
}
.delete-body p {
  margin: 0;
}

@media (max-width: 768px) {
  .limit-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
