import { Fragment } from 'react'
import type { Outcome, UsageRecord } from '../../api/types'
import { Badge, type BadgeTone } from '../../components/Badge'
import { CopyButton } from '../../components/CopyButton'
import { formatCount, formatLogTime, formatMs, formatUsd } from '../../format'
import { OUTCOME_LABELS } from '../../usage/filters'

interface RequestTableProps {
  records: UsageRecord[]
  expanded: ReadonlySet<number>
  onToggle: (id: number) => void
}

function outcomeTone(outcome: Outcome): BadgeTone {
  if (outcome === 'ok') return 'active'
  if (outcome === 'client_abort') return 'neutral'
  return 'revoked'
}

function costReason(record: UsageRecord): string {
  return record.usageSource === 'none' ? '사용량을 알 수 없어 비용을 계산하지 못했습니다.' : '단가가 없어 비용을 계산하지 못했습니다.'
}

const COLUMNS = 9

export function RequestTable({ records, expanded, onToggle }: RequestTableProps) {
  if (records.length === 0) return <p className="empty">조건에 맞는 요청이 없습니다.</p>
  return (
    <table className="table table-wide">
      <thead>
        <tr>
          <th scope="col">
            <span className="visually-hidden">상세</span>
          </th>
          <th scope="col">시각</th>
          <th scope="col">프로젝트</th>
          <th scope="col">태그</th>
          <th scope="col">모델</th>
          <th scope="col" className="num">
            토큰 (입력 · 캐시 · 출력)
          </th>
          <th scope="col" className="num">
            비용
          </th>
          <th scope="col" className="num">
            TTFT / 소요
          </th>
          <th scope="col">결과</th>
        </tr>
      </thead>
      <tbody>
        {records.map((record) => {
          const open = expanded.has(record.id)
          return (
            <Fragment key={record.id}>
              <tr>
                <td>
                  <button type="button" className="btn btn-small" aria-expanded={open} onClick={() => onToggle(record.id)}>
                    {open ? '접기' : '자세히'}
                  </button>
                </td>
                <td className="num">{formatLogTime(record.startedAt)}</td>
                <td>
                  {record.projectName ?? record.projectId.slice(0, 8)}
                  {record.keyPrefix && <div className="muted mono small">{record.keyPrefix}…</div>}
                </td>
                <td>{record.tag ?? <span className="muted">—</span>}</td>
                <td>
                  <span className="muted">{record.provider}/</span>
                  {record.modelServed ?? record.modelRequested}
                </td>
                <td className="num">
                  {formatCount(record.inputTokens)} · {formatCount(record.cachedInputTokens)} · {formatCount(record.outputTokens)}
                </td>
                <td className="num">
                  {record.cost === null ? (
                    <span className="muted" title={costReason(record)}>
                      —
                    </span>
                  ) : (
                    formatUsd(record.cost)
                  )}
                </td>
                <td className="num">
                  {formatMs(record.ttftMs)} / {formatMs(record.durationMs)}
                </td>
                <td>
                  <Badge tone={outcomeTone(record.outcome)}>{OUTCOME_LABELS[record.outcome]}</Badge>{' '}
                  <span className="muted small">
                    {record.httpStatus}
                    {record.streamed ? ' · 스트림' : ''}
                  </span>
                </td>
              </tr>
              {open && (
                <tr className="detail-row">
                  <td colSpan={COLUMNS}>
                    <dl className="detail-grid">
                      <dt>요청 ID</dt>
                      <dd className="row">
                        <span className="mono">{record.requestId}</span>
                        <CopyButton text={record.requestId} />
                      </dd>
                      <dt>키 ID</dt>
                      <dd className="mono">{record.keyId}</dd>
                      <dt>프로토콜</dt>
                      <dd>{record.protocol}</dd>
                      <dt>요청 모델</dt>
                      <dd>{record.modelRequested}</dd>
                      <dt>응답 모델</dt>
                      <dd>{record.modelServed ?? '—'}</dd>
                      <dt>사용량 출처</dt>
                      <dd>{record.usageSource}</dd>
                      <dt>오류 종류</dt>
                      <dd>{record.errorType ?? '—'}</dd>
                    </dl>
                  </td>
                </tr>
              )}
            </Fragment>
          )
        })}
      </tbody>
    </table>
  )
}
