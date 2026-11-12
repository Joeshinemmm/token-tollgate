import type { UsageQueryParams } from '../api/types'
import type { UsageFilters } from './filters'
import type { UsageWindow } from './period'

/** The query both usage screens send: the window, the time zone and the filters they share, empty filters left out. */
export function usageQuery(range: UsageWindow, timeZone: string, filters: UsageFilters): UsageQueryParams {
  return {
    from: range.from,
    to: range.to,
    tz: timeZone,
    projectId: filters.projectId || undefined,
    provider: filters.provider || undefined,
    tag: filters.tag || undefined,
  }
}
