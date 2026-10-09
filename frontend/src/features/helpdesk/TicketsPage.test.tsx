import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aCategory,
  anAgent,
  anSla,
  aProduct,
  aSummary,
  aTicket,
  aTicketContext,
  aTicketSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/helpdesk/tickets', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/tickets', {
      body: pageOf([
        aTicketSummary({
          priority: 'URGENT',
          sla: anSla({ firstResponseState: 'BREACHED' }),
        }),
        aTicketSummary({
          id: 't-2',
          number: 'T-00002',
          subject: 'Invoice shows the wrong GST number',
          status: 'PENDING',
          assignee: { id: 'u-ravi', name: 'Ravi Kumar' },
          sla: anSla({ firstRespondedAt: '2026-10-09T10:00:00Z', resolutionState: 'PAUSED' }),
        }),
      ]),
    })
    // the ticket page (Task 9) opens after a create
    .on('GET /helpdesk/tickets/:id', { body: aTicket({ id: 't-new' }) })
    .on('GET /helpdesk/tickets/:id/messages', { body: [] })
    .on('GET /helpdesk/tickets/:id/context', { body: aTicketContext() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /helpdesk/categories', { body: [aCategory()] })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-meera', name: 'Meera Iyer' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /search', {
      body: [
        {
          type: 'SALES_ORDER',
          id: 'so-1',
          label: 'SO-00001',
          detail: 'Meera Iyer',
          archived: false,
        },
        { type: 'TICKET', id: 't-9', label: 'T-00009 · Old', detail: null, archived: false },
      ],
    })
  return renderApp({ server, path })
}

async function fillRequired(dialog: HTMLElement, user: ReturnType<typeof setup>['user']) {
  await user.type(within(dialog).getByLabelText('Subject'), 'Printer jams')
  await user.type(within(dialog).getByLabelText('Description'), 'Jams on every page.')
  await user.selectOptions(within(dialog).getByLabelText('Requester'), 'p-meera')
}

