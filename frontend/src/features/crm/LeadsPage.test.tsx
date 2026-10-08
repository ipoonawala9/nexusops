import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLead, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /leads', {
      body: pageOf([
        aLead(),
        aLead({
          id: 'l-deccan',
          name: 'Deccan Spices',
          firstName: null,
          lastName: null,
          companyName: 'Deccan Spices',
          owner: null,
          estimatedValue: null,
          currency: null,
        }),
      ]),
    })
    .on('GET /leads/:id', { body: aLead() })
    .on('GET /crm/owners', {
      body: [{ id: 'u-ada', name: 'Ada Lovelace', email: 'ada@acme.test' }],
    })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: '/app/crm/leads' })
}

describe('LeadsPage', () => {
  it('lists open leads with company, status, owner and value', async () => {
    const { server } = setup()
    const row = (await screen.findByRole('link', { name: 'Grace Hopper' })).closest(
      'tr',
    ) as HTMLElement
    expect(within(row).getByText('Acme Robotics')).toBeInTheDocument()
    expect(within(row).getByText('New')).toBeInTheDocument()
    expect(within(row).getByText('Ada Lovelace')).toBeInTheDocument()
    expect(within(row).getByText(/5,000/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Grace Hopper' })).toHaveAttribute(
      'href',
      '/app/crm/leads/l-grace',
    )
    expect(server.callsTo('GET /leads')[0].query.get('status')).toBeNull()
  })

  it('filters by status, owner, source and search', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Grace Hopper' })
    await user.selectOptions(screen.getByLabelText('Status'), 'DISQUALIFIED')
    await user.selectOptions(screen.getByLabelText('Owner'), 'me')
    await user.selectOptions(screen.getByLabelText('Source'), 'EVENT')
    await user.type(screen.getByLabelText('Search leads'), 'acme')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const last = server.callsTo('GET /leads').at(-1)?.query
    expect(last?.get('status')).toBe('DISQUALIFIED')
    expect(last?.get('owner')).toBe('me')
    expect(last?.get('source')).toBe('EVENT')
    expect(last?.get('q')).toBe('acme')
  })

  it('creates a lead and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /leads', { status: 201, body: aLead({ id: 'l-new', name: 'Meera Iyer' }) })
    await user.click(await screen.findByRole('button', { name: 'New lead' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('First name'), 'Meera')
    await user.type(within(dialog).getByLabelText('Last name'), 'Iyer')
    await user.type(within(dialog).getByLabelText('Estimated value'), '2500')
    await user.selectOptions(within(dialog).getByLabelText('Source'), 'WALK_IN')
    await user.click(within(dialog).getByRole('button', { name: 'Create lead' }))
    expect(server.callsTo('POST /leads')[0].body).toEqual({
      firstName: 'Meera',
      lastName: 'Iyer',
      companyName: null,
      jobTitle: null,
      email: null,
      phone: null,
      source: 'WALK_IN',
      ownerId: null,
      estimatedValue: 2500,
      currency: null,
      description: null,
    })
    await screen.findByText('Meera Iyer added.')
    expect(router.state.location.pathname).toBe('/app/crm/leads/l-new')
  })

  it('needs a name or a company before sending', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New lead' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Create lead' }))
    expect(await within(dialog).findByText('Enter a name or a company.')).toBeInTheDocument()
    expect(server.callsTo('POST /leads')).toHaveLength(0)
  })

  it('imports a CSV file and shows row errors', async () => {
    const { server, user } = setup()
    server.on('POST /leads/import', {
      status: 422,
      body: {
        detail: "Some rows can't be imported. Fix them and upload the file again.",
        errorCount: 1,
        rows: [{ row: 3, field: 'email', message: 'Enter a valid email address.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Import CSV' }))
    const dialog = await screen.findByRole('dialog')
    const file = new File(['company\nAcme\n'], 'leads.csv', { type: 'text/csv' })
    await user.upload(within(dialog).getByLabelText('CSV file'), file)
    await user.click(within(dialog).getByRole('button', { name: 'Import' }))
    const errors = await within(dialog).findByRole('table', { name: 'Rows to fix' })
    expect(within(errors).getByText('3')).toBeInTheDocument()
    expect(within(errors).getByText('email')).toBeInTheDocument()
    expect(within(errors).getByText('Enter a valid email address.')).toBeInTheDocument()
    expect(server.callsTo('POST /leads/import')[0].form?.get('file')).toBeInstanceOf(File)

    server.on('POST /leads/import', { body: { imported: 2 } })
    await user.click(within(dialog).getByRole('button', { name: 'Import' }))
    expect(await screen.findByText('2 leads imported.')).toBeInTheDocument()
  })

  it('hides create and import from readers', async () => {
    setup(['crm.lead.read'])
    await screen.findByRole('link', { name: 'Grace Hopper' })
    expect(screen.queryByRole('button', { name: 'New lead' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Import CSV' })).not.toBeInTheDocument()
  })
})
