import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anOpportunity, defaultStages, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { OpportunityView } from '@/lib/api/types'

function setup(
  o: OpportunityView = anOpportunity(),
  permissions: string[] = [...ALL_TENANT_PERMISSIONS],
) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /opportunities/:id', { body: o })
    .on('GET /crm/pipeline/stages', { body: defaultStages() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: `/app/crm/opportunities/${o.id}` })
}

describe('OpportunityDetailPage', () => {
  it('shows the deal, its account and its record panels', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Packaging renewal' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Acme' })).toHaveAttribute(
      'href',
      '/app/crm/customers/p-acme',
    )
    expect(screen.getByRole('link', { name: 'Grace Hopper' })).toHaveAttribute(
      'href',
      '/app/directory/p-grace',
    )
    expect(screen.getByText(/\$1,200\.00/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Activity' })).toBeInTheDocument()
    expect(server.callsTo('GET /activities')[0].query.get('subjectType')).toBe('OPPORTUNITY')
  })

  it('shows why a deal was lost and links its source lead', async () => {
    setup(
      anOpportunity({
        status: 'LOST',
        stage: { id: 's-lost', name: 'Lost', kind: 'LOST', probability: 0 },
        lostReason: 'Price',
        closedAt: '2026-10-06T00:00:00Z',
        leadId: 'l-grace',
      }),
    )
    expect(await screen.findByText('Price')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Source lead' })).toHaveAttribute(
      'href',
      '/app/crm/leads/l-grace',
    )
  })

  it('links the account to the directory without customer access', async () => {
    setup(anOpportunity(), ['crm.opportunity.read', 'directory.party.read'])
    expect(await screen.findByRole('link', { name: 'Acme' })).toHaveAttribute(
      'href',
      '/app/directory/p-acme',
    )
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
