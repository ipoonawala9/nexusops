import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import type { PlatformMe, Profile, UserView } from '@/lib/api/types'
import type { FakeServer } from './fakeServer'

export function user(overrides: Partial<UserView> = {}): UserView {
  return {
    id: 'u-ada',
    email: 'ada@acme.test',
    firstName: 'Ada',
    lastName: 'Lovelace',
    status: 'ACTIVE',
    emailVerified: true,
    roles: [{ id: 'r-owner', name: 'TENANT_OWNER' }],
    lastLoginAt: '2026-10-06T09:00:00Z',
    createdAt: '2026-10-01T09:00:00Z',
    ...overrides,
  }
}

export function testProfile(overrides: Partial<Profile> = {}): Profile {
  return {
    user: user(),
    tenant: { id: 't-acme', slug: 'acme', name: 'Acme Inc', status: 'ACTIVE', planCode: 'FREE' },
    permissions: [...ALL_TENANT_PERMISSIONS],
    modules: [],
    ...overrides,
  }
}

export function signedIn(server: FakeServer, profile: Profile = testProfile()): FakeServer {
  return server
    .on('POST /auth/refresh', {
      body: { accessToken: 'test-token', tokenType: 'Bearer', expiresIn: 900 },
    })
    .on('GET /me', { body: profile })
}

export function signedOut(server: FakeServer): FakeServer {
  return server.on('POST /auth/refresh', {
    status: 401,
    body: { title: 'Unauthorized', detail: 'Your session has expired. Please sign in again.' },
  })
}

export function platformMe(overrides: Partial<PlatformMe> = {}): PlatformMe {
  return {
    id: 'p-ops',
    email: 'ops@nexusops.test',
    role: 'PLATFORM_ADMIN',
    permissions: ['platform.tenant.read', 'platform.tenant.suspend'],
    ...overrides,
  }
}

export function platformSignedIn(server: FakeServer, me: PlatformMe = platformMe()): FakeServer {
  return server
    .on('POST /platform/auth/refresh', {
      body: { accessToken: 'p-token', tokenType: 'Bearer', expiresIn: 900 },
    })
    .on('GET /platform/me', { body: me })
}

export function platformSignedOut(server: FakeServer): FakeServer {
  return server.on('POST /platform/auth/refresh', {
    status: 401,
    body: { detail: 'Your session has expired. Please sign in again.' },
  })
}
