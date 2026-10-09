import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  anAgent,
  anArticle,
  anArticleSummary,
  aMessage,
  anSla,
  aPerson,
  aTicket,
  aTicketContext,
  aTicketSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { TicketView } from '@/lib/api/types'

function setup(ticket: TicketView = aTicket(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/tickets/:id', { body: ticket })
    .on('GET /helpdesk/tickets/:id/messages', {
      body: [
        aMessage({ id: 'm-1', body: 'Please clear the paper tray.' }),
        aMessage({
          id: 'm-2',
          kind: 'INTERNAL_NOTE',
          body: 'Same model failed last month.',
          emailedTo: null,
        }),
      ],
    })
    .on('GET /helpdesk/tickets/:id/context', {
      body: aTicketContext({
        previousTickets: [
          aTicketSummary({
            id: 't-0',
            number: 'T-00000',
            subject: 'Toner smudges',
            status: 'CLOSED',
          }),
        ],
        possibleDuplicates: [
          aTicketSummary({ id: 't-7', number: 'T-00007', subject: 'Printer jam again' }),
        ],
        suggestedArticles: [anArticleSummary()],
      }),
    })
    .on('GET /parties/:id', {
      body: aPerson({
        id: 'p-meera',
        name: 'Meera Iyer',
        email: 'meera@deccan.test',
        phone: '+91 98',
      }),
    })
    .on('GET /helpdesk/articles/:id', { body: anArticle() })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: `/app/helpdesk/tickets/${ticket.id}` })
}

