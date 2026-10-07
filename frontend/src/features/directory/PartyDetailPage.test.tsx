import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import type { PartyView } from '@/lib/api/types'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aParty, aPerson, aSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(party: PartyView, permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /parties/:id', { body: party })
    .on('GET /parties', { body: pageOf([]) })
    // record panels (Tasks 11–12) load these; empty by default
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: `/app/directory/${party.id}` })
}

const GRACE = aPerson({
  jobTitle: 'Rear Admiral',
  organization: { id: 'p-acme', name: 'Acme' },
  phone: '+1 555 0100',
  roles: [
    { role: 'CUSTOMER', status: 'ACTIVE', since: '2026-01-15', employeeNumber: null },
    { role: 'EMPLOYEE', status: 'ACTIVE', since: null, employeeNumber: 'E-7' },
  ],
})

describe('PartyDetailPage', () => {
  it('shows a person with contact details, organization and roles', async () => {
    setup(GRACE)
    expect(await screen.findByRole('heading', { name: 'Grace Hopper' })).toBeInTheDocument()
    expect(screen.getByText('Person · Rear Admiral')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'grace@acme.test' })).toHaveAttribute(
      'href',
      'mailto:grace@acme.test',
    )
    expect(screen.getByRole('link', { name: 'Acme' })).toHaveAttribute(
      'href',
      '/app/directory/p-acme',
    )
    const roles = within(screen.getByRole('region', { name: 'Roles' }))
    expect(roles.getByText(/Active since/)).toBeInTheDocument()
    expect(roles.getByRole('button', { name: 'End customer role' })).toBeInTheDocument()
    expect(roles.getByRole('button', { name: 'Mark as supplier' })).toBeInTheDocument()
    expect(roles.getByLabelText('Employee number')).toHaveValue('E-7')
  })

  it('hides employee facts without the employee permission', async () => {
    setup(GRACE, ['directory.party.read', 'directory.party.manage'])
    await screen.findByRole('heading', { name: 'Grace Hopper' })
    const roles = within(screen.getByRole('region', { name: 'Roles' }))
    expect(roles.queryByText('Employee')).not.toBeInTheDocument()
    expect(roles.queryByLabelText('Employee number')).not.toBeInTheDocument()
  })

  it('changes a role and shows the updated record', async () => {
    const { server, user } = setup(GRACE)
    server.on('PUT /parties/:id/roles/:role', (req) => ({
      body: {
        ...GRACE,
        roles: [
          ...GRACE.roles,
          { role: req.params.role, status: 'ACTIVE', since: null, employeeNumber: null },
        ],
      },
    }))
    await user.click(await screen.findByRole('button', { name: 'Mark as supplier' }))
    expect(server.callsTo('PUT /parties/:id/roles/:role')[0].body).toEqual({
      status: 'ACTIVE',
      since: null,
      employeeNumber: null,
    })
    expect(await screen.findByRole('button', { name: 'End supplier role' })).toBeInTheDocument()
  })

  it('saves an employee number', async () => {
    const { server, user } = setup(GRACE)
    server.on('PUT /parties/:id/roles/:role', { body: GRACE })
    const input = await screen.findByLabelText('Employee number')
    await user.clear(input)
    await user.type(input, 'E-8')
    await user.click(screen.getByRole('button', { name: 'Save number' }))
    expect(server.callsTo('PUT /parties/:id/roles/:role')[0]).toMatchObject({
      params: { role: 'EMPLOYEE' },
      body: { status: 'ACTIVE', since: null, employeeNumber: 'E-8' },
    })
  })

  it('keeps the current organization when the options load after the dialog opens', async () => {
    const { server, user } = setup(GRACE)
    server
      .on('GET /parties', { body: pageOf([aSummary(), aSummary({ id: 'p-beta', name: 'Beta' })]) })
      .on('PUT /persons/:id', { body: { ...GRACE, version: 1 } })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('option', { name: 'Beta' })
    expect(within(dialog).getByLabelText('Organization')).toHaveValue('p-acme')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(server.callsTo('PUT /persons/:id')[0].body).toMatchObject({ organizationId: 'p-acme' })
  })

  it('edits with the loaded version', async () => {
    const { server, user } = setup(GRACE)
    server.on('PUT /persons/:id', { body: { ...GRACE, name: 'Grace B. Hopper', version: 1 } })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(server.callsTo('PUT /persons/:id')[0].body).toMatchObject({
      firstName: 'Grace',
      version: 0,
    })
    expect(await screen.findByRole('heading', { name: 'Grace B. Hopper' })).toBeInTheDocument()
  })

  it('archives after confirmation and can restore', async () => {
    const { server, user } = setup(GRACE)
    server
      .on('POST /parties/:id/archive', { body: { ...GRACE, archivedAt: '2026-10-06T10:00:00Z' } })
      .on('POST /parties/:id/restore', { body: GRACE })
    await user.click(await screen.findByRole('button', { name: 'Archive' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive' }),
    )
    expect(await screen.findByText(/This record is archived/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark as supplier' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Restore' }))
    expect(await screen.findByRole('button', { name: 'Edit' })).toBeInTheDocument()
  })

  it('lists the people of an organization', async () => {
    const { server } = setup(aParty())
    server.on('GET /parties', (req) => ({
      body: pageOf(
        req.query.get('organizationId') === 'p-acme'
          ? [
              aSummary({
                id: 'p-grace',
                kind: 'PERSON',
                name: 'Grace Hopper',
                email: 'grace@acme.test',
              }),
            ]
          : [],
      ),
    }))
    const people = within(await screen.findByRole('region', { name: 'People' }))
    expect(await people.findByRole('link', { name: 'Grace Hopper' })).toHaveAttribute(
      'href',
      '/app/directory/p-grace',
    )
  })

  it('shows readers no edit controls', async () => {
    setup(GRACE, ['directory.party.read'])
    await screen.findByRole('heading', { name: 'Grace Hopper' })
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark as supplier' })).not.toBeInTheDocument()
  })

  it('shows the server message for an unknown record', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /parties/:id', { status: 404, body: { detail: 'Record not found.' } })
    renderApp({ server, path: '/app/directory/p-missing' })
    expect(await screen.findByText('Record not found.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: '← Directory' })).toHaveAttribute(
      'href',
      '/app/directory',
    )
  })
})
