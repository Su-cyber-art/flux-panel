import { defineStore } from 'pinia'
import { isLoggedIn as checkLoggedIn, isAdmin as checkAdmin } from '@/utils/auth'
import { getRoleIdFromToken } from '@/utils/jwt'

interface AuthState {
  token: string | null
  name: string
  roleId: number | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem('token'),
    name: localStorage.getItem('name') || 'Admin',
    roleId: (() => {
      const r = localStorage.getItem('role_id')
      return r != null ? parseInt(r, 10) : null
    })(),
  }),
  getters: {
    isLoggedIn: () => checkLoggedIn(),
    isAdmin: (state) => {
      if (state.roleId != null) return state.roleId === 0
      return checkAdmin()
    },
  },
  actions: {
    setSession(token: string, roleId: number, name: string) {
      this.token = token
      this.roleId = roleId
      this.name = name
      localStorage.setItem('token', token)
      localStorage.setItem('role_id', String(roleId))
      localStorage.setItem('name', name)
      localStorage.setItem('admin', String(roleId === 0))
    },
    refreshFromStorage() {
      this.token = localStorage.getItem('token')
      this.name = localStorage.getItem('name') || 'Admin'
      const r = localStorage.getItem('role_id')
      this.roleId = r != null ? parseInt(r, 10) : getRoleIdFromToken()
    },
    logout() {
      this.token = null
      this.roleId = null
      this.name = 'Admin'
      localStorage.clear()
    },
  },
})