describe('TicketDetailPage', () => {
  it('shows the ticket, its SLA and the conversation', async () => {
    setup()
    expect(
      await screen.findByRole('heading', { name: 'T-00001 · Printer jams on every page' }),
    ).toBeInTheDocument()
    const sla = screen.getByRole('region', { name: 'SLA' })
    expect(within(sla).getByText('First response: On track')).toBeInTheDocument()
    expect(within(sla).getByText('Resolution: On track')).toBeInTheDocument()
    const conversation = screen.getByRole('region', { name: 'Conversation' })
    expect(
      await within(conversation).findByText('Please clear the paper tray.'),
    ).toBeInTheDocument()
    expect(within(conversation).getByText('Emailed to meera@deccan.test')).toBeInTheDocument()
    // the composer's type select also offers "Internal note": look in the message list only
    expect(
      within(within(conversation).getByRole('list')).getByText('Internal note'),
    ).toBeInTheDocument()
    const details = screen.getByRole('region', { name: 'Details' })
    expect(within(details).getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute(
      'href',
      '/app/directory/p-meera',
    )
    expect(within(details).getByRole('link', { name: 'W-1 · Widget' })).toHaveAttribute(
      'href',
      '/app/products/pr-widget',
    )
  })

  it('shows a linked record the viewer may open, and a restricted one without a link', async () => {
    setup(aTicket({ linked: { type: 'SALES_ORDER', id: 'so-1', label: 'SO-00001' } }))
    const details = await screen.findByRole('region', { name: 'Details' })
    expect(within(details).getByRole('link', { name: 'SO-00001' })).toHaveAttribute(
      'href',
      '/app/inventory/sales-orders/so-1',
    )
  })

  it('sends a public reply and shows the ticket as the server returns it', async () => {
    const { server, user } = setup()
    const box = await screen.findByLabelText('Message')
    const answered = aTicket({
      status: 'OPEN',
      sla: anSla({ firstRespondedAt: '2026-10-09T09:40:00Z', firstResponseState: 'MET' }),
      version: 1,
    })
    server
      .on('POST /helpdesk/tickets/:id/messages', {
        status: 201,
        body: {
          message: aMessage({ id: 'm-3', body: 'We are sending a technician.' }),
          ticket: answered,
        },
      })
      .on('GET /helpdesk/tickets/:id', { body: answered })
    await user.type(box, 'We are sending a technician.')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/messages')).toHaveLength(1),
    )
    expect(server.callsTo('POST /helpdesk/tickets/:id/messages')[0].body).toEqual({
      kind: 'PUBLIC_REPLY',
      body: 'We are sending a technician.',
    })
    expect(await screen.findByText('Reply emailed to meera@deccan.test.')).toBeInTheDocument()
    expect(await screen.findByText('First response: Met')).toBeInTheDocument()
    expect(screen.getByLabelText('Message')).toHaveValue('')
  })

  it('says so when a reply could not be emailed', async () => {
    const { server, user } = setup()
    const box = await screen.findByLabelText('Message')
    server.on('POST /helpdesk/tickets/:id/messages', {
      status: 201,
      body: {
        message: aMessage({ id: 'm-3', emailedTo: null }),
        ticket: aTicket({ status: 'OPEN' }),
      },
    })
    await user.type(box, 'Hello')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    expect(
      await screen.findByText(
        'Reply saved. No email was sent: the requester has no email address.',
      ),
    ).toBeInTheDocument()
  })

  it('adds an internal note and logs a customer message', async () => {
    const { server, user } = setup()
    const box = await screen.findByLabelText('Message')
    server.on('POST /helpdesk/tickets/:id/messages', {
      status: 201,
      body: { message: aMessage({ id: 'm-3', kind: 'INTERNAL_NOTE' }), ticket: aTicket() },
    })
    await user.selectOptions(screen.getByLabelText('Message type'), 'INTERNAL_NOTE')
    await user.type(box, 'Check the warranty first.')
    await user.click(screen.getByRole('button', { name: 'Add note' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/messages').at(-1)?.body).toEqual({
        kind: 'INTERNAL_NOTE',
        body: 'Check the warranty first.',
      }),
    )
    await user.selectOptions(screen.getByLabelText('Message type'), 'CUSTOMER_MESSAGE')
    expect(screen.getByRole('button', { name: 'Log customer message' })).toBeInTheDocument()
  })

  it('refuses an empty message before calling the server', async () => {
    const { server, user } = setup()
    await screen.findByLabelText('Message')
    await user.click(screen.getByRole('button', { name: 'Send reply' }))
    expect(await screen.findByText('Write a message.')).toBeInTheDocument()
    expect(server.callsTo('POST /helpdesk/tickets/:id/messages')).toHaveLength(0)
  })

  it('assigns the ticket to a teammate', async () => {
    const { server, user } = setup()
    const assigned = aTicket({
      status: 'OPEN',
      assignee: { id: 'u-ravi', name: 'Ravi Kumar' },
      version: 1,
    })
    const assignButton = await screen.findByRole('button', { name: 'Assign…' })
    server
      .on('POST /helpdesk/tickets/:id/assign', { body: assigned })
      .on('GET /helpdesk/tickets/:id', { body: assigned })
    await user.click(assignButton)
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), 'u-ravi')
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }))
    await waitFor(() => expect(server.callsTo('POST /helpdesk/tickets/:id/assign')).toHaveLength(1))
    expect(server.callsTo('POST /helpdesk/tickets/:id/assign')[0].body).toEqual({
      assigneeId: 'u-ravi',
      version: 0,
    })
    const details = screen.getByRole('region', { name: 'Details' })
    expect(await within(details).findByText('Ravi Kumar')).toBeInTheDocument()
  })

  it('assigns the ticket to the signed-in user in one click', async () => {
    const { server, user } = setup()
    const mine = aTicket({ assignee: { id: 'u-ada', name: 'Ada Lovelace' }, version: 1 })
    const assignToMe = await screen.findByRole('button', { name: 'Assign to me' })
    server
      .on('POST /helpdesk/tickets/:id/assign', { body: mine })
      .on('GET /helpdesk/tickets/:id', { body: mine })
    await user.click(assignToMe)
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/assign')[0]?.body).toEqual({
        assigneeId: 'u-ada',
        version: 0,
      }),
    )
  })

  it('needs a resolution note to resolve', async () => {
    const { server, user } = setup(aTicket({ status: 'OPEN' }))
    const resolved = aTicket({
      status: 'RESOLVED',
      resolutionNote: 'Replaced the roller.',
      version: 1,
    })
    const resolveButton = await screen.findByRole('button', { name: 'Resolve…' })
    server
      .on('POST /helpdesk/tickets/:id/status', { body: resolved })
      .on('GET /helpdesk/tickets/:id', { body: resolved })
    await user.click(resolveButton)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    expect(await within(dialog).findByText('Add a resolution note.')).toBeInTheDocument()
    expect(server.callsTo('POST /helpdesk/tickets/:id/status')).toHaveLength(0)
    await user.type(within(dialog).getByLabelText('Resolution note'), 'Replaced the roller.')
    await user.click(within(dialog).getByRole('button', { name: 'Resolve' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/status')[0]?.body).toEqual({
        status: 'RESOLVED',
        note: 'Replaced the roller.',
        version: 0,
      }),
    )
    expect(await screen.findByText('Replaced the roller.')).toBeInTheDocument()
  })

  it('offers the moves each status allows', async () => {
    const { server, user } = setup(
      aTicket({
        status: 'PENDING',
        sla: anSla({
          pausedAt: '2026-10-09T11:00:00Z',
          resolutionState: 'PAUSED',
          firstRespondedAt: '2026-10-09T10:00:00Z',
        }),
      }),
    )
    expect(await screen.findByRole('button', { name: 'Resume' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Wait on customer' })).not.toBeInTheDocument()
    expect(screen.getByText(/Paused since/)).toBeInTheDocument()
    const resumed = aTicket({ status: 'OPEN', version: 1 })
    server
      .on('POST /helpdesk/tickets/:id/status', { body: resumed })
      .on('GET /helpdesk/tickets/:id', { body: resumed })
    await user.click(screen.getByRole('button', { name: 'Resume' }))
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/status')[0]?.body).toEqual({
        status: 'OPEN',
        note: null,
        version: 0,
      }),
    )
    expect(await screen.findByRole('button', { name: 'Wait on customer' })).toBeInTheDocument()
  })

  it('reopens or closes a resolved ticket', async () => {
    const { server, user } = setup(
      aTicket({
        status: 'RESOLVED',
        resolutionNote: 'Fixed.',
        sla: anSla({ resolvedAt: '2026-10-10T09:00:00Z', resolutionState: 'MET' }),
      }),
    )
    expect(await screen.findByRole('button', { name: 'Reopen' })).toBeInTheDocument()
    const closedTicket = aTicket({ status: 'CLOSED', closedAt: '2026-10-10T10:00:00Z', version: 1 })
    server
      .on('POST /helpdesk/tickets/:id/status', { body: closedTicket })
      .on('GET /helpdesk/tickets/:id', { body: closedTicket })
    await user.click(screen.getByRole('button', { name: 'Close' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Close ticket' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /helpdesk/tickets/:id/status')[0]?.body).toEqual({
        status: 'CLOSED',
        note: null,
        version: 0,
      }),
    )
    expect(
      await screen.findByText('This ticket is closed. Its history stays here.'),
    ).toBeInTheDocument()
  })

  it('shows a closed ticket without composer or actions', async () => {
    setup(aTicket({ status: 'CLOSED', closedAt: '2026-10-10T10:00:00Z' }))
    expect(
      await screen.findByText('This ticket is closed. Its history stays here.'),
    ).toBeInTheDocument()
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })

  it('explains a conflict and reloads the ticket', async () => {
    const { server, user } = setup(aTicket({ status: 'OPEN' }))
    server.on('POST /helpdesk/tickets/:id/status', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Wait on customer' }))
    expect(
      await screen.findByText('This record was changed by someone else. Reload and try again.'),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(server.callsTo('GET /helpdesk/tickets/:id').length).toBeGreaterThan(1),
    )
  })

  it('shows the requester, previous tickets, possible duplicates and suggested articles', async () => {
    setup()
    const context = await screen.findByRole('region', { name: 'Context' })
    expect(await within(context).findByText('meera@deccan.test')).toBeInTheDocument()
    expect(within(context).getByRole('link', { name: 'T-00000' })).toHaveAttribute(
      'href',
      '/app/helpdesk/tickets/t-0',
    )
    expect(within(context).getByRole('link', { name: 'T-00007' })).toBeInTheDocument()
    expect(within(context).getByRole('link', { name: 'Clearing a paper jam' })).toHaveAttribute(
      'href',
      '/app/helpdesk/articles/a-1',
    )
  })

  it('inserts a suggested article into the reply', async () => {
    const { user } = setup()
    const context = await screen.findByRole('region', { name: 'Context' })
    await user.type(await screen.findByLabelText('Message'), 'Hi Meera,')
    await user.click(
      await within(context).findByRole('button', {
        name: 'Insert Clearing a paper jam into reply',
      }),
    )
    await waitFor(() =>
      expect(screen.getByLabelText('Message')).toHaveValue(
        'Hi Meera,\n\nClearing a paper jam\n\nOpen the rear tray and pull the sheet out gently.',
      ),
    )
    expect(screen.getByLabelText('Message type')).toHaveValue('PUBLIC_REPLY')
  })

  it('shows readers the ticket without composer or actions', async () => {
    setup(aTicket(), ['helpdesk.ticket.read', 'helpdesk.article.read', 'directory.party.read'])
    await screen.findByRole('heading', { name: 'T-00001 · Printer jams on every page' })
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign…' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Resolve…' })).not.toBeInTheDocument()
  })

  it('starts a fresh reply when the user moves to another ticket', async () => {
    const { server, user } = setup()
    const other = aTicket({ id: 't-7', number: 'T-00007', subject: 'Printer jam again' })
    server.on('GET /helpdesk/tickets/:id', (request) => ({
      body: request.params.id === 't-7' ? other : aTicket(),
    }))
    await user.selectOptions(await screen.findByLabelText('Message type'), 'INTERNAL_NOTE')
    await user.type(screen.getByLabelText('Message'), 'Draft for the first ticket')
    const context = screen.getByRole('region', { name: 'Context' })
    await user.click(await within(context).findByRole('link', { name: 'T-00007' }))
    expect(
      await screen.findByRole('heading', { name: 'T-00007 · Printer jam again' }),
    ).toBeInTheDocument()
    expect(screen.getByLabelText('Message')).toHaveValue('')
    expect(screen.getByLabelText('Message type')).toHaveValue('PUBLIC_REPLY')
  })

  it('edits the ticket', async () => {
    const { server, user } = setup()
    const edit = await screen.findByRole('button', { name: 'Edit' })
    const edited = aTicket({ subject: 'Printer jams on every page, again', version: 1 })
    server
      .on('PUT /helpdesk/tickets/:id', { body: edited })
      .on('GET /helpdesk/tickets/:id', { body: edited })
    await user.click(edit)
    const dialog = await screen.findByRole('dialog')
    const subject = await within(dialog).findByLabelText('Subject')
    await user.clear(subject)
    await user.type(subject, 'Printer jams on every page, again')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(server.callsTo('PUT /helpdesk/tickets/:id')).toHaveLength(1))
    expect(server.callsTo('PUT /helpdesk/tickets/:id')[0].body).toMatchObject({
      subject: 'Printer jams on every page, again',
      version: 0,
    })
    expect(
      await screen.findByRole('heading', { name: 'T-00001 · Printer jams on every page, again' }),
    ).toBeInTheDocument()
  })
})
