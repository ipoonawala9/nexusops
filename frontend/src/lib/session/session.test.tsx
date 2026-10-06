import { act, screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RouteObject } from 'react-router'
import { useTenantSession } from '@/features/auth/tenantSession'
import { TenantRoot } from '@/app/TenantRoot'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { useApi } from '@/lib/api/ApiContext'

function Probe() {
  const session = useTenantSession()
  const api = useApi()
  return (
    <div>
      <p>status: {session.state.status}</p>
      {session.state.status === 'authenticated' && <p>user: {session.state.profile.user.email}</p>}
      <button
        onClick={() =>
          void session.login({ workspace: 'acme', email: 'ada@acme.test', password: 'pw' })
        }
      >
        login
      </button>
      <button onClick={() => void session.logout()}>logout</button>
      <button onClick={() => void api.get('/users').catch(() => undefined)}>call</button>
    </div>
  )
}

const routes: RouteObject[] = [
  { element: <TenantRoot />, children: [{ path: '/', element: <Probe /> }] },
]

describe('tenant session', () => {
  it('restores from the refresh cookie at boot', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/', routes })
    expect(await screen.findByText('user: ada@acme.test')).toBeInTheDocument()
  })

  it('is anonymous when the refresh cookie is missing', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/', routes })
    expect(await screen.findByText('status: anonymous')).toBeInTheDocument()
  })

  it('signs in and out', async () => {
    const server = fakeServer()
    signedOut(server)
    server
      .on('POST /auth/login', { body: { accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900 } })
      .on('GET /me', { body: testProfile() })
      .on('POST /auth/logout', {})
    const { user } = renderApp({ server, path: '/', routes })
    await screen.findByText('status: anonymous')
    await user.click(screen.getByRole('button', { name: 'login' }))
    expect(await screen.findByText('user: ada@acme.test')).toBeInTheDocument()
    expect(server.callsTo('POST /auth/login')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
      password: 'pw',
    })
    await user.click(screen.getByRole('button', { name: 'logout' }))
    expect(await screen.findByText('status: anonymous')).toBeInTheDocument()
    expect(server.callsTo('POST /auth/logout')).toHaveLength(1)
  })

  it('signs out on an unrecoverable 401', async () => {
    const server = fakeServer()
    signedIn(server)
    server.on('GET /users', { status: 401, body: {} })
    const { user } = renderApp({ server, path: '/', routes })
    await screen.findByText('user: ada@acme.test')
    server.on('POST /auth/refresh', { status: 401, body: {} })
    await user.click(screen.getByRole('button', { name: 'call' }))
    await waitFor(() => expect(screen.getByText('status: anonymous')).toBeInTheDocument())
  })

  it('keeps the session when TenantRoot remounts after navigating away and back', async () => {
    const server = fakeServer()
    signedOut(server)
    server
      .on('POST /auth/login', { body: { accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900 } })
      .on('GET /me', { body: testProfile() })
    const routesWithSibling: RouteObject[] = [
      ...routes,
      { path: '/elsewhere', element: <p>elsewhere</p> },
    ]
    const { user, router } = renderApp({ server, path: '/', routes: routesWithSibling })
    await screen.findByText('status: anonymous')
    await user.click(screen.getByRole('button', { name: 'login' }))
    await screen.findByText('user: ada@acme.test')
    // The boot restore found no cookie: one refresh plus its single cross-tab-race retry.
    expect(server.callsTo('POST /auth/refresh')).toHaveLength(2)

    await act(() => router.navigate('/elsewhere'))
    expect(await screen.findByText('elsewhere')).toBeInTheDocument()
    await act(() => router.navigate('/'))

    expect(await screen.findByText('user: ada@acme.test')).toBeInTheDocument()
    // No further refresh on remount.
    expect(server.callsTo('POST /auth/refresh')).toHaveLength(2)
  })
})
