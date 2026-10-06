import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router'
import { TenantRoot } from '@/app/TenantRoot'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { useApi } from '@/lib/api/ApiContext'
import { RedirectIfTenantSignedIn, RequireTenantSession, safeNext } from './guards'
import { PERMISSIONS, RequirePermission } from './permissions'

function UsersProbe() {
  const api = useApi()
  return (
    <>
      <h1>Users page</h1>
      <button onClick={() => void api.get('/users').catch(() => undefined)}>call</button>
    </>
  )
}

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
                <UsersProbe />
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

  it('sends a session that expired mid-use to sign-in with a next parameter', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /users', { status: 401, body: {} })
    const { user, router } = renderApp({ server, path: '/app/settings/users?q=ada', routes })
    await screen.findByRole('heading', { name: 'Users page' })
    server.on('POST /auth/refresh', { status: 401, body: {} })
    await user.click(screen.getByRole('button', { name: 'call' }))
    expect(await screen.findByRole('heading', { name: 'Sign in page' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(
      `?next=${encodeURIComponent('/app/settings/users?q=ada')}`,
    )
  })

  it('forgets the restored token when the profile cannot be loaded', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /me', { status: 500, body: { detail: 'boom' } })
    const { handles } = renderApp({ server, path: '/app', routes })
    expect(await screen.findByRole('heading', { name: 'Sign in page' })).toBeInTheDocument()
    await waitFor(() => expect(handles.tenant.tokens.get()).toBeNull())
  })

  it('only honours local next targets under the given prefix', () => {
    expect(safeNext('/app/audit', '/app')).toBe('/app/audit')
    expect(safeNext('/app', '/app')).toBe('/app')
    expect(safeNext('/app?x=1', '/app')).toBe('/app?x=1')
    expect(safeNext('https://evil.example/app', '/app')).toBe('/app')
    expect(safeNext('//evil.example/app', '/app')).toBe('/app')
    expect(safeNext('/platform/tenants', '/app')).toBe('/app')
    expect(safeNext(null, '/platform', '/platform/tenants')).toBe('/platform/tenants')
  })

  it('requires a path boundary after the prefix and rejects dot-dot segments', () => {
    expect(safeNext('/appx', '/app')).toBe('/app')
    expect(safeNext('/application/evil', '/app')).toBe('/app')
    expect(safeNext('/app/../platform/tenants', '/app')).toBe('/app')
    expect(safeNext('/app/%2e%2e/platform/tenants', '/app')).toBe('/app')
    expect(safeNext('/app/settings/..', '/app')).toBe('/app')
  })

  it('accepts any platform page and falls back to the tenants list', () => {
    expect(safeNext('/platform/tenants?q=acme', '/platform', '/platform/tenants')).toBe(
      '/platform/tenants?q=acme',
    )
    expect(safeNext('/platform/other', '/platform', '/platform/tenants')).toBe('/platform/other')
    expect(safeNext('/app/audit', '/platform', '/platform/tenants')).toBe('/platform/tenants')
    expect(safeNext('/platformx', '/platform', '/platform/tenants')).toBe('/platform/tenants')
  })
})
