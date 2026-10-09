import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aHelpDeskDashboard, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('HelpDeskLayout', () => {
  it('explains that HelpDesk is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/helpdesk' })
    expect(
      await screen.findByText('HelpDesk is not enabled for this workspace.'),
    ).toBeInTheDocument()
  })

  it('shows the sections the user may open', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HELPDESK'] })).on('GET /helpdesk/dashboard', {
      body: aHelpDeskDashboard(),
    })
    renderApp({ server, path: '/app/helpdesk' })
    const nav = await screen.findByRole('navigation', { name: 'HelpDesk' })
    expect(nav).toHaveTextContent('Dashboard')
    expect(nav).toHaveTextContent('Tickets')
    expect(nav).toHaveTextContent('Knowledge base')
  })

  it('has no sections without any HelpDesk permission', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({
        modules: ['HELPDESK'],
        permissions: [...ALL_TENANT_PERMISSIONS].filter((p) => !p.startsWith('helpdesk.')),
      }),
    )
    renderApp({ server, path: '/app/helpdesk' })
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
  })

  it('opens the knowledge base for someone who may only read articles', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['HELPDESK'], permissions: ['helpdesk.article.read'] }),
    ).on('GET /helpdesk/articles', { body: pageOf([]) })
    const { router } = renderApp({ server, path: '/app/helpdesk' })
    expect(await screen.findByRole('heading', { name: 'Knowledge base' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/helpdesk/articles')
  })
})
