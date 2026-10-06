import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router'
import { TenantRoot } from '@/app/TenantRoot'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { RedirectIfTenantSignedIn, RequireTenantSession, safeNext } from './guards'
import { PERMISSIONS, RequirePermission } from './permissions'

const routes: RouteObject[] = [
  {
    element: <TenantRoot />,
    children: [
      {
        element: <RedirectIfTenantSignedIn />,
        children: [{ path: '/login', element: <h1>Sign in page</h1> }],
      },
      {
        element: <RequireTenantSession />,
        children: [
          { path: '/app', element: <h1>Overview page</h1> },
          {
            path: '/app/settings/users',
            element: (
              <RequirePermission anyOf={[PERMISSIONS.userRead]}>
                <h1>Users page</h1>
              </RequirePermission>
            ),
          },
        ],
      },
    ],
  },
]

describe('guards', () => {
  it('sends anonymous visitors to sign-in with a next parameter', async () => {
    const server = fakeServer()
    signedOut(server)
    const { router } = renderApp({ server, path: '/app/settings/users?status=ACTIVE', routes })
    expect(await screen.findByRole('heading', { name: 'Sign in page' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(
      `?next=${encodeURIComponent('/app/settings/users?status=ACTIVE')}`,
    )
  })

  it('restores a deep link without showing the sign-in page', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/app/settings/users', routes })
    expect(await screen.findByRole('heading', { name: 'Users page' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in page' })).not.toBeInTheDocument()
  })

  it('shows a no-access state for a missing permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: [] }))
    renderApp({ server, path: '/app/settings/users', routes })
    expect(
      await screen.findByRole('heading', { name: "You don't have access to this page" }),
    ).toBeInTheDocument()
  })

  it('sends signed-in users away from the sign-in page', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/login', routes })
    expect(await screen.findByRole('heading', { name: 'Overview page' })).toBeInTheDocument()
  })

  it('only honours local next targets under the given prefix', () => {
    expect(safeNext('/app/audit', '/app')).toBe('/app/audit')
    expect(safeNext('https://evil.example/app', '/app')).toBe('/app')
    expect(safeNext('//evil.example/app', '/app')).toBe('/app')
    expect(safeNext('/platform/tenants', '/app')).toBe('/app')
    expect(safeNext(null, '/platform/tenants')).toBe('/platform/tenants')
  })
})
