import { expect, it } from 'vitest'
import { DEFAULT_FILTERS, parseFilters, serializeFilters } from './filters'

it('round-trips filters through the hash query and leaves defaults out', () => {
  expect(serializeFilters(DEFAULT_FILTERS)).toBe('')
  expect(parseFilters('')).toEqual(DEFAULT_FILTERS)

  const parsed = parseFilters('range=custom&from=2026-10-01&to=2026-10-31&projectId=p1&provider=fake&tag=dev&model=fake-small&outcome=upstream_error')
  expect(parsed).toEqual({ range: 'custom', from: '2026-10-01', to: '2026-10-31', projectId: 'p1', provider: 'fake', tag: 'dev', model: 'fake-small', outcome: 'upstream_error' })
  expect(parseFilters(serializeFilters(parsed))).toEqual(parsed)

  expect(parseFilters('range=bogus&outcome=bogus')).toEqual(DEFAULT_FILTERS)
  expect(serializeFilters({ ...DEFAULT_FILTERS, range: '7d', from: '2026-10-01', to: '2026-10-31', tag: 'a b' })).toBe('tag=a+b')
})
