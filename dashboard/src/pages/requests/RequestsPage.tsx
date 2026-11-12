import { useEffect, useMemo, useState } from 'react'
import { errorMessage } from '../../api/client'
import type { Project, Provider, UsageRecord, UsageRecordsParams } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { Alert } from '../../components/Alert'
import { formatCount } from '../../format'
import { useHashRoute } from '../../router/useHashRoute'
import { toggled } from '../../updates'
import { UsageFilterBar } from '../../usage/UsageFilterBar'
import { serializeFilters, type UsageFilters } from '../../usage/filters'
import { usageQuery } from '../../usage/query'
import { useUsageFilters } from '../../usage/useUsageFilters'
import { RequestTable } from './RequestTable'

const PAGE_SIZE = 50

interface Page {
  key: string
  records: UsageRecord[]
  nextBefore: number | null
}

export function RequestsPage() {
  const { api } = useAuth()
  const [{ search }, navigate] = useHashRoute()
  const { filters, range, rangeUntouched, rangeError, timeZone } = useUsageFilters(search)

  const [projects, setProjects] = useState<Project[]>([])
  const [providers, setProviders] = useState<Provider[]>([])
  const [page, setPage] = useState<Page | null>(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [expanded, setExpanded] = useState<ReadonlySet<number>>(new Set())
  const [error, setError] = useState<string | null>(null)

  const params = useMemo<UsageRecordsParams | null>(
    () =>
      range
        ? {
            ...usageQuery(range, timeZone, filters),
            model: filters.model || undefined,
            outcome: filters.outcome || undefined,
            limit: PAGE_SIZE,
          }
        : null,
    [range, timeZone, filters],
  )
  const key = JSON.stringify(params)

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
    if (!params) return
    let cancelled = false
    api
      .usageRecords(params)
      .then((result) => {
        if (cancelled) return
        setPage({ key, records: result.records, nextBefore: result.nextBefore })
        setError(null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api, params, key])

  const loadMore = async () => {
    if (!params || !page || page.nextBefore === null) return
    setLoadingMore(true)
    try {
      const result = await api.usageRecords({ ...params, before: page.nextBefore })
      setPage((current) => (current && current.key === key ? { ...current, records: [...current.records, ...result.records], nextBefore: result.nextBefore } : current))
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setLoadingMore(false)
    }
  }

  const toggle = (id: number) => setExpanded((current) => toggled(current, id))

  const applyFilters = (next: UsageFilters) => navigate('requests', serializeFilters(next))
  const stale = page !== null && page.key !== key

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>요청 목록</h1>
          <p>최근 요청부터 {PAGE_SIZE}건씩 보여 줍니다. 요청·응답 본문은 저장하지 않으므로 메타데이터와 수치만 있습니다.</p>
        </div>
      </div>
      <UsageFilterBar filters={filters} onChange={applyFilters} projects={projects} providers={providers} timeZone={timeZone} rangeError={rangeError} showModel showOutcome />
      {rangeUntouched && <Alert kind="info">시작일과 종료일을 고르면 그 기간을 조회합니다. 종료일은 포함됩니다.</Alert>}
      {rangeError && <Alert kind="error">{rangeError}</Alert>}
      {error && (
        <Alert kind="error" onDismiss={() => setError(null)}>
          {error}
        </Alert>
      )}
      <section className={stale ? 'panel is-refreshing' : 'panel'} aria-labelledby="requests-heading">
        <div className="panel-head">
          <h2 id="requests-heading">요청</h2>
          {page && <span className="muted">{formatCount(page.records.length)}건 표시</span>}
        </div>
        {page ? <RequestTable records={page.records} expanded={expanded} onToggle={toggle} /> : <p className="empty">불러오는 중…</p>}
        {page && page.records.length > 0 && (
          <div className="panel-body row" style={{ justifyContent: 'center' }}>
            {page.nextBefore === null ? (
              <span className="muted">끝</span>
            ) : (
              <button type="button" className="btn" onClick={() => void loadMore()} disabled={loadingMore || stale}>
                {loadingMore ? '불러오는 중…' : '더 보기'}
              </button>
            )}
          </div>
        )}
      </section>
    </div>
  )
}
