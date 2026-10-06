import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function nav() {
  return within(screen.getByRole('navigation', { name: 'Workspace' }))
}

describe('AppLayout', () => {
  it('shows only enabled modules, phase features and permitted sections', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'], permissions: ['identity.user.read'] }))
    renderApp({ server, path: '/app' })
    expect(await screen.findByRole('heading', { name: 'Welcome, Ada' })).toBeInTheDocument()
    expect(nav().getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
    expect(nav().getByRole('link', { name: 'CRM' })).toBeInTheDocument()
    expect(nav().queryByRole('link', { name: 'Inventory' })).not.toBeInTheDocument()
    expect(nav().getByRole('link', { name: /Workflows/ })).toBeInTheDocument()
    expect(nav().queryByRole('link', { name: 'Audit' })).not.toBeInTheDocument()
    expect(nav().getByRole('link', { name: 'Settings' })).toHaveAttribute(
      'href',
      '/app/settings/users',
    )
    expect(screen.getAllByText('Acme Inc').length).toBeGreaterThan(0)
  })

  it('marks Settings active on every settings page', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /roles', { body: [] })
    renderApp({ server, path: '/app/settings/roles' })
    await screen.findByRole('navigation', { name: 'Settings' })
    const settings = nav().getByRole('link', { name: 'Settings' })
    expect(settings).toHaveAttribute('href', '/app/settings/workspace')
    expect(settings).toHaveAttribute('aria-current', 'page')
    expect(nav().getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current')
  })

  it('hides Settings entirely without any settings permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: [] }))
    renderApp({ server, path: '/app' })
    await screen.findByRole('heading', { name: 'Welcome, Ada' })
    expect(nav().queryByRole('link', { name: 'Settings' })).not.toBeInTheDocument()
  })

  it('signs out everywhere', async () => {
    const server = fakeServer()
    signedIn(server).on('POST /auth/logout-all', { status: 204 })
    const { user, router } = renderApp({ server, path: '/app' })
    await user.click(await screen.findByRole('button', { name: 'Sign out everywhere' }))
    expect(server.callsTo('POST /auth/logout-all')).toHaveLength(1)
    await screen.findByRole('heading', { name: 'Sign in' })
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toBe('')
  })

  it('signs out to a plain sign-in page (no next)', async () => {
    const server = fakeServer()
    signedIn(server).on('POST /auth/logout', { status: 204 })
    const { user, router } = renderApp({ server, path: '/app/settings/roles' })
    await user.click(await screen.findByRole('button', { name: 'Sign out' }))
    await screen.findByRole('heading', { name: 'Sign in' })
    expect(server.callsTo('POST /auth/logout')).toHaveLength(1)
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toBe('')
  })

  it('toggles the navigation on small screens', async () => {
    const server = fakeServer()
    signedIn(server)
    const { user } = renderApp({ server, path: '/app' })
    const toggle = await screen.findByRole('button', { name: 'Menu' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('opens the first settings tab the user may see', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['authorization.role.read'] }))
    const { router } = renderApp({ server, path: '/app/settings' })
    await screen.findByRole('navigation', { name: 'Settings' })
    expect(router.state.location.pathname).toBe('/app/settings/roles')
  })
})
