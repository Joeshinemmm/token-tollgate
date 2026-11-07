import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { parseHash, parseRoute, useHashRoute } from './useHashRoute'

describe('useHashRoute', () => {
  it('follows the location hash and falls back to the overview', async () => {
    const { result } = renderHook(() => useHashRoute())
    expect(result.current[0]).toEqual({ route: 'overview', search: '' })

    act(() => {
      window.location.hash = '#/prices'
    })
    await waitFor(() => expect(result.current[0].route).toBe('prices'))

    act(() => {
      result.current[1]('keys')
    })
    expect(window.location.hash).toBe('#/keys')
    await waitFor(() => expect(result.current[0].route).toBe('keys'))

    expect(parseRoute('#/nowhere')).toBe('overview')
  })

  it('keeps the query string next to the route', async () => {
    expect(parseHash('#/requests?outcome=ok&range=today')).toEqual({ route: 'requests', search: 'outcome=ok&range=today' })
    expect(parseHash('#/prices?x=1').route).toBe('prices')
    expect(parseHash('#/nowhere?x=1')).toEqual({ route: 'overview', search: 'x=1' })

    const { result } = renderHook(() => useHashRoute())
    act(() => {
      result.current[1]('overview', 'range=today&projectId=p1')
    })
    expect(window.location.hash).toBe('#/overview?range=today&projectId=p1')
    await waitFor(() => expect(result.current[0].search).toBe('range=today&projectId=p1'))
  })
})
