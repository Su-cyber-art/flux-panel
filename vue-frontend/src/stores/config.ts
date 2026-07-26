import { defineStore } from 'pinia'
import { getConfigByName, getConfigs } from '@/api'

const CACHE_PREFIX = 'vite_config_'
const APP_VERSION = '2.0.0'
const VERSION = (import.meta.env.VITE_APP_VERSION as string) || 'dev'

function cacheGet(key: string): string | null {
  return localStorage.getItem(CACHE_PREFIX + key)
}
function cacheSet(key: string, value: string): void {
  localStorage.setItem(CACHE_PREFIX + key, value)
}
export function clearConfigCache(keys?: string[]): void {
  if (keys && keys.length) {
    keys.forEach((k) => localStorage.removeItem(CACHE_PREFIX + k))
    return
  }
  Object.keys(localStorage)
    .filter((k) => k.startsWith(CACHE_PREFIX))
    .forEach((k) => localStorage.removeItem(k))
}

export const useConfigStore = defineStore('config', {
  state: () => ({
    name: cacheGet('app_name') || 'flux',
    version: VERSION,
    appVersion: APP_VERSION,
  }),
  actions: {
    applyTitle() {
      document.title = this.name || 'flux-panel'
    },
    async getCachedConfig(key: string): Promise<string | null> {
      const cached = cacheGet(key)
      if (cached != null) return cached
      try {
        const res = await getConfigByName(key)
        if (res.code === 0 && res.data?.value != null) {
          cacheSet(key, res.data.value)
          return res.data.value
        }
      } catch {
        /* ignore */
      }
      return null
    },
    async loadConfigs(): Promise<Record<string, string>> {
      try {
        const res = await getConfigs()
        if (res.code === 0 && res.data) {
          Object.entries(res.data).forEach(([k, v]) => cacheSet(k, String(v)))
          if (res.data.app_name && res.data.app_name !== this.name) {
            this.name = res.data.app_name
            this.applyTitle()
          }
          return res.data
        }
      } catch {
        /* ignore */
      }
      return {}
    },
    async syncAppName() {
      const name = await this.getCachedConfig('app_name')
      if (name && name !== this.name) {
        this.name = name
        this.applyTitle()
      }
    },
  },
})
