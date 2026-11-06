import { vi } from 'vitest'

export interface RecordedCall {
  method: string
  /** The path after `/admin/v1`, query string included. */
  path: string
  headers: Record<string, string>
  body: unknown
}

export interface FakeReply {
  status: number
  body?: unknown
}

export type FakeHandler = (call: RecordedCall) => FakeReply

interface FakeRoute {
  method: string
  path: string | RegExp
  handler: FakeHandler
}

export function reply(status: number, body?: unknown): FakeReply {
  return { status, body }
}

export function errorReply(status: number, code: string, message: string): FakeReply {
  return { status, body: { error: { code, message } } }
}

/** A stand-in for the admin API behind `fetch`: a route table and a record of every call. */
export class FakeAdminApi {
  readonly calls: RecordedCall[] = []
  private readonly routes: FakeRoute[] = []

  /** Registers a route; the route registered last wins when several match. */
  on(method: string, path: string | RegExp, handler: FakeHandler | FakeReply): this {
    this.routes.push({ method, path, handler: typeof handler === 'function' ? handler : () => handler })
    return this
  }

  install(): this {
    vi.stubGlobal('fetch', this.fetch)
    return this
  }

  callsTo(method: string, pathPrefix: string): RecordedCall[] {
    return this.calls.filter((call) => call.method === method && call.path.startsWith(pathPrefix))
  }

  private readonly fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = typeof input === 'string' ? input : input instanceof URL ? input.href : input.url
    const path = url.replace(/^(https?:\/\/[^/]+)?\/admin\/v1/, '')
    const method = init?.method ?? 'GET'
    const call: RecordedCall = {
      method,
      path,
      headers: (init?.headers as Record<string, string> | undefined) ?? {},
      body: typeof init?.body === 'string' ? JSON.parse(init.body) : undefined,
    }
    this.calls.push(call)
    const route = [...this.routes].reverse().find((candidate) => candidate.method === method && matches(candidate.path, path))
    const result = route ? route.handler(call) : errorReply(500, 'no_route', `가짜 API에 ${method} ${path} 경로가 없습니다.`)
    if (result.status === 204 || result.body === undefined) return new Response(null, { status: result.status })
    return new Response(JSON.stringify(result.body), { status: result.status, headers: { 'Content-Type': 'application/json' } })
  }
}

function matches(pattern: string | RegExp, path: string): boolean {
  return typeof pattern === 'string' ? pattern === path : pattern.test(path)
}

export function fakeAdminApi(): FakeAdminApi {
  return new FakeAdminApi().install()
}
