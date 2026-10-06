import { ApiError, createApiClient, type ApiClient } from './client'
import { createMemoryTokenStore, type TokenStore } from './tokenStore'
import type { TokenResponse } from './types'

export interface SessionApi {
  client: ApiClient
  tokens: TokenStore
  /**
   * One cookie-based refresh at boot. Memoized while in flight: React StrictMode mounts twice, and two concurrent
   * refreshes would race the server's single-use rotation (ADR-0003).
   */
  restore(): Promise<boolean>
  /** Called when a request's 401 could not be recovered by refreshing. Returns an unsubscribe. */
  onAuthFailure(listener: () => void): () => void
}

export interface SessionApiOptions {
  baseUrl?: string
  refreshPath: string
  fetchImpl?: typeof fetch
  /**
   * How long to wait before retrying a refresh that got 401. Another tab sharing the cookie jar may have just rotated
   * the refresh token; the server answers the loser with 401 inside its 10 s reuse grace (without revoking), and by the
   * retry the browser holds the winner's new cookie. Default 300 ms; tests pass 0.
   */
  refreshRetryDelayMs?: number
}

const delay = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms))

export function createSessionApi({
  baseUrl = '/api/v1',
  refreshPath,
  fetchImpl,
  refreshRetryDelayMs = 300,
}: SessionApiOptions): SessionApi {
  const tokens = createMemoryTokenStore()
  const listeners = new Set<() => void>()

  async function postRefresh(): Promise<string | null> {
    const response = await client.post<TokenResponse>(refreshPath, undefined, {
      skipAuthRefresh: true,
    })
    return response.accessToken
  }

  /** Used by both restore() and the client's 401 path: one retry after a 401 (cross-tab rotation race). */
  async function refresh(): Promise<string | null> {
    try {
      return await postRefresh()
    } catch (error) {
      if (!(error instanceof ApiError && error.status === 401)) throw error
      await delay(refreshRetryDelayMs)
      return postRefresh()
    }
  }

  const client: ApiClient = createApiClient({
    baseUrl,
    tokens,
    refresh,
    fetchImpl,
    onAuthFailure: () => listeners.forEach((listener) => listener()),
  })

  let restoring: Promise<boolean> | null = null

  return {
    client,
    tokens,
    restore() {
      // Already holding a token (e.g. TenantRoot remounted after a sign-in): nothing to restore.
      if (tokens.get() !== null) return Promise.resolve(true)
      restoring ??= refresh()
        .then((token) => {
          tokens.set(token)
          return token !== null
        })
        .catch(() => false)
        .finally(() => {
          restoring = null
        })
      return restoring
    },
    onAuthFailure(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
  }
}
