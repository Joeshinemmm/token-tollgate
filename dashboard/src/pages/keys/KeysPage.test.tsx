import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import type { Project, VirtualKey } from '../../api/types'
import { fakeAdminApi, reply, type FakeAdminApi } from '../../test/fakeApi'
import { renderWithAuth } from '../../test/renderWithAuth'
import { KeysPage } from './KeysPage'

const PROJECTS: Project[] = [
  { id: 'p1', name: 'alpha', createdAt: '2026-11-01T00:00:00Z', archivedAt: null },
  { id: 'p2', name: 'beta', createdAt: '2026-11-02T00:00:00Z', archivedAt: null },
]

const KEYS: VirtualKey[] = [
  { id: 'k1', projectId: 'p1', name: 'claude-code', keyPrefix: 'tg_aaaa', createdAt: '2026-11-03T00:00:00Z', revokedAt: null, lastUsedAt: null },
  { id: 'k2', projectId: 'p1', name: 'old-laptop', keyPrefix: 'tg_bbbb', createdAt: '2026-11-03T00:00:00Z', revokedAt: '2026-11-04T00:00:00Z', lastUsedAt: '2026-11-03T12:00:00Z' },
]

function renderKeysPage() {
  return renderWithAuth(<KeysPage />)
}

describe('KeysPage', () => {
  let fake: FakeAdminApi
  let projects: Project[]
  let keys: VirtualKey[]

  beforeEach(() => {
    projects = [...PROJECTS]
    keys = KEYS.map((key) => ({ ...key }))
    fake = fakeAdminApi()
      .on('GET', /^\/projects\?includeArchived=/, () => reply(200, projects))
      .on('GET', '/keys?projectId=p1', () => reply(200, keys))
      .on('GET', '/keys?projectId=p2', reply(200, []))
  })

  it('lists the projects and the keys of the first one', async () => {
    renderKeysPage()

    expect(await screen.findByText('alpha')).toBeInTheDocument()
    expect(screen.getByText('beta')).toBeInTheDocument()
    expect(await screen.findByText('claude-code')).toBeInTheDocument()

    const activeRow = screen.getByText('claude-code').closest('tr')!
    expect(within(activeRow).getByText('활성')).toBeInTheDocument()
    expect(within(activeRow).getByRole('button', { name: 'claude-code 폐기' })).toBeInTheDocument()

    const revokedRow = screen.getByText('old-laptop').closest('tr')!
    expect(within(revokedRow).getByText('폐기')).toBeInTheDocument()
    expect(within(revokedRow).queryByRole('button')).toBeNull()
    expect(fake.calls[1].headers.Authorization).toBe('Bearer test-token')
  })

  it('creates a project and reloads the list with it selected', async () => {
    fake.on('POST', '/projects', (call) => {
      const created: Project = { id: 'p3', name: (call.body as { name: string }).name, createdAt: '2026-11-07T00:00:00Z', archivedAt: null }
      projects = [...projects, created]
      return reply(201, created)
    })
    fake.on('GET', '/keys?projectId=p3', reply(200, []))
    const user = userEvent.setup()
    renderKeysPage()
    await screen.findByText('alpha')

    await user.type(screen.getByLabelText('새 프로젝트 이름'), 'gamma')
    await user.click(screen.getByRole('button', { name: '추가' }))

    expect(await screen.findByText('gamma')).toBeInTheDocument()
    expect(fake.callsTo('POST', '/projects')[0].body).toEqual({ name: 'gamma' })
    expect(await screen.findByRole('heading', { level: 2, name: /gamma의 키/ })).toBeInTheDocument()
    expect(screen.getByText('프로젝트 gamma을(를) 만들었습니다.')).toBeInTheDocument()
  })

  it('issues a key, shows the plaintext once and copies it', async () => {
    fake.on('POST', '/projects/p1/keys', (call) =>
      reply(201, {
        id: 'k3',
        projectId: 'p1',
        name: (call.body as { name: string }).name,
        keyPrefix: 'tg_cccc',
        createdAt: '2026-11-07T00:00:00Z',
        key: 'tg_cccc_plaintext_0123456789',
      }),
    )
    const user = userEvent.setup()
    renderKeysPage()
    await screen.findByText('claude-code')

    await user.click(screen.getByRole('button', { name: '키 발급' }))
    const dialog = await screen.findByRole('dialog', { name: '키 발급' })
    await user.type(within(dialog).getByLabelText('키 이름'), 'cursor')
    await user.click(within(dialog).getByRole('button', { name: '발급' }))

    expect(await screen.findByTestId('issued-key')).toHaveTextContent('tg_cccc_plaintext_0123456789')
    expect(screen.getByText('한 번만 표시')).toBeInTheDocument()
    expect(fake.callsTo('POST', '/projects/p1/keys')[0].body).toEqual({ name: 'cursor' })

    await user.click(screen.getByRole('button', { name: '복사' }))
    expect(await screen.findByText('복사됨')).toBeInTheDocument()
    expect(await navigator.clipboard.readText()).toBe('tg_cccc_plaintext_0123456789')

    await user.click(screen.getByRole('button', { name: '닫기' }))
    expect(screen.queryByTestId('issued-key')).toBeNull()
  })

  it('revokes a key after confirmation', async () => {
    fake.on('DELETE', '/keys/k1', () => {
      keys = keys.map((key) => (key.id === 'k1' ? { ...key, revokedAt: '2026-11-07T00:00:00Z' } : key))
      return reply(204)
    })
    const user = userEvent.setup()
    renderKeysPage()
    await screen.findByText('claude-code')

    await user.click(screen.getByRole('button', { name: 'claude-code 폐기' }))
    const dialog = await screen.findByRole('dialog', { name: '키 폐기' })
    expect(within(dialog).getByText(/tg_aaaa…\(claude-code\)를 폐기합니다/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: '폐기' }))

    await waitFor(() => expect(fake.callsTo('DELETE', '/keys/k1')).toHaveLength(1))
    await waitFor(() => expect(screen.queryByRole('button', { name: 'claude-code 폐기' })).toBeNull())
    const row = screen.getByText('claude-code').closest('tr')!
    expect(within(row).getByText('폐기')).toBeInTheDocument()
    expect(screen.getByText('키 tg_aaaa…를 폐기했습니다.')).toBeInTheDocument()
  })
})
