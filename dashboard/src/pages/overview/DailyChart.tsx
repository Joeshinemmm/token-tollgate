import { Area, AreaChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { formatCount, formatUsd } from '../../format'
import { METRIC_TITLES, formatMetric, type Metric } from '../../usage/metrics'
import type { DayPoint } from '../../usage/period'

interface DailyChartProps {
  points: DayPoint[]
  metric: Metric
  showTable: boolean
}

function shortDay(day: string): string {
  return day.slice(5)
}

/** One series of one metric per day: an area wash under a 2px line, a crosshair tooltip, and a table twin. */
export function DailyChart({ points, metric, showTable }: DailyChartProps) {
  const empty = points.every((point) => point.requests === 0)
  return (
    <div>
      <div className="chart" role="img" aria-label={`일별 ${METRIC_TITLES[metric]}`}>
        <ResponsiveContainer width="100%" height={240}>
          <AreaChart data={points} margin={{ top: 12, right: 16, left: 0, bottom: 4 }}>
            <CartesianGrid vertical={false} stroke="var(--line)" />
            <XAxis dataKey="day" tickFormatter={shortDay} tick={{ fontSize: 12, fill: 'var(--ink-3)' }} tickLine={false} axisLine={{ stroke: 'var(--line)' }} minTickGap={20} />
            <YAxis
              tickFormatter={(value: number) => formatMetric(metric, value)}
              tick={{ fontSize: 12, fill: 'var(--ink-3)' }}
              tickLine={false}
              axisLine={false}
              width={72}
              allowDecimals={metric === 'cost'}
            />
            <Tooltip
              cursor={{ stroke: 'var(--line-strong)', strokeWidth: 1 }}
              isAnimationActive={false}
              content={(props) => <ChartTip active={props.active} point={props.payload?.[0]?.payload as DayPoint | undefined} metric={metric} />}
            />
            <Area
              type="monotone"
              dataKey={metric}
              stroke="var(--chart-series)"
              strokeWidth={2}
              fill="var(--chart-series)"
              fillOpacity={0.1}
              dot={false}
              activeDot={{ r: 4, fill: 'var(--chart-series)', stroke: 'var(--surface)', strokeWidth: 2 }}
              isAnimationActive={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      </div>
      {empty && <p className="muted chart-note">이 기간에 기록이 없습니다.</p>}
      {showTable && (
        <table className="table" aria-label="일별 표">
          <thead>
            <tr>
              <th scope="col">날짜</th>
              <th scope="col" className="num">
                비용
              </th>
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
            </tr>
          </thead>
          <tbody>
            {points.map((point) => (
              <tr key={point.day}>
                <td>{point.day}</td>
                <td className="num">{formatUsd(point.cost)}</td>
                <td className="num">{formatCount(point.requests)}</td>
                <td className="num">{formatCount(point.okRequests)}</td>
                <td className="num">{formatCount(point.inputTokens)}</td>
                <td className="num">{formatCount(point.cachedInputTokens)}</td>
                <td className="num">{formatCount(point.outputTokens)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  )
}

function ChartTip({ active, point, metric }: { active?: boolean; point: DayPoint | undefined; metric: Metric }) {
  if (!active || !point) return null
  return (
    <div className="chart-tip">
      <strong>{formatMetric(metric, point[metric])}</strong>
      <span className="muted">
        {point.day} · {METRIC_TITLES[metric]}
      </span>
    </div>
  )
}
