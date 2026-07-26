export interface JWTPayload {
  sub: string
  role_id: number
  user: string
  exp: number
  iat: number
}

export function getPayloadFromToken(token: string): JWTPayload | null {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const json = decodeURIComponent(
      atob(parts[1].replace(/-/g, '+').replace(/_/g, '/'))
        .split('')
        .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
        .join(''),
    )
    return JSON.parse(json)
  } catch {
    try {
      const parts = token.split('.')
      return JSON.parse(atob(parts[1]))
    } catch {
      return null
    }
  }
}

export function getUserIdFromToken(token?: string | null): number | null {
  const t = token ?? localStorage.getItem('token')
  if (!t) return null
  const p = getPayloadFromToken(t)
  return p ? parseInt(p.sub, 10) : null
}

export function getRoleIdFromToken(token?: string | null): number | null {
  const t = token ?? localStorage.getItem('token')
  if (!t) return null
  const p = getPayloadFromToken(t)
  return p ? p.role_id : null
}

export function getUsernameFromToken(token?: string | null): string | null {
  const t = token ?? localStorage.getItem('token')
  if (!t) return null
  const p = getPayloadFromToken(t)
  return p ? p.user : null
}

export function isTokenValid(token: string): boolean {
  const p = getPayloadFromToken(token)
  if (!p) return false
  return p.exp > Math.floor(Date.now() / 1000)
}

export const JwtUtil = {
  getUserIdFromToken: () => getUserIdFromToken(),
  getRoleIdFromToken: () => getRoleIdFromToken(),
  getUsernameFromToken: () => getUsernameFromToken(),
  isTokenValid: () => {
    const t = localStorage.getItem('token')
    return !!t && isTokenValid(t)
  },
}
