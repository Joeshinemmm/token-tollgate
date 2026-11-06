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
