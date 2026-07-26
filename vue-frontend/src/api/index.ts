import { post } from './http'
import type {
  ApiResponse,
  LoginData,
  LoginResponse,
  ForwardPortAvailability,
} from '@/types'

export { reinitializeBaseURL, getBaseURL } from './http'

// ============ 用户 / 登录 ============
export const login = (data: LoginData) => post<LoginResponse>('/user/login', data)
export const createUser = (data: any) => post('/user/create', data)
export const getAllUsers = (pageData: any = {}) => post<any[]>('/user/list', pageData)
export const updateUser = (data: any) => post('/user/update', data)
export const deleteUser = (id: number) => post('/user/delete', { id })
export const getUserPackageInfo = () => post('/user/package', {})
export const updatePassword = (data: {
  newUsername: string
  currentPassword: string
  newPassword: string
  confirmPassword: string
}) => post('/user/updatePassword', data)
export const resetUserFlow = (data: { id: number; type: number }) => post('/user/reset', data)

// ============ 节点 ============
export const createNode = (data: any) => post('/node/create', data)
export const getNodeList = () => post<any[]>('/node/list', {})
export const updateNode = (data: any) => post('/node/update', data)
export const deleteNode = (id: number) => post('/node/delete', { id })
export const getNodeInstallCommand = (id: number) => post<string>('/node/install', { id })
export const checkNodeStatus = (nodeId?: number) =>
  post('/node/check-status', nodeId ? { nodeId } : {})

// ============ 隧道 ============
export const createTunnel = (data: any) => post('/tunnel/create', data)
export const getTunnelList = () => post<any[]>('/tunnel/list', {})
export const getTunnelById = (id: number) => post('/tunnel/get', { id })
export const updateTunnel = (data: any) => post('/tunnel/update', data)
export const deleteTunnel = (id: number) => post('/tunnel/delete', { id })
export const diagnoseTunnel = (tunnelId: number) => post('/tunnel/diagnose', { tunnelId })
export const assignUserTunnel = (data: any) => post('/tunnel/user/assign', data)
export const getUserTunnelList = (queryData: any = {}) => post<any[]>('/tunnel/user/list', queryData)
export const removeUserTunnel = (params: { id: number }) => post('/tunnel/user/remove', params)
export const updateUserTunnel = (data: any) => post('/tunnel/user/update', data)
export const userTunnel = () => post<any[]>('/tunnel/user/tunnel', {})

// ============ 转发 ============
export const createForward = (data: any) => post('/forward/create', data)
export const getForwardList = () => post<any[]>('/forward/list', {})
export const updateForward = (data: any) => post('/forward/update', data)
export const retryForwardSync = (id: number) => post('/forward/sync/retry', { id })
export const checkForwardPort = (data: {
  tunnelId: number
  inPort: number
  excludeForwardId?: number
}) => post<ForwardPortAvailability>('/forward/check-port', data)
export const deleteForward = (id: number) => post('/forward/delete', { id })
export const forceDeleteForward = (id: number) => post('/forward/force-delete', { id })
export const pauseForwardService = (forwardId: number) => post('/forward/pause', { id: forwardId })
export const resumeForwardService = (forwardId: number) => post('/forward/resume', { id: forwardId })
export const diagnoseForward = (forwardId: number) => post('/forward/diagnose', { forwardId })
export const updateForwardOrder = (data: { forwards: { id: number; inx: number }[] }) =>
  post('/forward/update-order', data)

// ============ 限速 ============
export const createSpeedLimit = (data: any) => post('/speed-limit/create', data)
export const getSpeedLimitList = () => post<any[]>('/speed-limit/list', {})
export const updateSpeedLimit = (data: any) => post('/speed-limit/update', data)
export const deleteSpeedLimit = (id: number) => post('/speed-limit/delete', { id })

// ============ 配置 ============
export const getConfigs = () => post<Record<string, string>>('/config/list', {})
export const getConfigByName = (name: string) => post<any>('/config/get', { name })
export const updateConfigs = (configMap: Record<string, string>) => post('/config/update', configMap)
export const updateConfig = (name: string, value: string) =>
  post('/config/update-single', { name, value })

// ============ 验证码 ============
export const checkCaptcha = () => post<number>('/captcha/check', {})
export const generateCaptcha = () => post('/captcha/generate', {})
export const verifyCaptcha = (data: { captchaId: string; trackData: string }) =>
  post('/captcha/verify', data)

export type { ApiResponse }
