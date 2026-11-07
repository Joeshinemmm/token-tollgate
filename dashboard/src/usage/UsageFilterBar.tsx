import { useState, type KeyboardEvent } from 'react'
import { OUTCOMES, type Project, type Provider } from '../api/types'
import { Segmented } from '../components/Segmented'
import { OUTCOME_LABELS, RANGE_PRESETS, type UsageFilters } from './filters'

interface UsageFilterBarProps {
  filters: UsageFilters
  onChange: (next: UsageFilters) => void
  projects: Project[]
  providers: Provider[]
  timeZone: string
  rangeError?: string | null
  showModel?: boolean
  showOutcome?: boolean
}

/** The one row of controls that scopes everything under it on the usage screens. */
export function UsageFilterBar({ filters, onChange, projects, providers, timeZone, rangeError, showModel, showOutcome }: UsageFilterBarProps) {
  const set = <K extends keyof UsageFilters>(key: K, value: UsageFilters[K]) => onChange({ ...filters, [key]: value })
  const providerNames = [...new Set(providers.map((provider) => provider.name))]

  return (
    <div className="filter-bar" role="group" aria-label="조회 조건">
      <Segmented label="기간" options={RANGE_PRESETS} value={filters.range} onChange={(range) => set('range', range)} />
      {filters.range === 'custom' && (
        <div className="filter-group">
          <label>
            시작{' '}
            <input type="date" className="input" aria-label="시작일" value={filters.from} onChange={(event) => set('from', event.target.value)} aria-invalid={Boolean(rangeError)} />
          </label>
          <span className="muted">~</span>
          <label>
            종료{' '}
            <input type="date" className="input" aria-label="종료일" value={filters.to} onChange={(event) => set('to', event.target.value)} aria-invalid={Boolean(rangeError)} />
          </label>
        </div>
      )}
      <label className="filter-group">
        프로젝트{' '}
        <select className="select" value={filters.projectId} onChange={(event) => set('projectId', event.target.value)}>
          <option value="">전체</option>
          {projects.map((project) => (
            <option key={project.id} value={project.id}>
              {project.name}
              {project.archivedAt ? ' (보관됨)' : ''}
            </option>
          ))}
        </select>
      </label>
      <label className="filter-group">
        공급처{' '}
        <select className="select" value={filters.provider} onChange={(event) => set('provider', event.target.value)}>
          <option value="">전체</option>
          {providerNames.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>
      <CommitText key={`tag:${filters.tag}`} label="태그" value={filters.tag} onCommit={(value) => set('tag', value)} />
      {showModel && <CommitText key={`model:${filters.model}`} label="모델" value={filters.model} onCommit={(value) => set('model', value)} />}
      {showOutcome && (
        <label className="filter-group">
          결과{' '}
          <select className="select" value={filters.outcome} onChange={(event) => set('outcome', event.target.value as UsageFilters['outcome'])}>
            <option value="">전체</option>
            {OUTCOMES.map((outcome) => (
              <option key={outcome} value={outcome}>
                {OUTCOME_LABELS[outcome]} ({outcome})
              </option>
            ))}
          </select>
        </label>
      )}
      <span className="filter-tz muted">시간대 {timeZone}</span>
    </div>
  )
}

/** A text filter that applies on Enter or blur, so that typing does not fire a query per keystroke. */
function CommitText({ label, value, onCommit }: { label: string; value: string; onCommit: (value: string) => void }) {
  const [draft, setDraft] = useState(value)
  const commit = () => {
    const trimmed = draft.trim()
    if (trimmed !== value) onCommit(trimmed)
  }
  const onKey = (event: KeyboardEvent<HTMLInputElement>) => {
    if (event.key === 'Enter') {
      event.preventDefault()
      commit()
    }
  }
  return (
    <label className="filter-group">
      {label}{' '}
      <input className="input" value={draft} placeholder="정확히 일치" onChange={(event) => setDraft(event.target.value)} onBlur={commit} onKeyDown={onKey} />
    </label>
  )
}
