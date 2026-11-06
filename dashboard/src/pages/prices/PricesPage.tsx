import { useEffect, useMemo, useReducer, useState } from 'react'
import { ApiError, errorMessage } from '../../api/client'
import type { Price, Provider, UpsertPriceInput } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { Alert } from '../../components/Alert'
import { ConfirmDialog } from '../../components/ConfirmDialog'
import { formatUtcDate, todayIsoDate } from '../../format'
import { PriceForm } from './PriceForm'
import { PriceTable } from './PriceTable'
import { emptyPriceForm, groupPrices, priceToForm, type PriceFormValues } from './priceModel'

export const DELETE_CONFLICT_MESSAGE = '사용 기록이 참조하는 단가는 삭제할 수 없습니다. 더 늦은 적용 시작으로 새 행을 추가하세요.'

const increment = (count: number) => count + 1

export function PricesPage() {
  const { api } = useAuth()
  const [prices, setPrices] = useState<Price[]>([])
  const [providers, setProviders] = useState<Provider[]>([])
  const [version, reload] = useReducer(increment, 0)
  const [form, setForm] = useState<PriceFormValues>(() => emptyPriceForm(todayIsoDate()))
  const [formEpoch, resetFormErrors] = useReducer(increment, 0)
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [deleting, setDeleting] = useState<Price | null>(null)
  const [deleteBusy, setDeleteBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    Promise.all([api.listPrices(), api.listProviders()])
      .then(([priceRows, providerRows]) => {
        if (cancelled) return
        setPrices(priceRows)
        setProviders(providerRows)
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(errorMessage(err))
      })
    return () => {
      cancelled = true
    }
  }, [api, version])

  const groups = useMemo(() => groupPrices(prices), [prices])
  const providerNames = useMemo(() => [...new Set(providers.filter((provider) => provider.enabled).map((provider) => provider.name))], [providers])

  const resetForm = () => {
    setForm(emptyPriceForm(todayIsoDate()))
    resetFormErrors()
  }

  const save = async (input: UpsertPriceInput) => {
    setSaving(true)
    setError(null)
    setNotice(null)
    try {
      const response = await api.upsertPrice(input)
      const where = `${input.provider}/${input.model}, ${input.effectiveFrom}`
      setNotice(response.status === 201 ? `단가를 추가했습니다 (${where}).` : `단가를 교체했습니다 (${where}).`)
      resetForm()
      reload()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const confirmDelete = async () => {
    if (!deleting) return
    setDeleteBusy(true)
    setError(null)
    setNotice(null)
    try {
      await api.deletePrice(deleting.id)
      setNotice(`${deleting.provider}/${deleting.model}의 ${formatUtcDate(deleting.effectiveFrom)} 단가를 삭제했습니다.`)
      reload()
    } catch (err) {
      setError(err instanceof ApiError && err.status === 409 ? DELETE_CONFLICT_MESSAGE : errorMessage(err))
    } finally {
      setDeleteBusy(false)
      setDeleting(null)
    }
  }

  const toggleHistory = (key: string) =>
    setExpanded((current) => {
      const next = new Set(current)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  return (
    <div className="stack">
      <div className="page-head">
        <div>
          <h1>요금표</h1>
          <p>모델별 단가는 USD / 100만 토큰입니다. 요청 시각보다 앞서 시작한 행 중 가장 최근 행이 적용됩니다.</p>
        </div>
      </div>
      {error && (
        <Alert kind="error" onDismiss={() => setError(null)}>
          {error}
        </Alert>
      )}
      {notice && (
        <Alert kind="success" onDismiss={() => setNotice(null)}>
          {notice}
        </Alert>
      )}
      <section className="panel" aria-labelledby="price-form-heading">
        <div className="panel-head">
          <h2 id="price-form-heading">단가 추가</h2>
        </div>
        <div className="panel-body">
          <PriceForm key={formEpoch} values={form} providers={providerNames} busy={saving} onChange={setForm} onSubmit={save} onReset={resetForm} />
        </div>
      </section>
      <section className="panel" aria-labelledby="price-table-heading">
        <div className="panel-head">
          <h2 id="price-table-heading">현재 단가</h2>
          <span className="muted">
            {groups.length}개 모델, {prices.length}행
          </span>
        </div>
        <PriceTable
          groups={groups}
          expanded={expanded}
          onToggleHistory={toggleHistory}
          onEdit={(price) => {
            setForm(priceToForm(price))
            resetFormErrors()
            setNotice(null)
            window.scrollTo({ top: 0 })
          }}
          onDelete={setDeleting}
        />
        {groups.some((group) => group.history.length > 0) && (
          <p className="muted" style={{ padding: '8px 16px', fontSize: 13 }}>
            이력 열의 숫자를 누르면 그 모델의 이전 단가 행이 펼쳐집니다.
          </p>
        )}
      </section>
      <ConfirmDialog
        open={deleting !== null}
        title="단가 삭제"
        message={deleting ? `${deleting.provider}/${deleting.model}의 ${formatUtcDate(deleting.effectiveFrom)} 단가 행을 삭제합니다. 사용 기록이 참조하는 행은 서버가 거절합니다.` : ''}
        confirmLabel="삭제"
        danger
        busy={deleteBusy}
        onConfirm={() => void confirmDelete()}
        onCancel={() => setDeleting(null)}
      />
    </div>
  )
}
