import { formatCount, formatUsd } from '../format'

/** What the daily chart plots; one series at a time, on one axis. */
export type Metric = 'cost' | 'requests' | 'tokens'

export const METRICS: readonly { value: Metric; label: string }[] = [
  { value: 'cost', label: '비용' },
  { value: 'requests', label: '요청' },
  { value: 'tokens', label: '토큰' },
]

export const METRIC_TITLES: Record<Metric, string> = {
  cost: '비용 (USD)',
  requests: '요청 수',
  tokens: '토큰 (입력 + 출력)',
}

export function formatMetric(metric: Metric, value: number): string {
  return metric === 'cost' ? formatUsd(value) : formatCount(value)
}
