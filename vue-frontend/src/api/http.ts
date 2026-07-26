import axios, { type AxiosRequestConfig } from 'axios'
import type { ApiResponse } from '@/types'
import { isWebViewFunc, getPanelAddresses } from '@/utils/panel'

let baseURL = ''

export function reinitializeBaseURL(): void {
  if (isWebViewFunc()) {
    // WebView：由原生宿主回调 window.setAddresses 注入面板地址
    ;(window as any).setAddresses = (list: Array<{ name: string; address: string; inx: any }>) => {
      const current = (list || []).find((i) => i.inx)
      if (current) {
        baseURL = `${current.address}/api/v1/`
        axios.defaults.baseURL = baseURL
      }
    }
    getPanelAddresses('setAddresses')
  } else {
    baseURL = import.meta.env.VITE_API_BASE ? `${import.meta.env.VITE_API_BASE}/api/v1/` : '/api/v1/'
    axios.defaults.baseURL = baseURL
  }
}
reinitializeBaseURL()

export function getBaseURL(): string {
  return axios.defaults.baseURL || baseURL || '/api/v1/'
}

function isTokenExpired(resp: ApiResponse | null): boolean {
  return (
    !!resp &&
    resp.code === 401 &&
    (resp.msg === '未登录或token已过期' ||
      resp.msg === '无效的token或token已过期' ||
      resp.msg === '无法获取用户权限信息')
  )
}

function handleTokenExpired(): void {
  localStorage.removeItem('token')
  localStorage.removeItem('role_id')
  localStorage.removeItem('name')
  localStorage.removeItem('admin')
  if (window.location.pathname !== '/') {
    window.location.href = '/'
  }
}

function authHeaders(extra?: Record<string, string>): Record<string, string> {
  const headers: Record<string, string> = { ...(extra || {}) }
  const token = window.localStorage.getItem('token')
  if (token) headers['Authorization'] = token
  return headers
}

export async function get<T = any>(url: string, params?: any): Promise<ApiResponse<T>> {
  if (getBaseURL() === '') {
    return { code: -1, msg: ' - 请先设置面板地址', data: null as any }
  }
  try {
    const config: AxiosRequestConfig = {
      baseURL: getBaseURL(),
      params,
      timeout: 30000,
      headers: authHeaders(),
    }
    const res = await axios.get<ApiResponse<T>>(url, config)
    const data = res.data
    if (isTokenExpired(data)) {
      handleTokenExpired()
      return data
    }
    return data
  } catch (error: any) {
    if (error?.response?.status === 401) handleTokenExpired()
    return { code: -1, msg: error?.message || '网络请求失败', data: null as any }
  }
}

export async function post<T = any>(url: string, body?: any): Promise<ApiResponse<T>> {
  if (getBaseURL() === '') {
    return { code: -1, msg: ' - 请先设置面板地址', data: null as any }
  }
  try {
    const config: AxiosRequestConfig = {
      baseURL: getBaseURL(),
      timeout: 30000,
      headers: authHeaders({ 'Content-Type': 'application/json' }),
    }
    const res = await axios.post<ApiResponse<T>>(url, body ?? {}, config)
    const data = res.data
    if (isTokenExpired(data)) {
      handleTokenExpired()
      return data
    }
    return data
  } catch (error: any) {
    if (error?.response?.status === 401) handleTokenExpired()
    return { code: -1, msg: error?.message || '网络请求失败', data: null as any }
  }
}
