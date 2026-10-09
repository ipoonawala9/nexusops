import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aCategory,
  aCustomerSummary,
  anAgent,
  aParty,
  aProduct,
  aTicket,
  aTicketContext,
  aTicketSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function partyPage(modules: string[], ticketsReply?: { status: number; body: unknown }) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /parties/:id', { body: aParty() })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on(
      'GET /helpdesk/tickets',
      ticketsReply ?? {
        body: pageOf([
          aTicketSummary({ status: 'RESOLVED', requester: { id: 'p-acme', name: 'Acme' } }),
        ]),
      },
    )
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /products', { body: pageOf([aProduct()]) })
    // the ticket page opens after a create
    .on('GET /helpdesk/tickets/:id', { body: aTicket({ id: 't-new' }) })
    .on('GET /helpdesk/tickets/:id/messages', { body: [] })
    .on('GET /helpdesk/tickets/:id/context', { body: aTicketContext() })
  return renderApp({ server, path: '/app/directory/p-acme' })
}

describe('TicketsPanel', () => {
  it("lists a party's tickets in every status", async () => {
    const { server } = partyPage(['HELPDESK'])
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByRole('link', { name: 'T-00001' })).toHaveAttribute(
      'href',
      '/app/helpdesk/tickets/t-1',
    )
    expect(within(panel).getByText('Resolved')).toBeInTheDocument()
    const query = server.callsTo('GET /helpdesk/tickets')[0].query
    expect(query.get('requesterId')).toBe('p-acme')
    expect(query.get('status')).toBe('NEW,OPEN,PENDING,RESOLVED,CLOSED')
  })

  it('opens a new ticket for the party', async () => {
    const { server, user } = partyPage(['HELPDESK'])
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    await user.click(within(panel).getByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Requester')).toHaveValue('p-acme')
    await user.type(within(dialog).getByLabelText('Subject'), 'Late delivery')
    await user.type(within(dialog).getByLabelText('Description'), 'Order did not arrive.')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets')[0]?.body).toMatchObject({
        requesterId: 'p-acme',
        subject: 'Late delivery',
      }),
    )
  })

  it('says so when the tickets cannot be loaded', async () => {
    partyPage(['HELPDESK'], { status: 500, body: { title: 'Server error' } })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByText("Couldn't load tickets.")).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(within(panel).queryByText('No tickets yet.')).not.toBeInTheDocument()
  })

  it('is absent without HelpDesk', async () => {
    partyPage([])
    await screen.findByRole('heading', { name: 'Acme' })
    expect(screen.queryByRole('region', { name: 'Tickets' })).not.toBeInTheDocument()
  })

  it("lists a product's tickets", async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HELPDESK'] }))
      .on('GET /products/:id', { body: aProduct() })
      .on('GET /activities', { body: pageOf([]) })
      .on('GET /documents', { body: [] })
      .on('GET /helpdesk/tickets', { body: pageOf([aTicketSummary()]) })
    renderApp({ server, path: '/app/products/pr-widget' })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByRole('link', { name: 'T-00001' })).toBeInTheDocument()
    expect(within(panel).queryByRole('button', { name: 'New ticket' })).not.toBeInTheDocument()
    expect(server.callsTo('GET /helpdesk/tickets')[0].query.get('productId')).toBe('pr-widget')
  })

  it('appears on the Customer 360 page', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['CRM', 'HELPDESK'], permissions: [...ALL_TENANT_PERMISSIONS] }),
    )
      .on('GET /crm/customers/:id', { body: aCustomerSummary() })
      .on('GET /parties', { body: pageOf([]) })
      .on('GET /opportunities', { body: pageOf([]) })
      .on('GET /leads', { body: pageOf([]) })
      .on('GET /activities', { body: pageOf([]) })
      .on('GET /documents', { body: [] })
      .on('GET /helpdesk/tickets', { body: pageOf([aTicketSummary()]) })
    renderApp({ server, path: '/app/crm/customers/p-acme' })
    const panel = await screen.findByRole('region', { name: 'Tickets' })
    expect(await within(panel).findByRole('link', { name: 'T-00001' })).toBeInTheDocument()
  })
})
