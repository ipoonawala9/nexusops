import { createContext, useContext, type ReactNode } from 'react'
import { createSessionApi, type SessionApi } from '@/lib/api/session'

export interface ApiHandles {
  tenant: SessionApi
  platform: SessionApi
}

const HandlesContext = createContext<ApiHandles | null>(null)

export function createDefaultHandles(): ApiHandles {
  return {
    tenant: createSessionApi({ refreshPath: '/auth/refresh' }),
    platform: createSessionApi({ refreshPath: '/platform/auth/refresh' }),
  }
}

export function ApiHandlesProvider({
  handles,
  children,
}: {
  handles: ApiHandles
  children: ReactNode
}) {
  return <HandlesContext.Provider value={handles}>{children}</HandlesContext.Provider>
}

export function useApiHandles(): ApiHandles {
  const handles = useContext(HandlesContext)
  if (!handles) throw new Error('useApiHandles() used outside ApiHandlesProvider')
  return handles
}