describe('TicketsPage', () => {
  it('lists open tickets with requester, priority, status, assignee and the SLA that matters now', async () => {
    setup()
    const first = (await screen.findByRole('link', { name: 'T-00001' })).closest(
      'tr',
    ) as HTMLElement
    expect(first).toHaveTextContent('Printer jams on every page')
    expect(first).toHaveTextContent('Meera Iyer')
    expect(within(first).getByText('Urgent')).toBeInTheDocument()
    expect(within(first).getByText('First response: Breached')).toBeInTheDocument()
    expect(within(first).getByText('Unassigned')).toBeInTheDocument()
    const second = screen.getByRole('link', { name: 'T-00002' }).closest('tr') as HTMLElement
    expect(within(second).getByText('Waiting on customer')).toBeInTheDocument()
    expect(within(second).getByText('Resolution: Paused')).toBeInTheDocument()
    expect(within(second).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'T-00001' })).toHaveAttribute(
      'href',
      '/app/helpdesk/tickets/t-1',
    )
  })

  it('asks for open tickets by default and passes every filter to the server', async () => {
    const { server, user } = setup('/app/helpdesk/tickets?sla=breached&assignee=unassigned')
    await screen.findByRole('link', { name: 'T-00001' })
    const first = server.callsTo('GET /helpdesk/tickets')[0].query
    expect(first.get('status')).toBeNull()
    expect(first.get('sla')).toBe('breached')
    expect(first.get('assignee')).toBe('unassigned')
    await user.selectOptions(screen.getByLabelText('Show'), 'all')
    await waitFor(() =>
      expect(server.callsTo('GET /helpdesk/tickets').at(-1)?.query.get('status')).toBe(
        'NEW,OPEN,PENDING,RESOLVED,CLOSED',
      ),
    )
    await user.selectOptions(screen.getByLabelText('Priority'), 'URGENT')
    await user.selectOptions(screen.getByLabelText('Assignee'), 'me')
    await user.selectOptions(screen.getByLabelText('Category'), 'c-general')
    await user.type(screen.getByLabelText('Search tickets'), 'T-00002')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const last = server.callsTo('GET /helpdesk/tickets').at(-1)?.query
      expect(last?.get('priority')).toBe('URGENT')
      expect(last?.get('assignee')).toBe('me')
      expect(last?.get('categoryId')).toBe('c-general')
      expect(last?.get('q')).toBe('T-00002')
    })
  })

  it('creates a ticket for a requester with a product and a related order, then opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await fillRequired(dialog, user)
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Find a related record'), 'SO-0')
    await within(dialog).findByRole('option', { name: 'Sales order · SO-00001' })
    expect(within(dialog).queryByRole('option', { name: /T-00009/ })).not.toBeInTheDocument()
    await user.selectOptions(within(dialog).getByLabelText('Related record'), 'SALES_ORDER:so-1')
    await user.selectOptions(within(dialog).getByLabelText('Category'), 'c-general')
    await user.selectOptions(within(dialog).getByLabelText('Priority'), 'HIGH')
    await user.selectOptions(within(dialog).getByLabelText('Channel'), 'EMAIL')
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), 'u-ravi')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets')[0].body).toEqual({
      subject: 'Printer jams',
      description: 'Jams on every page.',
      requesterId: 'p-meera',
      productId: 'pr-widget',
      linkedType: 'SALES_ORDER',
      linkedId: 'so-1',
      categoryId: 'c-general',
      priority: 'HIGH',
      channel: 'EMAIL',
      assigneeId: 'u-ravi',
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/helpdesk/tickets/t-new'))
  })

  it('keeps the chosen product selected when the product search changes', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    server.on('GET /products', { body: pageOf([]) })
    await user.type(within(dialog).getByLabelText('Find product'), 'zzz')
    await waitFor(() => expect(server.callsTo('GET /products').at(-1)?.query.get('q')).toBe('zzz'))
    const select = within(dialog).getByLabelText('Product')
    expect(select).toHaveValue('pr-widget')
    expect(within(select).getByRole('option', { name: /Widget/ })).toBeInTheDocument()
  })

  it('leaves optional fields empty as nulls and defaults to normal priority by phone', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await fillRequired(dialog, user)
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets')[0].body).toMatchObject({
      productId: null,
      linkedType: null,
      linkedId: null,
      categoryId: null,
      priority: 'NORMAL',
      channel: 'PHONE',
      assigneeId: null,
    })
  })

  it('needs a subject, a description and a requester before calling the server', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    expect(await within(dialog).findAllByText('Required.')).toHaveLength(2)
    expect(within(dialog).getByText('Choose who the ticket is for.')).toBeInTheDocument()
    expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(0)
  })

  it('shows a refused related record next to the related-record field', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/tickets', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'linkedId', message: 'Choose a record in this workspace.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    await fillRequired(dialog, user)
    await user.type(within(dialog).getByLabelText('Find a related record'), 'SO-0')
    await within(dialog).findByRole('option', { name: 'Sales order · SO-00001' })
    await user.selectOptions(within(dialog).getByLabelText('Related record'), 'SALES_ORDER:so-1')
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    expect(
      await within(dialog).findByText('Choose a record in this workspace.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Related record')).toHaveAttribute('aria-invalid', 'true')
  })

  it('offers no assignee to someone who may not assign, and lets the category route', async () => {
    const { server, user } = setup('/app/helpdesk/tickets', [
      'helpdesk.ticket.read',
      'helpdesk.ticket.manage',
      'directory.party.read',
    ])
    server.on('POST /helpdesk/tickets', { status: 201, body: aTicket({ id: 't-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New ticket' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText('Assignee')).not.toBeInTheDocument()
    await fillRequired(dialog, user)
    await user.click(within(dialog).getByRole('button', { name: 'Create ticket' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets')[0].body).toMatchObject({ assigneeId: null })
  })

  it('hides New ticket from readers', async () => {
    setup('/app/helpdesk/tickets', ['helpdesk.ticket.read'])
    await screen.findByRole('link', { name: 'T-00001' })
    expect(screen.queryByRole('button', { name: 'New ticket' })).not.toBeInTheDocument()
  })

  it('says what to do when nothing matches', async () => {
    const { server } = setup('/app/helpdesk/tickets?sla=breached')
    server.on('GET /helpdesk/tickets', { body: pageOf([]) })
    expect(await screen.findByText('No tickets match these filters.')).toBeInTheDocument()
  })
})
