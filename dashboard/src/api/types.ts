// Mirrors the admin API DTOs in server/src/main/kotlin/tokentollgate/admin/AdminDtos.kt.

export interface Project {
  id: string
  name: string
  createdAt: string
  archivedAt: string | null
}

export interface VirtualKey {
  id: string
  projectId: string
  name: string
  keyPrefix: string
  createdAt: string
  revokedAt: string | null
  lastUsedAt: string | null
}

/** Returned once, when a key is issued; `key` is the plaintext the server never stores. */
export interface IssuedKey {
  id: string
  projectId: string
  name: string
  keyPrefix: string
  createdAt: string
  key: string
}

export interface Price {
  id: string
  provider: string
  model: string
  inputPerMtok: number
  outputPerMtok: number
  cachedInputPerMtok: number | null
  currency: string
  effectiveFrom: string
  createdAt: string
}

export interface Provider {
  name: string
  type: string
  baseUrl: string
  enabled: boolean
  default: boolean
  models: string[]
}

export interface AdminErrorBody {
  code: string
  message: string
}

export interface UpsertPriceInput {
  provider: string
  model: string
  inputPerMtok: number
  outputPerMtok: number
  cachedInputPerMtok: number | null
  effectiveFrom: string
}

// Usage views (mirrors admin/UsageDtos.kt). Times are ISO instants; costs are USD numbers.

export type GroupBy = 'day' | 'project' | 'model' | 'tag'

export type Outcome = 'ok' | 'upstream_error' | 'client_abort' | 'timeout' | 'gateway_error'

export const OUTCOMES: readonly Outcome[] = ['ok', 'upstream_error', 'client_abort', 'timeout', 'gateway_error']

export interface UsageMetrics {
  requests: number
  okRequests: number
  inputTokens: number
  cachedInputTokens: number
  outputTokens: number
  cost: number
  priceMissingRequests: number
  usageUnknownRequests: number
}

export interface UsageGroup extends UsageMetrics {
  /** The day (YYYY-MM-DD), project id, model or tag; null for records without a tag. */
  key: string | null
  /** The project name when grouped by project. */
  label: string | null
  /** The provider when grouped by model. */
  provider: string | null
}

export interface UsageSummary {
  from: string
  to: string
  tz: string
  groupBy: GroupBy
  totals: UsageMetrics
  groups: UsageGroup[]
}

export interface UsageRecord {
  id: number
  requestId: string
  startedAt: string
  projectId: string
  projectName: string | null
  keyId: string
  keyPrefix: string | null
  tag: string | null
  protocol: string
  provider: string
  modelRequested: string
  modelServed: string | null
  inputTokens: number
  cachedInputTokens: number
  outputTokens: number
  cost: number | null
  usageSource: 'response' | 'stream' | 'none'
  ttftMs: number | null
  durationMs: number
  httpStatus: number
  streamed: boolean
  outcome: Outcome
  errorType: string | null
}

export interface UsageRecordsPage {
  records: UsageRecord[]
  nextBefore: number | null
}

/** Query parameters shared by both usage endpoints; an undefined field is left out of the URL. */
export type UsageQueryParams = {
  from?: string
  to?: string
  tz?: string
  projectId?: string
  tag?: string
  model?: string
  provider?: string
}

export type UsageSummaryParams = UsageQueryParams & { groupBy: GroupBy }

export type UsageRecordsParams = UsageQueryParams & { outcome?: Outcome; limit?: number; before?: number }
