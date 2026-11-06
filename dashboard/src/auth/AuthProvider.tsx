import { useCallback, useMemo, useState, type ReactNode } from 'react'
import { createAdminApi } from '../api/admin'
import { createClient } from '../api/client'
import { AuthContext, TOKEN_STORAGE_KEY, type AuthState } from './AuthContext'

/** Where the token lives: this tab's session storage, with memory as the fallback when storage is blocked. */
class TokenStore {
  private memory: string | null

  constructor() {
    try {
      this.memory = window.sessionStorage.getItem(TOKEN_STORAGE_KEY)
    } catch {
      this.memory = null
    }
  }

  get(): string | null {
    return this.memory
  }

  set(token: string | null) {
    this.memory = token
    try {
      if (token === null) window.sessionStorage.removeItem(TOKEN_STORAGE_KEY)
      else window.sessionStorage.setItem(TOKEN_STORAGE_KEY, token)
    } catch {
      // Without session storage the token still lives in memory for this page.
    }
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [store] = useState(() => new TokenStore())
  const [token, setToken] = useState<string | null>(() => store.get())
  const [notice, setNotice] = useState<string | null>(null)

  const expire = useCallback(() => {
    store.set(null)
    setToken(null)
    setNotice('세션이 끊겼습니다. 다시 로그인하세요.')
  }, [store])

  const api = useMemo(() => createAdminApi(createClient({ token: () => store.get(), onUnauthorized: expire })), [store, expire])

  const login = useCallback(
    async (candidate: string) => {
      const probe = createAdminApi(createClient({ token: () => candidate }))
      await probe.listProviders()
      store.set(candidate)
      setNotice(null)
      setToken(candidate)
    },
    [store],
  )

  const logout = useCallback(() => {
    store.set(null)
    setNotice(null)
    setToken(null)
  }, [store])

  const value = useMemo<AuthState>(() => ({ token, notice, api, login, logout }), [token, notice, api, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
