import { computed, ref } from 'vue'
import { darkTheme, type GlobalTheme } from 'naive-ui'

export type ThemeMode = 'auto' | 'light' | 'dark'

const STORAGE_KEY = 'theme-mode'

function prefersDark(): boolean {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

const mode = ref<ThemeMode>((localStorage.getItem(STORAGE_KEY) as ThemeMode) || 'auto')
const systemDark = ref(prefersDark())

window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', (e) => {
  systemDark.value = e.matches
})

const isDark = computed(() => {
  if (mode.value === 'dark') return true
  if (mode.value === 'light') return false
  return systemDark.value
})

function applyHtmlClass() {
  document.documentElement.classList.toggle('dark', isDark.value)
  document.documentElement.style.colorScheme = isDark.value ? 'dark' : 'light'
}

/** 全局主题 composable，供 App 提供 Naive 的 theme，供 header 提供切换按钮 */
export function useTheme() {
  const naiveTheme = computed<GlobalTheme | null>(() => (isDark.value ? darkTheme : null))

  function setMode(next: ThemeMode) {
    mode.value = next
    localStorage.setItem(STORAGE_KEY, next)
    applyHtmlClass()
  }

  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }

  applyHtmlClass()

  return { mode, isDark, naiveTheme, setMode, toggle }
}
