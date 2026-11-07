import type { GroupBy, UsageGroup } from '../../api/types'
import { formatCount, formatPercent, formatUsd } from '../../format'

export type Breakdown = Exclude<GroupBy, 'day'>

interface BreakdownTableProps {
  groupBy: Breakdown
  groups: UsageGroup[]
  totalCost: number
  onPick: (group: UsageGroup) => void
}

const NAME_HEADS: Record<Breakdown, string> = { project: '프로젝트', model: '모델', tag: '태그' }

function displayName(groupBy: Breakdown, group: UsageGroup): string {
  if (groupBy === 'project') return group.label ?? group.key ?? '(없음)'
  if (groupBy === 'model') return `${group.provider ?? '?'}/${group.key ?? '?'}`
  return group.key ?? '태그 없음'
}

/** Groups ranked by cost, each with a one-hue share bar; the name opens the request list with that group as a filter. */
export function BreakdownTable({ groupBy, groups, totalCost, onPick }: BreakdownTableProps) {
  const rows = [...groups].sort((a, b) => b.cost - a.cost || b.requests - a.requests)
  if (rows.length === 0) return <p className="empty">이 기간에 기록이 없습니다.</p>
  return (
    <table className="table">
      <thead>
        <tr>
          <th scope="col">{NAME_HEADS[groupBy]}</th>
          <th scope="col" className="num">
            요청
          </th>
          <th scope="col" className="num">
            성공
          </th>
          <th scope="col" className="num">
            입력
          </th>
          <th scope="col" className="num">
            캐시
          </th>
          <th scope="col" className="num">
            출력
          </th>
          <th scope="col" className="num">
            비용
          </th>
          <th scope="col">비율</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((group) => {
          const name = displayName(groupBy, group)
          const share = totalCost > 0 ? Math.min(1, group.cost / totalCost) : 0
          return (
            <tr key={`${group.provider ?? ''}|${group.key ?? ''}`}>
              <td>
                {group.key === null ? (
                  <span className="muted">{name}</span>
                ) : (
                  <button type="button" className="btn-link" onClick={() => onPick(group)} title="이 조건으로 요청 목록 보기">
                    {name}
                  </button>
                )}
              </td>
              <td className="num">{formatCount(group.requests)}</td>
              <td className="num">{formatCount(group.okRequests)}</td>
              <td className="num">{formatCount(group.inputTokens)}</td>
              <td className="num">{formatCount(group.cachedInputTokens)}</td>
              <td className="num">{formatCount(group.outputTokens)}</td>
              <td className="num">{formatUsd(group.cost)}</td>
              <td>
                <div className="share">
                  <div className="rank-bar" aria-hidden="true">
                    <span style={{ width: `${share * 100}%` }} />
                  </div>
                  <span className="num">{formatPercent(group.cost, totalCost)}</span>
                </div>
              </td>
            </tr>
          )
        })}
      </tbody>
    </table>
  )
}
