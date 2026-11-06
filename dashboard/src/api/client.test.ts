import { describe, expect, it, vi } from 'vitest'
import { errorReply, fakeAdminApi, reply } from '../test/fakeApi'
import { ApiError, createClient } from './client'

describe('createClient', () => {
  it('sends the token as a Bearer header and parses the JSON body', async () => {
    const fake = fakeAdminApi().on('GET', '/providers', reply(200, [{ name: 'fake' }]))
    const client = createClient({ token: () => 'secret-token' })

    const providers = await client.get<{ name: string }[]>('/providers')

    expect(providers).toEqual([{ name: 'fake' }])
    expect(fake.calls).toHaveLength(1)
    expect(fake.calls[0].headers.Authorization).toBe('Bearer secret-token')
  })

  it('turns the server error body into an ApiError with status, code and message', async () => {
    fakeAdminApi().on('GET', '/projects/missing', errorReply(404, 'not_found', '프로젝트가 없습니다.'))
    const client = createClient({ token: () => 'secret-token' })

    const failure = await client.get('/projects/missing').catch((error: unknown) => error)

    expect(failure).toBeInstanceOf(ApiError)
    const apiError = failure as ApiError
    expect(apiError.status).toBe(404)
    expect(apiError.code).toBe('not_found')
    expect(apiError.message).toBe('프로젝트가 없습니다.')
  })

  it('calls onUnauthorized on 401 and still throws', async () => {
    fakeAdminApi().on('GET', '/providers', errorReply(401, 'unauthorized', '인증 실패'))
    const onUnauthorized = vi.fn()
    const client = createClient({ token: () => 'wrong', onUnauthorized })

    await expect(client.get('/providers')).rejects.toMatchObject({ status: 401 })
    expect(onUnauthorized).toHaveBeenCalledTimes(1)
  })
})
