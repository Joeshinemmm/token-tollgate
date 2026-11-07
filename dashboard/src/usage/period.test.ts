import { describe, expect, it } from 'vitest'
import type { UsageGroup } from '../api/types'
import { RANGE_INCOMPLETE_MESSAGE, RANGE_ORDER_MESSAGE, RANGE_TOO_LONG_MESSAGE, addDays, customRangeError, fillDays, presetWindow } from './period'

const TODAY = '2026-11-07'

describe('presetWindow', () => {
  it('turns presets into date bounds with an exclusive end', () => {
    expect(presetWindow({ range: 'today', from: '', to: '' }, TODAY)).toEqual({ from: '2026-11-07', to: '2026-11-08' })
    expect(presetWindow({ range: '7d', from: '', to: '' }, TODAY)).toEqual({ from: '2026-11-01', to: '2026-11-08' })
    expect(presetWindow({ range: '30d', from: '', to: '' }, TODAY)).toEqual({ from: '2026-10-09', to: '2026-11-08' })
    expect(presetWindow({ range: 'custom', from: '2026-10-01', to: '2026-10-31' }, TODAY)).toEqual({ from: '2026-10-01', to: '2026-11-01' })
    expect(presetWindow({ range: 'custom', from: '2026-10-01', to: '' }, TODAY)).toBeNull()
    expect(addDays('2026-03-01', -1)).toBe('2026-02-28')
  })
})

describe('customRangeError', () => {
  it('mirrors the server rules for a custom range', () => {
    expect(customRangeError('', '2026-11-07')).toBe(RANGE_INCOMPLETE_MESSAGE)
    expect(customRangeError('2026-11-08', '2026-11-07')).toBe(RANGE_ORDER_MESSAGE)
    expect(customRangeError('2025-11-06', '2026-11-07')).toBe(RANGE_TOO_LONG_MESSAGE)
    expect(customRangeError('2025-11-07', '2026-11-07')).toBeNull()
    expect(customRangeError('2026-11-07', '2026-11-07')).toBeNull()
  })
})

describe('fillDays', () => {
  it('gives every day of the window a point, zero where the server had no bucket', () => {
    const bucket: UsageGroup = {
      key: '2026-11-06',
      label: null,
      provider: null,
      requests: 3,
      okRequests: 2,
      inputTokens: 100,
      cachedInputTokens: 10,
      outputTokens: 40,
      cost: 0.0012,
      priceMissingRequests: 0,
      usageUnknownRequests: 1,
    }
    const points = fillDays({ from: '2026-11-05', to: '2026-11-08' }, [bucket])
    expect(points.map((point) => point.day)).toEqual(['2026-11-05', '2026-11-06', '2026-11-07'])
    expect(points[0]).toMatchObject({ requests: 0, cost: 0, tokens: 0 })
    expect(points[1]).toMatchObject({ requests: 3, okRequests: 2, inputTokens: 100, cachedInputTokens: 10, outputTokens: 40, tokens: 140, cost: 0.0012 })
  })
})
