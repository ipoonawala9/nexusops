import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(body = aDashboard()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] })).on(
    'GET /crm/dashboard',
    { body },
  )
  return renderApp({ server, path: '/app/crm' })
}

describe('CrmDashboardPage', () => {
  it('shows lead and pipeline figures', async () => {
    setup()
    const leads = await screen.findByRole('region', { name: 'Leads' })
    expect(within(leads).getByText('New').nextElementSibling).toHaveTextContent('3')
    expect(within(leads).getByText('25%')).toBeInTheDocument()
    const pipeline = screen.getByRole('region', { name: 'Pipeline' })
    expect(within(pipeline).getByRole('row', { name: /Prospecting/ })).toHaveTextContent(
      '$1,200.00',
    )
    expect(screen.getByRole('region', { name: 'Won this month' })).toHaveTextContent('$300.00')
    expect(screen.getByRole('link', { name: 'Packaging renewal' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
  })

  it('filters to my deals and hides sections the user cannot read', async () => {
    const { server, user } = setup(aDashboard({ pipeline: null }))
    await screen.findByRole('region', { name: 'Leads' })
    expect(screen.queryByRole('region', { name: 'Pipeline' })).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Show'), 'me')
    expect(server.callsTo('GET /crm/dashboard').at(-1)?.query.get('owner')).toBe('me')
  })
})
