import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import App from '../App'
import { TOKEN_STORAGE_KEY } from '../auth/AuthContext'
import { errorReply, fakeAdminApi, reply } from '../test/fakeApi'

const EMPTY_SUMMARY = {
  from: '2026-11-01T00:00:00Z',
  to: '2026-11-08T00:00:00Z',
  tz: 'UTC',
  groupBy: 'day',
  totals: { requests: 0, okRequests: 0, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: 0, priceMissingRequests: 0, usageUnknownRequests: 0 },
  groups: [],
}

const PROVIDERS = [{ name: 'fake', type: 'openai', baseUrl: 'http://fake', enabled: true, default: true, models: ['fake-*'] }]

describe('LoginPage', () => {
  it('probes GET /providers with the typed token', async () => {
    const fake = fakeAdminApi().on('GET', '/providers', reply(200, PROVIDERS)).on('GET', /^\/projects/, reply(200, [])).on('GET', /^\/usage\/summary/, reply(200, EMPTY_SUMMARY))
    const user = userEvent.setup()
    render(<App />)

    await user.type(screen.getByLabelText('관리자 토큰'), 'typed-token')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(fake.callsTo('GET', '/providers').length).toBeGreaterThan(0))
    expect(fake.callsTo('GET', '/providers')[0].headers.Authorization).toBe('Bearer typed-token')
  })

  it('shows the wrong-token message on 401 and stores nothing', async () => {
    fakeAdminApi().on('GET', '/providers', errorReply(401, 'unauthorized', 'Unauthorized'))
    const user = userEvent.setup()
    render(<App />)

    await user.type(screen.getByLabelText('관리자 토큰'), 'wrong')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByText('관리자 토큰이 올바르지 않습니다.')).toBeInTheDocument()
    expect(window.sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBeNull()
    expect(screen.getByLabelText('관리자 토큰')).toBeInTheDocument()
  })

  it('on 200 stores the token for the tab and shows the shell', async () => {
    fakeAdminApi().on('GET', '/providers', reply(200, PROVIDERS)).on('GET', /^\/projects/, reply(200, [])).on('GET', /^\/usage\/summary/, reply(200, EMPTY_SUMMARY))
    const user = userEvent.setup()
    render(<App />)

    await user.type(screen.getByLabelText('관리자 토큰'), 'good-token')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    expect(await screen.findByRole('heading', { level: 1, name: '개요' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '요금표' })).toHaveAttribute('href', '#/prices')
    expect(window.sessionStorage.getItem(TOKEN_STORAGE_KEY)).toBe('good-token')
    expect(screen.queryByLabelText('관리자 토큰')).toBeNull()
  })
})
