import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApiClient } from './client'
import { createMemoryTokenStore } from './tokenStore'

function json(status: number, body: unknown, contentType = 'application/json') {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': contentType } })
}

function setup(
  responses: Array<Response | ((req: Request) => Response)>,
  refreshResult: string | null = 'new-token',
) {
  const tokens = createMemoryTokenStore()
  tokens.set('old-token')
  const calls: Request[] = []
  const queue = [...responses]
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const req = new Request(new URL(String(input), 'http://localhost'), init)
    calls.push(req)
    const next = queue.shift()
    if (!next) throw new Error('unexpected fetch')
    return typeof next === 'function' ? next(req) : next
  })
  const refresh = vi.fn(async () => refreshResult)
  const onAuthFailure = vi.fn()
  const client = createApiClient({ baseUrl: '/api/v1', tokens, refresh, onAuthFailure, fetchImpl })
  return { client, tokens, calls, refresh, onAuthFailure, fetchImpl }
}

describe('api client', () => {
  it('sends the bearer token and parses JSON', async () => {
    const { client, calls } = setup([json(200, { ok: true })])
    await expect(client.get('/me')).resolves.toEqual({ ok: true })
    expect(calls[0].headers.get('Authorization')).toBe('Bearer old-token')
    expect(calls[0].url).toBe('http://localhost/api/v1/me')
  })

  it('refreshes once on 401 and retries with the new token', async () => {
    const { client, calls, refresh, tokens } = setup([json(401, {}), json(200, { ok: 1 })])
    await expect(client.get('/me')).resolves.toEqual({ ok: 1 })
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(calls[1].headers.get('Authorization')).toBe('Bearer new-token')
    expect(tokens.get()).toBe('new-token')
  })

  it('shares a single refresh between concurrent 401s', async () => {
    const { client, refresh } = setup([
      json(401, {}),
      json(401, {}),
      json(401, {}),
      json(200, 1),
      json(200, 2),
      json(200, 3),
    ])
    await Promise.all([client.get('/a'), client.get('/b'), client.get('/c')])
    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('clears the token and reports auth failure when refresh fails', async () => {
    const { client, tokens, onAuthFailure } = setup(
      [json(401, { status: 401, title: 'Unauthorized' })],
      null,
    )
    const error = await client.get('/me').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).status).toBe(401)
    expect(tokens.get()).toBeNull()
    expect(onAuthFailure).toHaveBeenCalledTimes(1)
  })

  it('does not loop when the retried request is still 401', async () => {
    const { client, refresh, fetchImpl } = setup([json(401, {}), json(401, { status: 401 })])
    await expect(client.get('/me')).rejects.toBeInstanceOf(ApiError)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(fetchImpl).toHaveBeenCalledTimes(2)
  })

  it('exposes problem details from error responses', async () => {
    const problem = {
      status: 400,
      title: 'Bad Request',
      requestId: 'r1',
      errors: [{ field: 'email', message: 'must be valid' }],
    }
    const { client } = setup([json(400, problem, 'application/problem+json')])
    const error = (await client.post('/x', { a: 1 }).catch((e: unknown) => e)) as ApiError
    expect(error.problem.requestId).toBe('r1')
    expect(error.problem.errors?.[0].field).toBe('email')
  })

  it('handles non-JSON error bodies', async () => {
    const { client } = setup([
      new Response('gateway down', { status: 502, statusText: 'Bad Gateway' }),
    ])
    const error = (await client.get('/x').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(502)
    expect(error.problem.title).toBe('Bad Gateway')
  })

  it('sends JSON bodies with content-type and returns undefined for 204', async () => {
    const { client, calls } = setup([new Response(null, { status: 204 })])
    await expect(client.post('/x', { a: 1 })).resolves.toBeUndefined()
    expect(calls[0].headers.get('Content-Type')).toBe('application/json')
    expect(await calls[0].text()).toBe('{"a":1}')
  })

  it('does not attempt a refresh when no token was sent (e.g. a failed login)', async () => {
    const { client, tokens, refresh, onAuthFailure } = setup([
      json(401, { status: 401, title: 'Invalid credentials' }),
    ])
    tokens.set(null)
    const error = (await client.post('/auth/login', { a: 1 }).catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(401)
    expect(error.problem.title).toBe('Invalid credentials')
    expect(refresh).not.toHaveBeenCalled()
    expect(onAuthFailure).not.toHaveBeenCalled()
  })

  it('lets a request opt out of refresh (e.g. the refresh call itself)', async () => {
    const { client, refresh } = setup([json(401, { status: 401 })])
    await expect(
      client.post('/auth/refresh', undefined, { skipAuthRefresh: true }),
    ).rejects.toBeInstanceOf(ApiError)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('reuses an already-refreshed token for a late 401 instead of refreshing again', async () => {
    let releaseSlow: (r: Response) => void = () => {}
    const slow = new Promise<Response>((resolve) => {
      releaseSlow = resolve
    })
    const tokens = createMemoryTokenStore()
    tokens.set('old-token')
    const seen: Array<{ path: string; auth: string | null }> = []
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const path = String(input)
      const auth = new Headers(init?.headers).get('Authorization')
      seen.push({ path, auth })
      if (path.endsWith('/slow') && auth === 'Bearer old-token') return slow
      if (auth === 'Bearer old-token') return json(401, {})
      return json(200, { path })
    })
    const refresh = vi.fn(async () => 'new-token')
    const client = createApiClient({ baseUrl: '/api/v1', tokens, refresh, fetchImpl })

    const slowRequest = client.get('/slow')
    await expect(client.get('/fast')).resolves.toEqual({ path: '/api/v1/fast' })
    releaseSlow(json(401, {}))
    await expect(slowRequest).resolves.toEqual({ path: '/api/v1/slow' })

    expect(refresh).toHaveBeenCalledTimes(1)
    expect(seen.at(-1)).toEqual({ path: '/api/v1/slow', auth: 'Bearer new-token' })
  })
})
