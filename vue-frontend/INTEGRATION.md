# Vue 前端页面开发对接契约（供各页面统一遵循）

技术栈：Vue 3 `<script setup lang="ts">` + Naive UI + Pinia + vue-router + ECharts(vue-echarts)。
构建用 esbuild 转译（`vite build`），不强制类型门禁，但请写出类型正确、可编译的代码。

## 别名
`@` → `src`。所有内部导入用 `@/...`。

## 组件库
Naive UI 组件按需从 `naive-ui` 具名导入，例如：
`import { NCard, NButton, NInput, NModal, NSelect, NTag, NSwitch, NDataTable, NPagination, NDatePicker, NRadioGroup, NRadio, NAlert, NProgress, NSpin, NEmpty, NInputNumber, NSelect } from 'naive-ui'`
图标：`import { XxxOutline } from '@vicons/ionicons5'`，用 `<NIcon :component="XxxOutline" />`。
消息提示必须用：`import { useToast } from '@/composables/useToast'; const toast = useToast()`，调用 `toast.success('...')` / `toast.error('...')` / `toast.info(...)` / `toast.warning(...)`。
弹窗确认可用 Naive 的 `useDialog()`（已在 App 提供 NDialogProvider）。

## 复用组件（已存在，直接 import 使用）
- `@/components/PageContainer.vue`：`<PageContainer :loading="loading" loading-text="正在加载...">页面内容</PageContainer>`，内部已含 `.fx-page` 内边距与淡入动画。
- `@/components/EmptyState.vue`：`<EmptyState title="暂无X" description="...">`，可用 `#icon`、`#action` 具名插槽。
- `@/components/AddressModal.vue`：`<AddressModal v-model:show="show" title="入口地址" :addresses="['1.2.3.4:80', ...]" />`。
- `@/components/DiagnosisDialog.vue`：`<DiagnosisDialog v-model:show="show" :loading="loading" :report="report" title="转发诊断结果" :subtitle="name" type-label="转发服务" @retry="handleDiagnose" />`。report 为 `DiagnosisReport | null`。
- `@/components/ChangePasswordModal.vue`：`<ChangePasswordModal v-model:show="show" />`（自带表单、校验、成功后登出跳转）。

## API（全部返回 `Promise<ApiResponse<T>>`，`code===0` 为成功；Promise 永不 reject）
从 `@/api` 具名导入。签名见 `src/api/index.ts`。常用：
- 登录：`login({username,password,captchaId})` → `{token,role_id,name,requirePasswordChange?}`；`checkCaptcha()` → `data`：0 无需验证码/1 需要。
- 用户：`getAllUsers()`(返回扁平数组，后端忽略分页/keyword)、`createUser`、`updateUser`、`deleteUser(id)`、`getUserPackageInfo()`、`updatePassword`、`resetUserFlow({id,type})`(type1账号 type2隧道)。
- 节点：`getNodeList()`、`createNode`、`updateNode`、`deleteNode(id)`、`getNodeInstallCommand(id)`→`data`为命令字符串。
- 隧道：`getTunnelList()`、`createTunnel`、`updateTunnel`、`deleteTunnel(id)`、`diagnoseTunnel(tunnelId)`、`assignUserTunnel`、`getUserTunnelList({userId})`、`updateUserTunnel`、`removeUserTunnel({id})`、`userTunnel()`(当前用户可用隧道)。
- 转发：`getForwardList()`、`createForward`、`updateForward`、`deleteForward(id)`、`forceDeleteForward(id)`、`pauseForwardService(id)`、`resumeForwardService(id)`、`diagnoseForward(id)`、`checkForwardPort({tunnelId,inPort,excludeForwardId?})`→`ForwardPortAvailability`、`retryForwardSync(id)`、`updateForwardOrder({forwards:[{id,inx}]})`。
- 限速：`getSpeedLimitList()`、`createSpeedLimit`、`updateSpeedLimit`、`deleteSpeedLimit(id)`。
- 配置：`getConfigs()`→`Record`、`getConfigByName(name)`、`updateConfigs(map)`。

## Store / 工具
- `import { useAuthStore } from '@/stores/auth'`：`auth.name`、`auth.isAdmin`、`auth.roleId`、`auth.setSession(token,roleId,name)`、`auth.logout()`。
- `import { useConfigStore } from '@/stores/config'`：`config.name`、`config.version`、`config.appVersion`、`config.getCachedConfig(k)`、`config.loadConfigs()`。
- `import { safeLogout } from '@/utils/logout'`。
- `import { isAdmin, isLoggedIn } from '@/utils/auth'`。
- `import { JwtUtil, getUserIdFromToken } from '@/utils/jwt'`。
- `import { copyText } from '@/utils/clipboard'`（返回 boolean）。
- 格式化：`import { formatFlow, formatFlowPlain, formatNumber, formatSpeed, formatTraffic, formatUptime, formatDate, formatResetTime, formatInAddress, formatRemoteAddress, hasMultiple } from '@/utils/format'`。
- 面板桥：`import { isWebViewFunc, getPanelAddresses, savePanelAddress, setCurrentPanelAddress, deletePanelAddress, validatePanelAddress } from '@/utils/panel'`。
- 节点 WS：`import { useNodeSocket } from '@/composables/useNodeSocket'`，`useNodeSocket((msg)=>{ /* msg:{id,type,data} */ })`，组件卸载自动断开。

## 类型
从 `@/types` 导入：`User, UserForm, UserTunnel, Tunnel, TunnelForm, NodeItem, NodeForm, Forward, ForwardForm, ForwardPortAvailability, SpeedLimitRule, SpeedLimitForm, DiagnosisReport, DiagnosisResultItem, LoginData`。

## 样式约定
- 页面根用 `<PageContainer>`。卡片网格用 class `fx-grid`（自适应 minmax(300px,1fr)）。卡片加 class `fx-card`（悬浮微交互）。等宽文本用 class `.fx-mono`，次要文字用 `.text-secondary`。
- 优先用 CSS 变量：`var(--bg-elevated) var(--bg-subtle) var(--border-soft) var(--text-primary) var(--text-secondary) var(--brand-500)`。
- 用 scoped `<style>`。移动端（≤768px）需可用。

## 交互与状态映射（务必与旧版一致，中文文案逐字保留）
- 转发状态 `getStatusDisplay`：1→{success,'正常'} 0→{warning,'暂停'} -1→{error,'异常'} -2→{warning,'删除中'} 默认{default,'未知'}。
- 负载策略：fifo→'主备' round→'轮询' rand→'随机' hash→'哈希'。
- 隧道类型：1→'端口转发'(primary) 2→'隧道转发'(secondary)；隧道状态 1→'启用'(success) 0→'禁用'(default)。
- 计费：tunnelFlow/flow 1→'单向计费'(单向计算) 2→'双向计费'(双向计算)。
- `99999` 为流量/数量“无限制”哨兵（仅 dashboard 特殊处理）。
- 所有确认删除用弹窗；转发强制删除用二次确认。
- localStorage 键：`token role_id name admin vite_config_* forward-view-mode forward-order lastNotified`。

写完请仅返回：文件路径 + 2~3 句说明与任何与规格的偏差。不要粘贴整段代码。
