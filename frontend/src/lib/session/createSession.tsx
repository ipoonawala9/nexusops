import { useQueryClient } from '@tanstack/react-query'
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import type { SessionApi } from '@/lib/api/session'
import type { TokenResponse } from '@/lib/api/types'

export type SessionState<P> =
  { status: 'loading' } | { status: 'anonymous' } | { status: 'authenticated'; profile: P }

export interface Session<P, L> {
  state: SessionState<P>
  login(input: L): Promise<void>
  logout(): Promise<void>
  reloadProfile(): Promise<void>
  /** Forget the session locally, e.g. after a server-side "sign out everywhere". */
  clear(): void
}

export interface SessionPaths {
  login: string
  logout: string
  profile: string
}

/** One factory for both the tenant and the platform session (they differ only in paths and types). */
export function createSession<P, L>(paths: SessionPaths, name: string) {
  const Context = createContext<Session<P, L> | null>(null)

  function SessionProvider({ api, children }: { api: SessionApi; children: ReactNode }) {
    const queryClient = useQueryClient()
    const [state, setState] = useState<SessionState<P>>({ status: 'loading' })

    const clear = useCallback(() => {
      api.tokens.set(null)
      queryClient.clear()
      setState({ status: 'anonymous' })
    }, [api, queryClient])

    const reloadProfile = useCallback(async () => {
      const profile = await api.client.get<P>(paths.profile)
      setState({ status: 'authenticated', profile })
    }, [api])

    useEffect(() => api.onAuthFailure(clear), [api, clear])

    useEffect(() => {
      let cancelled = false
      void api.restore().then(async (restored) => {
        if (cancelled) return
        if (!restored) {
          setState({ status: 'anonymous' })
          return
        }
        try {
          const profile = await api.client.get<P>(paths.profile)
          if (!cancelled) setState({ status: 'authenticated', profile })
        } catch {
          if (!cancelled) setState({ status: 'anonymous' })
        }
      })
      return () => {
        cancelled = true
      }
    }, [api])

    const login = useCallback(
      async (input: L) => {
        const response = await api.client.post<TokenResponse>(paths.login, input, {
          skipAuthRefresh: true,
        })
        api.tokens.set(response.accessToken)
        queryClient.clear()
        await reloadProfile()
      },
      [api, queryClient, reloadProfile],
    )

    const logout = useCallback(async () => {
      try {
        await api.client.post(paths.logout, undefined, { skipAuthRefresh: true })
      } catch {
        // The cookie may already be gone; signing out locally is what matters.
      } finally {
        clear()
      }
    }, [api, clear])

    const value = useMemo<Session<P, L>>(
      () => ({ state, login, logout, reloadProfile, clear }),
      [state, login, logout, reloadProfile, clear],
    )
    return <Context.Provider value={value}>{children}</Context.Provider>
  }

  function useSession(): Session<P, L> {
    const session = useContext(Context)
    if (!session) throw new Error(`${name} session used outside its provider`)
    return session
  }

  return { SessionProvider, useSession }
}
