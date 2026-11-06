import type { VirtualKey } from '../../api/types'
import { Badge } from '../../components/Badge'
import { formatDateTime, formatRelative } from '../../format'

interface KeyTableProps {
  /** Null while the selected project's keys are still loading. */
  keys: VirtualKey[] | null
  onRevoke: (key: VirtualKey) => void
}

export function KeyTable({ keys, onRevoke }: KeyTableProps) {
  if (keys === null) return <p className="empty">불러오는 중…</p>
  if (keys.length === 0) return <p className="empty">이 프로젝트에 키가 없습니다. 키를 발급해 도구에 넣으세요.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th scope="col">이름</th>
          <th scope="col">키</th>
          <th scope="col">생성</th>
          <th scope="col">마지막 사용</th>
          <th scope="col">상태</th>
          <th scope="col">
            <span className="visually-hidden">동작</span>
          </th>
        </tr>
      </thead>
      <tbody>
        {keys.map((key) => {
          const revoked = key.revokedAt !== null
          return (
            <tr key={key.id} className={revoked ? 'is-dim' : undefined}>
              <td>{key.name}</td>
              <td className="mono">{key.keyPrefix}…</td>
              <td>{formatDateTime(key.createdAt)}</td>
              <td>{key.lastUsedAt ? formatRelative(key.lastUsedAt) : '사용 없음'}</td>
              <td>{revoked ? <Badge tone="revoked">폐기</Badge> : <Badge tone="active">활성</Badge>}</td>
              <td>
                <div className="table-actions">
                  {!revoked && (
                    <button type="button" className="btn btn-small btn-danger" onClick={() => onRevoke(key)} aria-label={`${key.name} 폐기`}>
                      폐기
                    </button>
                  )}
                </div>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
