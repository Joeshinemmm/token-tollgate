/** USD with at least two decimals and at most eight, trailing zeros dropped: 1 → "1.00", 0.00000015 → "0.00000015". */
export function formatUsd(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return '—'
  return value.toFixed(8).replace(/(\.\d{2}\d*?)0+$/, '$1')
}

/** Whole numbers with thousands separators. */
export function formatCount(value: number): string {
  return new Intl.NumberFormat('ko-KR').format(value)
}

/** The calendar date in the browser's zone, Korean style: "2026. 11. 7.". */
export function formatDate(iso: string): string {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium' }).format(new Date(iso))
}

/** Date and time in the browser's zone (or the one given), Korean style. */
export function formatDateTime(iso: string, timeZone?: string): string {
  return new Intl.DateTimeFormat('ko-KR', { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(iso))
}

/** The calendar date of an instant in UTC, which is how price rows store their effective date. */
export function formatUtcDate(iso: string): string {
  return new Date(iso).toISOString().slice(0, 10)
}

/** "방금", "5분 전", "3시간 전", "2일 전"; beyond a month, the date. */
export function formatRelative(iso: string, now: Date = new Date()): string {
  const diffMs = now.getTime() - new Date(iso).getTime()
  const minutes = Math.floor(diffMs / 60_000)
  if (minutes < 1) return '방금'
  if (minutes < 60) return `${minutes}분 전`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}시간 전`
  const days = Math.floor(hours / 24)
  if (days < 31) return `${days}일 전`
  return formatDateTime(iso)
}

/** Today's date in the browser's zone as YYYY-MM-DD, for date inputs. */
export function todayIsoDate(now: Date = new Date()): string {
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
