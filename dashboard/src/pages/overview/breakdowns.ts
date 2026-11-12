import type { GroupBy } from '../../api/types'

export type Breakdown = Exclude<GroupBy, 'day'>

export const BREAKDOWN_LABELS: Record<Breakdown, string> = { project: '프로젝트', model: '모델', tag: '태그' }

export const BREAKDOWNS: readonly { value: Breakdown; label: string }[] = (['project', 'model', 'tag'] as const).map((value) => ({ value, label: BREAKDOWN_LABELS[value] }))
