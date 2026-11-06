import { createContext, useContext } from 'react'
import type { AdminApi } from '../api/admin'

export const TOKEN_STORAGE_KEY = 'tollgate.adminToken'

export interface AuthState {
  /** Null until a token has been accepted by the server. */
  token: string | null
  /** Shown on the login screen, for example after the server rejected the stored token. */
  notice: string | null
  api: AdminApi
  /** Checks the token against the server; resolves when it was accepted, rejects with an ApiError otherwise. */
  login(candidate: string): Promise<void>
  logout(): void
}

export const AuthContext = createContext<AuthState | null>(null)

export function useAuth(): AuthState {
  const state = useContext(AuthContext)
  if (!state) throw new Error('useAuth must be used inside AuthProvider')
  return state
}
