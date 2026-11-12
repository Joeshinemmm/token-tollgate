import { Suspense, lazy, useEffect, useMemo, useState } from 'react'
import { errorMessage } from '../../api/client'
import type { Project, Provider, UsageGroup, UsageMetrics, UsageQueryParams, UsageSummary } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { Alert } from '../../components/Alert'
import { Segmented } from '../../components/Segmented'
import { StatTile } from '../../components/StatTile'
import { formatCount, formatUsd } from '../../format'
import { useHashRoute } from '../../router/useHashRoute'
import { UsageFilterBar } from '../../usage/UsageFilterBar'
import { serializeFilters, type UsageFilters } from '../../usage/filters'
import { METRICS, type Metric } from '../../usage/metrics'
import { fillDays, type DayPoint } from '../../usage/period'
import { usageQuery } from '../../usage/query'
import { useUsageFilters } from '../../usage/useUsageFilters'
import { BreakdownTable } from './BreakdownTable'
import { BREAKDOWNS, type Breakdown } from './breakdowns'

/** Recharts is only needed here, so it loads as its own chunk when the overview first renders. */
const DailyChart = lazy(() => import('./DailyChart').then((module) => ({ default: module.DailyChart })))

interface DailyResult {
  key: string
  summary: UsageSummary
  points: DayPoint[]
}

interface BreakdownResult {
  key: string
  summary: UsageSummary
}

export function OverviewPage() {
  const { api } = useAuth()
  const [{ search }, navigate] = useHashRoute()
  const { filters, range, rangeUntouched, rangeError, timeZone } = useUsageFilters(search)

  const [projects, setProjects] = useState<Project[]>([])
  const [providers, setProviders] = useState<Provider[]>([])
  const [metric, setMetric] = useState<Metric>('cost')
  const [showTable, setShowTable] = useState(false)
  const [breakdown, setBreakdown] = useState<Breakdown>('project')
  const [daily, setDaily] = useState<DailyResult | null>(null)
  const [groups, setGroups] = useState<BreakdownResult | null>(null)
  const [error, setError] = useState<string | null>(null)

  const baseParams = useMemo<UsageQueryParams | null>(() => (range ? usageQuery(range, timeZone, filters) : null), [range, timeZone, filters])
  const dailyKey = JSON.stringify({ ...baseParams, groupBy: 'day' })
  const breakdownKey = JSON.stringify({ ...baseParams, groupBy: breakdown })

  useEffect(() => {
    let cancelled = false
    Promise.all([api.listProjects(true), api.listProviders()])
      .then(([projectRows, providerRows]) => {
        if (cancelled) return
        setProjects(projectRows)
        setProviders(providerRows)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api])

  useEffect(() => {
    if (!baseParams || !range) return
    let cancelled = false
    api
      .usageSummary({ ...baseParams, groupBy: 'day' })
      .then((summary) => {
        if (cancelled) return
        setDaily({ key: dailyKey, summary, points: fillDays(range, summary.groups) })
        setError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api, baseParams, range, dailyKey])

  useEffect(() => {
    if (!baseParams) return
    let cancelled = false
    api
      .usageSummary({ ...baseParams, groupBy: breakdown })
      .then((summary) => {
        if (!cancelled) setGroups({ key: breakdownKey, summary })
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api, baseParams, breakdown, breakdownKey])

  const applyFilters = (next: UsageFilters) => navigate('overview', serializeFilters(next))

  const openRequests = (group: UsageGroup) => {
    const next: UsageFilters = { ...filters }
    switch (breakdown) {
      case 'project':
        next.projectId = group.key ?? ''
        break
      case 'model':
        next.model = group.key ?? ''
        next.provider = group.provider ?? next.provider
        break
      case 'tag':
        next.tag = group.key ?? ''
        break
    }
    navigate('requests', serializeFilters(next))
  }

  const totals = daily?.summary.totals ?? null
  const dailyStale = daily !== null && daily.key !== dailyKey
  const groupsStale = groups !== null && groups.key !== breakdownKey
  const failed = totals ? totals.requests - totals.okRequests : 0
  const warning = totals ? countWarning(totals) : null

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>개요</h1>
          <p>기간 안의 비용과 토큰을 하루 단위로, 그리고 프로젝트·모델·태그로 나눠 봅니다. 일별 묶음은 브라우저 시간대 기준입니다.</p>
        </div>
      </div>
      <UsageFilterBar filters={filters} onChange={applyFilters} projects={projects} providers={providers} timeZone={timeZone} rangeError={rangeError} />
      {rangeUntouched && <Alert kind="info">시작일과 종료일을 고르면 그 기간을 조회합니다. 종료일은 포함됩니다.</Alert>}
      {rangeError && <Alert kind="error">{rangeError}</Alert>}
      {error && (
        <Alert kind="error" onDismiss={() => setError(null)}>
          {error}
        </Alert>
      )}
      <div className={dailyStale ? 'overview-grid is-refreshing' : 'overview-grid'}>
        <div className="stat-row">
          <StatTile lead label="총 비용 (USD)" value={totals ? formatUsd(totals.cost) : '—'} detail={totals ? '단가가 있는 요청만 더한 값' : undefined} warning={warning} />
          <StatTile label="요청 수" value={totals ? formatCount(totals.requests) : '—'} detail={totals ? `성공 ${formatCount(totals.okRequests)} · 실패 ${formatCount(failed)}` : undefined} />
          <StatTile label="입력 토큰" value={totals ? formatCount(totals.inputTokens) : '—'} detail={totals ? `캐시 ${formatCount(totals.cachedInputTokens)} 포함` : undefined} />
          <StatTile label="출력 토큰" value={totals ? formatCount(totals.outputTokens) : '—'} />
        </div>
        <section className="panel" aria-labelledby="daily-heading">
          <div className="panel-head">
            <h2 id="daily-heading">일별 추이</h2>
            <div className="row">
              <Segmented label="지표" options={METRICS} value={metric} onChange={setMetric} />
              <label className="check">
                <input type="checkbox" checked={showTable} onChange={(event) => setShowTable(event.target.checked)} />
                표로 보기
              </label>
            </div>
          </div>
          {daily ? (
            <Suspense fallback={<p className="empty chart-wait">차트를 불러오는 중…</p>}>
              <DailyChart points={daily.points} metric={metric} showTable={showTable} />
            </Suspense>
          ) : (
            <p className="empty chart-wait">불러오는 중…</p>
          )}
        </section>
      </div>
      <section className={groupsStale ? 'ledger is-refreshing' : 'ledger'} aria-labelledby="breakdown-heading">
        <div className="ledger-head">
          <h2 id="breakdown-heading">비용 내역</h2>
          <Segmented label="내역 기준" options={BREAKDOWNS} value={breakdown} onChange={setBreakdown} />
        </div>
        {groups ? (
          <div className="table-scroll">
            <BreakdownTable groupBy={breakdown} groups={groups.summary.groups} totalCost={groups.summary.totals.cost} onPick={openRequests} />
          </div>
        ) : (
          <p className="empty">불러오는 중…</p>
        )}
      </section>
    </div>
  )
}

/** The requests the cost leaves out or cannot vouch for, or null when there are none. */
function countWarning(totals: UsageMetrics): string | null {
  const parts: string[] = []
  if (totals.priceMissingRequests > 0) parts.push(`단가 없음 ${formatCount(totals.priceMissingRequests)}건`)
  if (totals.usageUnknownRequests > 0) parts.push(`사용량 모름 ${formatCount(totals.usageUnknownRequests)}건`)
  return parts.length > 0 ? parts.join(' · ') : null
}
