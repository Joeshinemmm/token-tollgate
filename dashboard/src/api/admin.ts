import type { Client } from './client'
import type {
  IssuedKey,
  Price,
  Project,
  Provider,
  UpsertPriceInput,
  UsageRecordsPage,
  UsageRecordsParams,
  UsageSummary,
  UsageSummaryParams,
  VirtualKey,
} from './types'

export interface ProjectPatch {
  name?: string
  archived?: boolean
}

/** `?a=b&c=d` from the defined, non-empty entries of [params]; empty when there are none. */
export function queryString(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === '') continue
    search.set(key, String(value))
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

/** One function per admin endpoint; the screens never build URLs themselves. */
export function createAdminApi(client: Client) {
  return {
    listProviders: () => client.get<Provider[]>('/providers'),

    listProjects: (includeArchived: boolean) =>
      client.get<Project[]>(`/projects?includeArchived=${includeArchived ? 'true' : 'false'}`),
    createProject: (name: string) => client.post<Project>('/projects', { name }),
    updateProject: (id: string, patch: ProjectPatch) => client.patch<Project>(`/projects/${id}`, patch),

    listKeys: (projectId: string) => client.get<VirtualKey[]>(`/keys?projectId=${encodeURIComponent(projectId)}`),
    issueKey: (projectId: string, name: string) => client.post<IssuedKey>(`/projects/${projectId}/keys`, { name }),
    revokeKey: (id: string) => client.delete(`/keys/${id}`),

    listPrices: () => client.get<Price[]>('/prices'),
    /** 201 when the (provider, model, effectiveFrom) row is new, 200 when its rates were replaced. */
    upsertPrice: (input: UpsertPriceInput) => client.put<Price>('/prices', input),
    deletePrice: (id: string) => client.delete(`/prices/${id}`),

    usageSummary: (params: UsageSummaryParams) => client.get<UsageSummary>(`/usage/summary${queryString(params)}`),
    usageRecords: (params: UsageRecordsParams) => client.get<UsageRecordsPage>(`/usage/records${queryString(params)}`),
  }
}

export type AdminApi = ReturnType<typeof createAdminApi>
