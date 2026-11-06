import { useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Alert } from '../components/Alert'
import { Field } from '../components/Field'
import { Brand } from '../layout/Shell'

export function LoginPage() {
  const { login, notice } = useAuth()
  const [token, setToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    const candidate = token.trim()
    if (!candidate) {
      setError('관리자 토큰을 입력하세요.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await login(candidate)
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) setError('관리자 토큰이 올바르지 않습니다.')
      else setError(err instanceof Error ? err.message : '로그인에 실패했습니다.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login">
      <div className="login-card">
        <Brand />
        <div>
          <h1>관리자 로그인</h1>
          <p className="muted">서버의 TOLLGATE_ADMIN_TOKEN 값을 입력합니다. 토큰은 이 탭이 열려 있는 동안만 기억합니다.</p>
        </div>
        {notice && <Alert kind="info">{notice}</Alert>}
        {error && <Alert kind="error">{error}</Alert>}
        <form onSubmit={submit}>
          <Field label="관리자 토큰">
            {(id) => (
              <input
                id={id}
                className="input"
                type="password"
                autoComplete="off"
                value={token}
                onChange={(event) => setToken(event.target.value)}
                autoFocus
              />
            )}
          </Field>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? '확인 중…' : '로그인'}
          </button>
        </form>
      </div>
    </div>
  )
}
