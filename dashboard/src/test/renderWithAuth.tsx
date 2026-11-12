import { render, type RenderResult } from '@testing-library/react'
import type { ReactElement } from 'react'
import { TOKEN_STORAGE_KEY } from '../auth/AuthContext'
import { AuthProvider } from '../auth/AuthProvider'

/** Renders [ui] signed in with a test token, as the pages are rendered inside the app. */
export function renderWithAuth(ui: ReactElement): RenderResult {
  window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'test-token')
  return render(<AuthProvider>{ui}</AuthProvider>)
}
