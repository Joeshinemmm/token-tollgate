import { useMemo } from 'react'
import { parseFilters, type UsageFilters } from './filters'
import { browserTimeZone, customRangeError, presetWindow, type UsageWindow } from './period'

export interface UsageFilterState {
  filters: UsageFilters
  /** Null while a custom range is incomplete or invalid. */
  range: UsageWindow | null
  /** A custom range with neither end picked yet. */
  rangeUntouched: boolean
  rangeError: string | null
  timeZone: string
}

/** The filters of a usage screen read from the hash query, with the window and time zone the queries use. */
export function useUsageFilters(search: string): UsageFilterState {
  const filters = useMemo(() => parseFilters(search), [search])
  const range = useMemo(() => presetWindow(filters), [filters])
  const rangeUntouched = filters.range === 'custom' && !filters.from && !filters.to
  const rangeError = filters.range === 'custom' && !rangeUntouched ? customRangeError(filters.from, filters.to) : null
  const timeZone = useMemo(() => browserTimeZone(), [])
  return { filters, range, rangeUntouched, rangeError, timeZone }
}
