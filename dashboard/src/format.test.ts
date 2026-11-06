import { describe, expect, it } from 'vitest'
import { formatDateTime, formatRelative, formatUsd } from './format'

describe('formatUsd', () => {
  it('keeps at least two decimals and drops trailing zeros beyond them', () => {
    expect(formatUsd(1)).toBe('1.00')
    expect(formatUsd(2.5)).toBe('2.50')
    expect(formatUsd(0.00000015)).toBe('0.00000015')
    expect(formatUsd(0.000004)).toBe('0.000004')
    expect(formatUsd(null)).toBe('—')
  })
})

describe('dates', () => {
  it('formats an instant in the given zone and relative to now', () => {
    expect(formatDateTime('2026-11-07T01:02:00Z', 'UTC')).toMatch(/2026\. 11\. 7\./)
    expect(formatDateTime('2026-11-07T01:02:00Z', 'UTC')).toMatch(/1:02/)

    const now = new Date('2026-11-07T12:00:00Z')
    expect(formatRelative('2026-11-07T11:59:40Z', now)).toBe('방금')
    expect(formatRelative('2026-11-07T11:55:00Z', now)).toBe('5분 전')
    expect(formatRelative('2026-11-07T09:00:00Z', now)).toBe('3시간 전')
    expect(formatRelative('2026-11-05T12:00:00Z', now)).toBe('2일 전')
  })
})
