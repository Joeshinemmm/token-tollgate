import { act, renderHook, waitFor } from '@testing-library/react'
import { expect, it } from 'vitest'
import { parseRoute, useHashRoute } from './useHashRoute'

it('follows the location hash and falls back to keys', async () => {
  const { result } = renderHook(() => useHashRoute())
  expect(result.current[0]).toBe('keys')

  act(() => {
    window.location.hash = '#/prices'
  })
  await waitFor(() => expect(result.current[0]).toBe('prices'))

  act(() => {
    result.current[1]('keys')
  })
  expect(window.location.hash).toBe('#/keys')
  await waitFor(() => expect(result.current[0]).toBe('keys'))

  expect(parseRoute('#/nowhere')).toBe('keys')
  expect(parseRoute('#/prices?x=1')).toBe('prices')
})
