import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { Price } from '../../api/types'
import { TOKEN_STORAGE_KEY } from '../../auth/AuthContext'
import { AuthProvider } from '../../auth/AuthProvider'
import { todayIsoDate } from '../../format'
import { errorReply, fakeAdminApi, reply, type FakeAdminApi } from '../../test/fakeApi'
import { DELETE_CONFLICT_MESSAGE, PricesPage } from './PricesPage'
import { RATE_MESSAGE, REQUIRED_MESSAGE } from './priceModel'

const PRICES: Price[] = [
  { id: 'r1', provider: 'fake', model: 'fake-relay', inputPerMtok: 1, outputPerMtok: 2, cachedInputPerMtok: 0.1, currency: 'USD', effectiveFrom: '2026-01-01T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
  { id: 'r0', provider: 'fake', model: 'fake-relay', inputPerMtok: 0.5, outputPerMtok: 1, cachedInputPerMtok: null, currency: 'USD', effectiveFrom: '2025-06-01T00:00:00Z', createdAt: '2025-06-01T00:00:00Z' },
  { id: 'r2', provider: 'fake', model: 'fake-large', inputPerMtok: 3, outputPerMtok: 15, cachedInputPerMtok: null, currency: 'USD', effectiveFrom: '2026-01-01T00:00:00Z', createdAt: '2026-01-01T00:00:00Z' },
]

const PROVIDERS = [{ name: 'fake', type: 'openai', baseUrl: 'http://fake', enabled: true, default: true, models: ['fake-*'] }]

function renderPricesPage() {
  window.sessionStorage.setItem(TOKEN_STORAGE_KEY, 'test-token')
  return render(
    <AuthProvider>
      <PricesPage />
    </AuthProvider>,
  )
}

describe('PricesPage', () => {
  let fake: FakeAdminApi

  beforeEach(() => {
    fake = fakeAdminApi().on('GET', '/prices', reply(200, PRICES)).on('GET', '/providers', reply(200, PROVIDERS))
  })

  it('shows one row per model with its latest rates and expandable history', async () => {
    const user = userEvent.setup()
    renderPricesPage()

    const historyButton = await screen.findByRole('button', { name: 'fake-relay 이력 펼치기' })
    expect(historyButton).toHaveTextContent('이력 1')
    expect(screen.getAllByRole('row')).toHaveLength(3)

    const relayRow = historyButton.closest('tr')!
    expect(within(relayRow).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['fake', 'fake-relay', '1.00', '0.10', '2.00', '2026-01-01', '이력 1', '수정삭제'])
    const largeRow = screen.getByText('fake-large').closest('tr')!
    expect(within(largeRow).getByText('입력과 같음')).toBeInTheDocument()

    await user.click(historyButton)
    expect(screen.getAllByRole('row')).toHaveLength(4)
    expect(screen.getByText('이전').closest('tr')).toHaveTextContent('0.50')
    expect(screen.getByText('2개 모델, 3행')).toBeInTheDocument()
  })

  it('validates the form, then sends the PUT body and reports a new row', async () => {
    fake.on('PUT', '/prices', (call) => reply(201, { ...(call.body as object), id: 'r9', currency: 'USD', createdAt: '2026-11-07T00:00:00Z' }))
    const user = userEvent.setup()
    renderPricesPage()
    await screen.findByText('fake-large')

    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(screen.getAllByText(REQUIRED_MESSAGE)).toHaveLength(4)
    expect(fake.callsTo('PUT', '/prices')).toHaveLength(0)

    await user.type(screen.getByLabelText('공급처'), 'fake')
    await user.type(screen.getByLabelText('모델'), 'fake-new')
    await user.type(screen.getByLabelText('입력 단가'), '1.5')
    await user.type(screen.getByLabelText('출력 단가'), 'abc')
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(screen.getByText(RATE_MESSAGE)).toBeInTheDocument()
    expect(fake.callsTo('PUT', '/prices')).toHaveLength(0)

    await user.clear(screen.getByLabelText('출력 단가'))
    await user.type(screen.getByLabelText('출력 단가'), '3')
    await user.click(screen.getByRole('button', { name: '저장' }))

    await waitFor(() => expect(fake.callsTo('PUT', '/prices')).toHaveLength(1))
    expect(fake.callsTo('PUT', '/prices')[0].body).toEqual({
      provider: 'fake',
      model: 'fake-new',
      inputPerMtok: 1.5,
      outputPerMtok: 3,
      cachedInputPerMtok: null,
      effectiveFrom: todayIsoDate(),
    })
    expect(await screen.findByText(`단가를 추가했습니다 (fake/fake-new, ${todayIsoDate()}).`)).toBeInTheDocument()
    expect(screen.getByLabelText('모델')).toHaveValue('')
  })

  it('explains a 409 when a referenced price row is deleted', async () => {
    fake.on('DELETE', '/prices/r2', errorReply(409, 'price_in_use', 'price is referenced by usage records'))
    const user = userEvent.setup()
    renderPricesPage()

    await user.click(await screen.findByRole('button', { name: 'fake fake-large 2026-01-01 삭제' }))
    const dialog = await screen.findByRole('dialog', { name: '단가 삭제' })
    await user.click(within(dialog).getByRole('button', { name: '삭제' }))

    expect(await screen.findByText(DELETE_CONFLICT_MESSAGE)).toBeInTheDocument()
    expect(fake.callsTo('DELETE', '/prices/r2')).toHaveLength(1)
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText('fake-large')).toBeInTheDocument()
  })
})
