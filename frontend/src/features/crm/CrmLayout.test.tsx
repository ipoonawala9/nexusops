import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('CrmLayout', () => {
  it('explains that CRM is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/crm' })
    expect(await screen.findByText('CRM is not enabled for this workspace.')).toBeInTheDocument()
  })

  it('shows the sections the user may open', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'], permissions: ['crm.lead.read'] })).on(
      'GET /crm/dashboard',
      { body: aDashboard({ pipeline: null }) },
    )
    renderApp({ server, path: '/app/crm' })
    const nav = await screen.findByRole('navigation', { name: 'CRM' })
    expect(nav).toHaveTextContent('Dashboard')
    expect(nav).toHaveTextContent('Leads')
    expect(nav).not.toHaveTextContent('Pipeline')
    expect(nav).not.toHaveTextContent('Customers')
  })

  it('shows every section to a full user', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] }),
    ).on('GET /crm/dashboard', { body: aDashboard() })
    renderApp({ server, path: '/app/crm' })
    const nav = await screen.findByRole('navigation', { name: 'CRM' })
    for (const name of ['Dashboard', 'Leads', 'Pipeline', 'Customers'])
      expect(nav).toHaveTextContent(name)
  })
})
