import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { createSessionApi } from './session'

describe('createSessionApi', () => {
  it('restores once even when asked twice (StrictMode double mount)', async () => {
    const server = fakeServer({
      'POST /auth/refresh': { body: { accessToken: 't1', tokenType: 'Bearer', expiresIn: 900 } },
    })
    const api = createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl })
    const [a, b] = await Promise.all([api.restore(), api.restore()])
    expect(a && b).toBe(true)
    expect(server.callsTo('POST /auth/refresh')).toHaveLength(1)
    expect(api.tokens.get()).toBe('t1')
  })

  it('reports a failed restore without throwing', async () => {
    const server = fakeServer({
      'POST /auth/refresh': { status: 401, body: { detail: 'expired' } },
    })
    const api = createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl })
    await expect(api.restore()).resolves.toBe(false)
    expect(api.tokens.get()).toBeNull()
  })

  it('refreshes silently when a sent token is rejected', async () => {
    let calls = 0
    const server = fakeServer({
      'POST /auth/refresh': { body: { accessToken: 'fresh', tokenType: 'Bearer', expiresIn: 900 } },
      'GET /me': (req) =>
        ++calls === 1
          ? { status: 401, body: {} }
          : { body: { auth: req.headers.get('Authorization') } },
    })
    const api = createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl })
    api.tokens.set('stale')
    await expect(api.client.get('/me')).resolves.toEqual({ auth: 'Bearer fresh' })
  })
})
