import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aBoard, anOpportunity, aSummary, defaultStages, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /crm/pipeline/board', { body: aBoard() })
    .on('GET /crm/pipeline/stages', { body: defaultStages() })
    .on('GET /crm/owners', { body: [] })
    .on('GET /parties', { body: pageOf([aSummary()]) })
    .on('GET /opportunities/:id', { body: anOpportunity() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: '/app/crm/pipeline' })
}

describe('PipelinePage', () => {
  it('shows a column per stage with counts and per-currency totals', async () => {
    setup()
    const column = await screen.findByRole('region', { name: 'Prospecting' })
    expect(within(column).getByText('1 deal')).toBeInTheDocument()
    // the column total and the card both show the amount
    expect(within(column).getAllByText(/\$1,200\.00/)).toHaveLength(2)
    expect(within(column).getByRole('link', { name: 'Packaging renewal' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(screen.getByRole('region', { name: 'Won' })).toBeInTheDocument()
    expect(
      within(screen.getByRole('region', { name: 'Won' })).getByText('Closed in the last 30 days'),
    ).toBeInTheDocument()
  })

  it('moves a card to another stage', async () => {
    const { server, user } = setup()
    server.on('POST /opportunities/:id/stage', {
      body: anOpportunity({
        stage: { id: 's-proposal', name: 'Proposal', kind: 'OPEN', probability: 50 },
        version: 1,
      }),
    })
    await user.selectOptions(
      await screen.findByLabelText('Move Packaging renewal to'),
      's-proposal',
    )
    expect(server.callsTo('POST /opportunities/:id/stage')[0].body).toEqual({
      stageId: 's-proposal',
      version: 0,
    })
    expect(await screen.findByText('Moved to Proposal.')).toBeInTheDocument()
    expect(server.callsTo('GET /crm/pipeline/board').length).toBeGreaterThan(1)
  })

  it('refreshes the board and the deal when a move fails', async () => {
    const { server, user } = setup()
    server.on('POST /opportunities/:id/stage', {
      status: 409,
      body: {
        title: 'Conflict',
        detail: 'This record was changed by someone else. Reload and try again.',
      },
    })
    await screen.findByLabelText('Move Packaging renewal to')
    const boards = server.callsTo('GET /crm/pipeline/board').length
    await user.selectOptions(screen.getByLabelText('Move Packaging renewal to'), 's-proposal')
    expect(await screen.findByText(/changed by someone else/)).toBeInTheDocument()
    await vi.waitFor(() =>
      expect(server.callsTo('GET /crm/pipeline/board').length).toBeGreaterThan(boards),
    )
  })

  it('asks why a deal was lost', async () => {
    const { server, user } = setup()
    server.on('POST /opportunities/:id/stage', {
      body: anOpportunity({ status: 'LOST', version: 1 }),
    })
    await user.selectOptions(await screen.findByLabelText('Move Packaging renewal to'), 's-lost')
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Chose a competitor')
    await user.click(within(dialog).getByRole('button', { name: 'Mark as lost' }))
    expect(server.callsTo('POST /opportunities/:id/stage')[0].body).toEqual({
      stageId: 's-lost',
      lostReason: 'Chose a competitor',
      version: 0,
    })
  })

  it('creates an opportunity for an account', async () => {
    const { server, user, router } = setup()
    server.on('POST /opportunities', {
      status: 201,
      body: anOpportunity({ id: 'o-new', name: 'Spice supply' }),
    })
    await user.click(await screen.findByRole('button', { name: 'New opportunity' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Spice supply')
    await user.selectOptions(await within(dialog).findByLabelText('Account'), 'p-acme')
    await user.type(within(dialog).getByLabelText('Amount'), '900')
    await user.type(within(dialog).getByLabelText('Currency'), 'inr')
    await user.click(within(dialog).getByRole('button', { name: 'Create opportunity' }))
    expect(server.callsTo('POST /opportunities')[0].body).toEqual({
      name: 'Spice supply',
      accountId: 'p-acme',
      contactId: null,
      stageId: 's-prospecting',
      amount: 900,
      currency: 'INR',
      expectedCloseOn: null,
      ownerId: null,
      description: null,
    })
    expect(router.state.location.pathname).toBe('/app/crm/opportunities/o-new')
  })

  it('requires an account before sending', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New opportunity' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'X')
    await user.click(within(dialog).getByRole('button', { name: 'Create opportunity' }))
    expect(await within(dialog).findByText('Choose an account.')).toBeInTheDocument()
    expect(server.callsTo('POST /opportunities')).toHaveLength(0)
  })

  it('is read-only for readers', async () => {
    setup(['crm.opportunity.read'])
    await screen.findByRole('region', { name: 'Prospecting' })
    expect(screen.queryByLabelText('Move Packaging renewal to')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New opportunity' })).not.toBeInTheDocument()
  })
})
