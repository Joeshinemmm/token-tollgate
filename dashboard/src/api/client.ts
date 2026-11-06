import type { AdminErrorBody } from './types'

/** A failed admin API call: the HTTP status, the server's error code, and a message fit for the screen. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export const NETWORK_ERROR_MESSAGE = '서버에 연결할 수 없습니다. 서버 주소와 실행 상태를 확인하세요.'

export interface ClientOptions {
  /** The admin token to send, read on every call so that a login or logout takes effect at once. */
  token: () => string | null
  /** Called when any call is answered with 401, before the error is thrown. */
  onUnauthorized?: () => void
  baseUrl?: string
}

export interface ApiResponse<T> {
  status: number
  data: T
}

export interface Client {
  send<T>(method: string, path: string, body?: unknown): Promise<ApiResponse<T>>
  get<T>(path: string): Promise<T>
  post<T>(path: string, body: unknown): Promise<T>
  put<T>(path: string, body: unknown): Promise<ApiResponse<T>>
  patch<T>(path: string, body: unknown): Promise<T>
  delete(path: string): Promise<void>
}

/** A thin fetch wrapper: JSON in and out, the bearer token on every call, errors in one shape. */
export function createClient(options: ClientOptions): Client {
  const base = options.baseUrl ?? '/admin/v1'

  async function send<T>(method: string, path: string, body?: unknown): Promise<ApiResponse<T>> {
    const headers: Record<string, string> = { Accept: 'application/json' }
    const token = options.token()
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'

    let response: Response
    try {
      response = await fetch(base + path, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
      })
    } catch {
      throw new ApiError(0, 'network', NETWORK_ERROR_MESSAGE)
    }

    if (response.status === 401) options.onUnauthorized?.()
    if (!response.ok) throw await toApiError(response)
    if (response.status === 204) return { status: response.status, data: undefined as T }
    return { status: response.status, data: (await response.json()) as T }
  }

  return {
    send,
    get: async <T,>(path: string) => (await send<T>('GET', path)).data,
    post: async <T,>(path: string, body: unknown) => (await send<T>('POST', path, body)).data,
    put: <T,>(path: string, body: unknown) => send<T>('PUT', path, body),
    patch: async <T,>(path: string, body: unknown) => (await send<T>('PATCH', path, body)).data,
    delete: async (path: string) => {
      await send<void>('DELETE', path)
    },
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let code = `http_${response.status}`
  let message = `요청이 실패했습니다 (HTTP ${response.status})`
  try {
    const body = (await response.json()) as { error?: Partial<AdminErrorBody> }
    if (body?.error?.message) {
      message = body.error.message
      if (body.error.code) code = body.error.code
    }
  } catch {
    // A body that is not JSON keeps the generic message.
  }
  return new ApiError(response.status, code, message)
}

/** The message to show for any thrown value. */
export function errorMessage(error: unknown): string {
  if (error instanceof Error) return error.message
  return '알 수 없는 오류가 발생했습니다.'
}
