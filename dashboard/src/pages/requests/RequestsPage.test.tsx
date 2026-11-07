import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { UsageRecord } from '../../api/types'
import { TOKEN_STORAGE_KEY } from '../../auth/AuthContext'
import { AuthProvider } from '../../auth/AuthProvider'
import { fakeAdminApi, reply, type FakeAdminApi, type RecordedCall } from '../../test/fakeApi'
import { RequestsPage } from './RequestsPage'

function record(partial: Partial<UsageRecord> & Pick<UsageRecord, 'id'>): UsageRecord {
  return {
    requestId: `00000000-0000-0000-0000-00000000000${partial.id}`,
    startedAt: '2026-11-07T01:02:03Z',
    projectId: 'p1',
    projectName: 'alpha',
    keyId: 'k1',
    keyPrefix: 'tt_aaaa',
    tag: 'dev',
    protocol: 'openai',
    provider: 'fake',
    modelRequested: 'fake-small',
    modelServed: 'fake-small',
    inputTokens: 100,
    cachedInputTokens: 5,
    outputTokens: 50,
    cost: 0.000004,
    usageSource: 'response',
    ttftMs: 845,
    durationMs: 1830,
    httpStatus: 200,
    streamed: true,
    outcome: 'ok',
    errorType: null,
    ...partial,
  }
}

const FIRST_PAGE = [record({ id: 42 }), record({ id: 41, outcome: 'upstream_error', httpStatus: 502, cost: null, usageSource: 'none', ttftMs: null, streamed: false, errorType: 'http_500', tag: null })]
const SECOND_PAGE = [record({ id: 40, modelRequested: 'fake-large', modelServed: 'fake-large' })]

function query(call: RecordedCall): URLSearchParams {
  return new URLSearchParams(call.path.split('?')[1] ?? '')
}

function renderRequests() {
  window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'test-token')
  return render(
    <AuthProvider>
      <RequestsPage />
    </AuthProvider>,
  )
}

describe('RequestsPage', () => {
  let fake: FakeAdminApi

  beforeEach(() => {
    fake = fakeAdminApi()
      .on('GET', /^\/projects/, reply(200, [{ id: 'p1', name: 'alpha', createdAt: '2026-11-01T00:00:00Z', archivedAt: null }]))
      .on('GET', '/providers', reply(200, [{ name: 'fake', type: 'openai', baseUrl: 'http://fake', enabled: true, default: true, models: ['fake-*'] }]))
      .on('GET', /^\/usage\/records/, (call) => {
        const before = query(call).get('before')
        if (before === '41') return reply(200, { records: SECOND_PAGE, nextBefore: null })
        return reply(200, { records: FIRST_PAGE, nextBefore: 41 })
      })
  })

  it('lists the records with outcome badges, missing costs and an expandable detail row', async () => {
    const user = userEvent.setup()
    renderRequests()

    const buttons = await screen.findAllByRole('button', { name: '자세히' })
    expect(buttons).toHaveLength(2)
    const okRow = buttons[0].closest('tr')!
    expect(okRow).toHaveTextContent('성공')
    expect(okRow).toHaveTextContent('0.000004')
    expect(okRow).toHaveTextContent('845 ms')
    expect(okRow).toHaveTextContent('1.8 s')
    const failedRow = buttons[1].closest('tr')!
    expect(failedRow).toHaveTextContent('상류 오류')
    expect(failedRow).toHaveTextContent('502')
    expect(within(failedRow).getByTitle('사용량을 알 수 없어 비용을 계산하지 못했습니다.')).toHaveTextContent('—')
    expect(screen.getByText('2건 표시')).toBeInTheDocument()

    const first = query(fake.callsTo('GET', '/usage/records')[0])
    expect(first.get('limit')).toBe('50')
    expect(first.has('before')).toBe(false)
    expect(first.has('outcome')).toBe(false)

    await user.click(buttons[1])
    expect(screen.getByText('00000000-0000-0000-0000-0000000000041')).toBeInTheDocument()
    expect(screen.getByText('http_500')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '접기' })).toHaveAttribute('aria-expanded', 'true')
  })

  it('applies the outcome filter through the hash query', async () => {
    const user = userEvent.setup()
    renderRequests()
    await screen.findAllByRole('button', { name: '자세히' })

    await user.selectOptions(screen.getByLabelText('결과'), 'upstream_error')

    await waitFor(() => expect(fake.callsTo('GET', '/usage/records').some((call) => query(call).get('outcome') === 'upstream_error')).toBe(true))
    expect(window.location.hash).toBe('#/requests?outcome=upstream_error')
  })

  it('appends the next page with the cursor and says when it is the end', async () => {
    const user = userEvent.setup()
    renderRequests()
    await screen.findAllByRole('button', { name: '자세히' })

    await user.click(screen.getByRole('button', { name: '더 보기' }))

    await waitFor(() => expect(screen.getAllByRole('button', { name: '자세히' })).toHaveLength(3))
    const more = fake.callsTo('GET', '/usage/records').find((call) => query(call).has('before'))!
    expect(query(more).get('before')).toBe('41')
    expect(query(more).get('limit')).toBe('50')
    expect(screen.getByText('3건 표시')).toBeInTheDocument()
    expect(screen.getByText('끝')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
  })
})
