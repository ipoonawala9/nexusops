import type { PlatformMe } from '@/lib/api/types'
import { createSession } from '@/lib/session/createSession'

export interface PlatformLoginInput {
  email: string
  password: string
  code: string
}

export const PLATFORM_SUSPEND = 'platform.tenant.suspend'

export const { SessionProvider: PlatformSessionProvider, useSession: usePlatformSession } =
  createSession<PlatformMe, PlatformLoginInput>(
    { login: '/platform/auth/login', logout: '/platform/auth/logout', profile: '/platform/me' },
    'Platform',
  )
