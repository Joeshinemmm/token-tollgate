import type { UsageGroup } from '../api/types'
import { todayIsoDate } from '../format'
import type { UsageFilters } from './filters'

/** The server refuses longer windows (UsageWindow.MAX_RANGE). */
export const MAX_RANGE_DAYS = 366

export function browserTimeZone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}

function parts(date: string): [number, number, number] {
  const [year, month, day] = date.split('-').map(Number)
  return [year, month - 1, day]
}

/** Calendar-date arithmetic on YYYY-MM-DD strings, done in UTC so that daylight-saving shifts cannot skip or repeat a day. */
export function addDays(date: string, days: number): string {
  const [year, month, day] = parts(date)
  return new Date(Date.UTC(year, month, day + days)).toISOString().slice(0, 10)
}

export function daysBetween(from: string, to: string): number {
  return Math.round((Date.UTC(...parts(to)) - Date.UTC(...parts(from))) / 86_400_000)
}

/** The bounds sent to the server: `from` inclusive, `to` exclusive, calendar dates the server reads at midnight in `tz`. */
export interface UsageWindow {
  from: string
  to: string
}

const DATE = /^\d{4}-\d{2}-\d{2}$/

export const RANGE_INCOMPLETE_MESSAGE = '시작일과 종료일을 입력하세요.'
export const RANGE_ORDER_MESSAGE = '시작일은 종료일보다 앞서거나 같아야 합니다.'
export const RANGE_TOO_LONG_MESSAGE = `기간은 ${MAX_RANGE_DAYS}일 이하여야 합니다.`

/** Mirrors the server's window rules for a custom range whose both ends are inclusive dates. */
export function customRangeError(from: string, to: string): string | null {
  if (!DATE.test(from) || !DATE.test(to)) return RANGE_INCOMPLETE_MESSAGE
  if (from > to) return RANGE_ORDER_MESSAGE
  if (daysBetween(from, to) + 1 > MAX_RANGE_DAYS) return RANGE_TOO_LONG_MESSAGE
  return null
}

/** Resolves the preset to a window; null while a custom range is incomplete or invalid. */
export function presetWindow(filters: Pick<UsageFilters, 'range' | 'from' | 'to'>, today: string = todayIsoDate()): UsageWindow | null {
  const tomorrow = addDays(today, 1)
  switch (filters.range) {
    case 'today':
      return { from: today, to: tomorrow }
    case '7d':
      return { from: addDays(today, -6), to: tomorrow }
    case '30d':
      return { from: addDays(today, -29), to: tomorrow }
    case 'custom':
      return customRangeError(filters.from, filters.to) ? null : { from: filters.from, to: addDays(filters.to, 1) }
  }
}

export function listDays(window: UsageWindow): string[] {
  const days: string[] = []
  for (let day = window.from; day < window.to; day = addDays(day, 1)) days.push(day)
  return days
}

export interface DayPoint {
  day: string
  requests: number
  okRequests: number
  inputTokens: number
  cachedInputTokens: number
  outputTokens: number
  /** Input plus output, the number a chart of "tokens" plots. */
  tokens: number
  cost: number
}

/** One point per calendar day of the window, zero where the server returned no bucket. */
export function fillDays(window: UsageWindow, groups: UsageGroup[]): DayPoint[] {
  const byDay = new Map(groups.map((group) => [group.key, group]))
  return listDays(window).map((day) => {
    const group = byDay.get(day)
    const inputTokens = group?.inputTokens ?? 0
    const outputTokens = group?.outputTokens ?? 0
    return {
      day,
      requests: group?.requests ?? 0,
      okRequests: group?.okRequests ?? 0,
      inputTokens,
      cachedInputTokens: group?.cachedInputTokens ?? 0,
      outputTokens,
      tokens: inputTokens + outputTokens,
      cost: group?.cost ?? 0,
    }
  })
}
