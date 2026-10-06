import { createApiClient, type ApiClient } from './client'
import { createMemoryTokenStore, type TokenStore } from './tokenStore'
import type { TokenResponse } from './types'

export interface SessionApi {
  client: ApiClient
  tokens: TokenStore
  /**
   * One cookie-based refresh at boot. Memoized: React StrictMode mounts twice, and two concurrent
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
}

export function createSessionApi({
  baseUrl = '/api/v1',
  refreshPath,
  fetchImpl,
}: SessionApiOptions): SessionApi {
  const tokens = createMemoryTokenStore()
  const listeners = new Set<() => void>()

  async function refresh(): Promise<string | null> {
    const response = await client.post<TokenResponse>(refreshPath, undefined, {
      skipAuthRefresh: true,
    })
    return response.accessToken
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
      restoring ??= refresh()
        .then((token) => {
          tokens.set(token)
          return token !== null
        })
        .catch(() => false)
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
