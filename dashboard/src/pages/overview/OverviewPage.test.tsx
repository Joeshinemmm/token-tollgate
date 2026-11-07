import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { UsageGroup, UsageMetrics, UsageSummary } from '../../api/types'
import { TOKEN_STORAGE_KEY } from '../../auth/AuthContext'
import { AuthProvider } from '../../auth/AuthProvider'
import { todayIsoDate } from '../../format'
import { fakeAdminApi, reply, type FakeAdminApi, type RecordedCall } from '../../test/fakeApi'
import { addDays, browserTimeZone } from '../../usage/period'
import { OverviewPage } from './OverviewPage'

const TODAY = todayIsoDate()

const TOTALS: UsageMetrics = { requests: 12, okRequests: 10, inputTokens: 1200, cachedInputTokens: 200, outputTokens: 340, cost: 0.0123, priceMissingRequests: 1, usageUnknownRequests: 2 }

function group(partial: Partial<UsageGroup> & Pick<UsageGroup, 'key'>): UsageGroup {
  return { label: null, provider: null, requests: 0, okRequests: 0, inputTokens: 0, cachedInputTokens: 0, outputTokens: 0, cost: 0, priceMissingRequests: 0, usageUnknownRequests: 0, ...partial }
}

function summary(groupBy: UsageSummary['groupBy']): UsageSummary {
  const groups: Record<UsageSummary['groupBy'], UsageGroup[]> = {
    day: [group({ key: TODAY, requests: 12, okRequests: 10, inputTokens: 1200, cachedInputTokens: 200, outputTokens: 340, cost: 0.0123 })],
    project: [group({ key: 'p1', label: 'alpha', requests: 12, okRequests: 10, cost: 0.0123 })],
    model: [
      group({ key: 'fake-small', provider: 'fake', requests: 9, okRequests: 8, cost: 0.0023 }),
      group({ key: 'fake-large', provider: 'fake', requests: 3, okRequests: 2, cost: 0.01 }),
    ],
    tag: [group({ key: null, requests: 12, okRequests: 10, cost: 0.0123 })],
  }
  return { from: `${TODAY}T00:00:00Z`, to: `${addDays(TODAY, 1)}T00:00:00Z`, tz: browserTimeZone(), groupBy, totals: TOTALS, groups: groups[groupBy] }
}

function query(call: RecordedCall): URLSearchParams {
  return new URLSearchParams(call.path.split('?')[1] ?? '')
}

function renderOverview() {
  window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'test-token')
  return render(
    <AuthProvider>
      <OverviewPage />
    </AuthProvider>,
  )
}

describe('OverviewPage', () => {
  let fake: FakeAdminApi

  beforeEach(() => {
    fake = fakeAdminApi()
      .on('GET', /^\/projects/, reply(200, [{ id: 'p1', name: 'alpha', createdAt: '2026-11-01T00:00:00Z', archivedAt: null }]))
      .on('GET', '/providers', reply(200, [{ name: 'fake', type: 'openai', baseUrl: 'http://fake', enabled: true, default: true, models: ['fake-*'] }]))
      .on('GET', /^\/usage\/summary/, (call) => reply(200, summary((query(call).get('groupBy') ?? 'day') as UsageSummary['groupBy'])))
  })

  it('shows the totals of the last 7 days in the browser time zone, with the table twin of the chart', async () => {
    const user = userEvent.setup()
    renderOverview()

    const costTile = (await screen.findByText('총 비용 (USD)')).closest<HTMLElement>('.stat')!
    await waitFor(() => expect(within(costTile).getByText('0.0123')).toBeInTheDocument())
    expect(within(costTile).getByRole('status')).toHaveTextContent('단가 없음 1건 · 사용량 모름 2건')
    const requestsTile = screen.getByText('요청 수').closest<HTMLElement>('.stat')!
    expect(within(requestsTile).getByText('12')).toBeInTheDocument()
    expect(within(requestsTile).getByText('성공 10 · 실패 2')).toBeInTheDocument()
    expect(within(screen.getByText('입력 토큰').closest<HTMLElement>('.stat')!).getByText('캐시 200 포함')).toBeInTheDocument()

    const dailyCall = fake.callsTo('GET', '/usage/summary').find((call) => query(call).get('groupBy') === 'day')!
    expect(query(dailyCall).get('from')).toBe(addDays(TODAY, -6))
    expect(query(dailyCall).get('to')).toBe(addDays(TODAY, 1))
    expect(query(dailyCall).get('tz')).toBe(browserTimeZone())
    expect(query(dailyCall).has('projectId')).toBe(false)

    await user.click(screen.getByLabelText('표로 보기'))
    const table = screen.getByRole('table', { name: '일별 표' })
    expect(within(table).getAllByRole('row')).toHaveLength(8)
    expect(within(table).getByText(TODAY).closest('tr')).toHaveTextContent('0.0123')
    expect(within(table).getByText(addDays(TODAY, -6)).closest('tr')).toHaveTextContent('0.00')
  })

  it('re-queries with the new bounds when the preset changes and keeps it in the hash', async () => {
    const user = userEvent.setup()
    renderOverview()
    await screen.findAllByText('0.0123')

    await user.click(screen.getByRole('tab', { name: '오늘' }))

    await waitFor(() => {
      const todayCalls = fake.callsTo('GET', '/usage/summary').filter((call) => query(call).get('from') === TODAY && query(call).get('groupBy') === 'day')
      expect(todayCalls).toHaveLength(1)
      expect(query(todayCalls[0]).get('to')).toBe(addDays(TODAY, 1))
    })
    expect(window.location.hash).toBe('#/overview?range=today')
    expect(screen.getByRole('tab', { name: '오늘' })).toHaveAttribute('aria-selected', 'true')
  })

  it('switches the breakdown to models and links a row to the request list', async () => {
    const user = userEvent.setup()
    renderOverview()
    expect(await screen.findByRole('button', { name: 'alpha' })).toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: '모델' }))

    const large = await screen.findByRole('button', { name: 'fake/fake-large' })
    const rows = within(large.closest<HTMLElement>('table')!).getAllByRole('row')
    expect(rows[1]).toHaveTextContent('fake/fake-large')
    expect(rows[1]).toHaveTextContent('81.3%')
    expect(rows[2]).toHaveTextContent('fake/fake-small')
    expect(fake.callsTo('GET', '/usage/summary').some((call) => query(call).get('groupBy') === 'model')).toBe(true)

    await user.click(large)
    expect(window.location.hash).toBe('#/requests?provider=fake&model=fake-large')
  })
})
