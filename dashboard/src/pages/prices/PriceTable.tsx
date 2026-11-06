import { Fragment, type ReactNode } from 'react'
import type { Price } from '../../api/types'
import { formatUsd, formatUtcDate } from '../../format'
import { groupKey, type PriceGroup } from './priceModel'

interface PriceTableProps {
  groups: PriceGroup[]
  expanded: ReadonlySet<string>
  onToggleHistory: (groupKey: string) => void
  onEdit: (price: Price) => void
  onDelete: (price: Price) => void
}

export function PriceTable({ groups, expanded, onToggleHistory, onEdit, onDelete }: PriceTableProps) {
  if (groups.length === 0) return <p className="empty">요금표가 비어 있습니다. 위 폼으로 첫 단가를 추가하세요.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th scope="col">공급처</th>
          <th scope="col">모델</th>
          <th scope="col" className="num">
            입력
          </th>
          <th scope="col" className="num">
            캐시 입력
          </th>
          <th scope="col" className="num">
            출력
          </th>
          <th scope="col">적용 시작 (UTC)</th>
          <th scope="col">이력</th>
          <th scope="col">
            <span className="visually-hidden">동작</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {groups.map((group) => {
          const key = groupKey(group)
          const open = expanded.has(key)
          return (
            <Fragment key={key}>
              <PriceRow price={group.latest} onEdit={onEdit} onDelete={onDelete}>
                {group.history.length > 0 ? (
                  <button type="button" className="btn btn-link" onClick={() => onToggleHistory(key)} aria-expanded={open} aria-label={`${group.model} 이력 ${open ? '접기' : '펼치기'}`}>
                    이력 {group.history.length}
                  </button>
                ) : (
                  <span className="muted">—</span>
                )}
              </PriceRow>
              {open &&
                group.history.map((price) => (
                  <PriceRow key={price.id} price={price} history onEdit={onEdit} onDelete={onDelete}>
                    <span className="muted">이전</span>
                  </PriceRow>
                ))}
            </Fragment>
          )
        })}
      </tbody>
    </table>
  )
}

function PriceRow({
  price,
  history,
  children,
  onEdit,
  onDelete,
}: {
  price: Price
  history?: boolean
  children: ReactNode
  onEdit: (price: Price) => void
  onDelete: (price: Price) => void
}) {
  const label = `${price.provider} ${price.model} ${formatUtcDate(price.effectiveFrom)}`
  return (
    <tr className={history ? 'is-history' : undefined}>
      <td>{price.provider}</td>
      <td>{price.model}</td>
      <td className="num">{formatUsd(price.inputPerMtok)}</td>
      <td className="num">{price.cachedInputPerMtok === null ? <span className="muted">입력과 같음</span> : formatUsd(price.cachedInputPerMtok)}</td>
      <td className="num">{formatUsd(price.outputPerMtok)}</td>
      <td>{formatUtcDate(price.effectiveFrom)}</td>
      <td>{children}</td>
      <td>
        <div className="table-actions">
          <button type="button" className="btn btn-small" onClick={() => onEdit(price)} aria-label={`${label} 수정`}>
            수정
          </button>
          <button type="button" className="btn btn-small btn-danger" onClick={() => onDelete(price)} aria-label={`${label} 삭제`}>
            삭제
          </button>
        </div>
      </td>
    </tr>
  )
}
