import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCustomerSummary, anActivity, anOpportunity, aSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] }))
    .on('GET /crm/customers/:id', { body: aCustomerSummary() })
    .on('GET /parties', {
      body: pageOf([aSummary({ id: 'p-grace', kind: 'PERSON', name: 'Grace Hopper' })]),
    })
    .on('GET /opportunities', { body: pageOf([anOpportunity()]) })
    .on('GET /leads', { body: pageOf([]) })
    .on('GET /activities', {
      body: pageOf([
        anActivity({
          summary: 'Pricing call',
          subjectType: 'OPPORTUNITY',
          subjectId: 'o-renewal',
          subject: {
            type: 'OPPORTUNITY',
            id: 'o-renewal',
            label: 'Packaging renewal',
            archived: false,
          },
        }),
        anActivity({
          id: 'a-2',
          summary: 'Kick-off',
          subject: { type: 'PARTY', id: 'p-acme', label: 'Acme', archived: false },
        }),
      ]),
    })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: '/app/crm/customers/p-acme' })
}

describe('Customer360Page', () => {
  it('joins the customer, its people, deals and the whole timeline', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Acme' })).toBeInTheDocument()
    expect(screen.getByText('Open deals').closest('div')).toHaveTextContent('1')
    expect(await screen.findByRole('link', { name: 'Grace Hopper' })).toHaveAttribute(
      'href',
      '/app/directory/p-grace',
    )
    const deals = screen.getByRole('region', { name: 'Opportunities' })
    expect(
      await within(deals).findByRole('link', { name: 'Packaging renewal' }),
    ).toBeInTheDocument()
    const activity = screen.getByRole('region', { name: 'Activity' })
    expect(await within(activity).findByText('Pricing call')).toBeInTheDocument()
    expect(within(activity).getByRole('link', { name: 'Packaging renewal' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(server.callsTo('GET /activities')[0].query.get('includeRelated')).toBe('true')
    expect(server.callsTo('GET /opportunities')[0].query.get('accountId')).toBe('p-acme')
    expect(server.callsTo('GET /parties')[0].query.get('organizationId')).toBe('p-acme')
  })
})
