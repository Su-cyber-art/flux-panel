import { getRoleIdFromToken, isTokenValid } from './jwt'

export function getToken(): string | null {
  return localStorage.getItem('token')
}

export function getCurrentUserRoleId(): number | null {
  const token = getToken()
  if (!token || !isTokenValid(token)) return null
  return getRoleIdFromToken(token)
}

export function isAdmin(): boolean {
  return getCurrentUserRoleId() === 0
}

export function hasRole(target: number): boolean {
  return getCurrentUserRoleId() === target
}

export function isLoggedIn(): boolean {
  const token = getToken()
  return !!token && isTokenValid(token)
}
