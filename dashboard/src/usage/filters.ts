import { OUTCOMES, type Outcome } from '../api/types'

export type RangePreset = 'today' | '7d' | '30d' | 'custom'

export const RANGE_PRESETS: readonly { value: RangePreset; label: string }[] = [
  { value: 'today', label: '오늘' },
  { value: '7d', label: '7일' },
  { value: '30d', label: '30일' },
  { value: 'custom', label: '직접' },
]

export const OUTCOME_LABELS: Record<Outcome, string> = {
  ok: '성공',
  upstream_error: '상류 오류',
  client_abort: '클라이언트 중단',
  timeout: '시간 초과',
  gateway_error: '게이트웨이 오류',
}

/** What the usage screens show; lives in the hash query so that reload, back and sharing keep it. */
export interface UsageFilters {
  range: RangePreset
  /** Custom range bounds as inclusive calendar dates (YYYY-MM-DD); only read when range is custom. */
  from: string
  to: string
  projectId: string
  provider: string
  tag: string
  model: string
  outcome: Outcome | ''
}

export const DEFAULT_FILTERS: UsageFilters = { range: '7d', from: '', to: '', projectId: '', provider: '', tag: '', model: '', outcome: '' }

function isPreset(value: string | null): value is RangePreset {
  return RANGE_PRESETS.some((preset) => preset.value === value)
}

function isOutcome(value: string | null): value is Outcome {
  return (OUTCOMES as readonly string[]).includes(value ?? '')
}

export function parseFilters(search: string): UsageFilters {
  const params = new URLSearchParams(search)
  const range = params.get('range')
  const outcome = params.get('outcome')
  return {
    range: isPreset(range) ? range : DEFAULT_FILTERS.range,
    from: params.get('from') ?? '',
    to: params.get('to') ?? '',
    projectId: params.get('projectId') ?? '',
    provider: params.get('provider') ?? '',
    tag: params.get('tag') ?? '',
    model: params.get('model') ?? '',
    outcome: isOutcome(outcome) ? outcome : '',
  }
}

/** The query string for [filters], without defaults and empty values so that the address stays short. */
export function serializeFilters(filters: UsageFilters): string {
  const params = new URLSearchParams()
  if (filters.range !== DEFAULT_FILTERS.range) params.set('range', filters.range)
  if (filters.range === 'custom') {
    if (filters.from) params.set('from', filters.from)
    if (filters.to) params.set('to', filters.to)
  }
  for (const key of ['projectId', 'provider', 'tag', 'model', 'outcome'] as const) {
    if (filters[key]) params.set(key, filters[key])
  }
  return params.toString()
}
