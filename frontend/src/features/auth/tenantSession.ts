import type { Profile } from '@/lib/api/types'
import { createSession } from '@/lib/session/createSession'

export interface LoginInput {
  workspace: string
  email: string
  password: string
}

export const { SessionProvider: TenantSessionProvider, useSession: useTenantSession } =
  createSession<Profile, LoginInput>(
    { login: '/auth/login', logout: '/auth/logout', profile: '/me' },
    'Tenant',
  )
