import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCustomerRow, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('CustomersPage', () => {
  it('lists customers with their open and won values per currency', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] }),
    ).on('GET /crm/customers', { body: pageOf([aCustomerRow()]) })
    const { user } = renderApp({ server, path: '/app/crm/customers' })
    const link = await screen.findByRole('link', { name: 'Acme' })
    expect(link).toHaveAttribute('href', '/app/crm/customers/p-acme')
    const row = link.closest('tr') as HTMLElement
    expect(within(row).getByText(/€50\.00 · \$300\.00/)).toBeInTheDocument()
    await user.type(screen.getByLabelText('Search customers'), 'acme')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(server.callsTo('GET /crm/customers').at(-1)?.query.get('q')).toBe('acme')
  })
})
