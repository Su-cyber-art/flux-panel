import {
  GridOutline,
  SwapHorizontalOutline,
  GitNetworkOutline,
  HardwareChipOutline,
  SpeedometerOutline,
  PeopleOutline,
  SettingsOutline,
  PersonOutline,
} from '@vicons/ionicons5'
import type { Component } from 'vue'

export interface MenuEntry {
  path: string
  label: string
  adminOnly: boolean
  icon: Component
}

export const menuItems: MenuEntry[] = [
  { path: '/dashboard', label: '仪表板', adminOnly: false, icon: GridOutline },
  { path: '/forward', label: '转发管理', adminOnly: false, icon: SwapHorizontalOutline },
  { path: '/tunnel', label: '隧道管理', adminOnly: true, icon: GitNetworkOutline },
  { path: '/node', label: '节点监控', adminOnly: true, icon: HardwareChipOutline },
  { path: '/limit', label: '限速管理', adminOnly: true, icon: SpeedometerOutline },
  { path: '/user', label: '用户管理', adminOnly: true, icon: PeopleOutline },
  { path: '/config', label: '网站配置', adminOnly: true, icon: SettingsOutline },
]

export const h5Tabs: MenuEntry[] = [
  { path: '/dashboard', label: '首页', adminOnly: false, icon: GridOutline },
  { path: '/forward', label: '转发', adminOnly: false, icon: SwapHorizontalOutline },
  { path: '/tunnel', label: '隧道', adminOnly: true, icon: GitNetworkOutline },
  { path: '/node', label: '节点', adminOnly: true, icon: HardwareChipOutline },
  { path: '/profile', label: '我的', adminOnly: false, icon: PersonOutline },
]
