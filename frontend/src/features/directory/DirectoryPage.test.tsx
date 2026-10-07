import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aPerson, aSummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'

const ACME = aSummary({ roles: ['CUSTOMER', 'SUPPLIER'] })
const GRACE = aSummary({
  id: 'p-grace',
  kind: 'PERSON',
  name: 'Grace Hopper',
  email: 'grace@acme.test',
  domain: null,
  organization: { id: 'p-acme', name: 'Acme' },
})

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /parties', { body: pageOf([ACME, GRACE]) })
    .on('GET /parties/:id', { body: aPerson() })
  return renderApp({ server, path: '/app/directory' })
}

describe('DirectoryPage', () => {
  it('lists people and organizations with their roles', async () => {
    const { server } = setup()
    const row = (await screen.findByRole('link', { name: 'Acme' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Customer')).toBeInTheDocument()
    expect(within(row).getByText('Supplier')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Grace Hopper' })).toHaveAttribute(
      'href',
      '/app/directory/p-grace',
    )
    expect(screen.getByText(/Person · Acme/)).toBeInTheDocument()
    const query = server.callsTo('GET /parties')[0].query
    expect(query.get('size')).toBe('20')
    expect(query.get('archived')).toBeNull()
  })

  it('filters by kind, role, status and search', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Acme' })
    await user.selectOptions(screen.getByLabelText('Show'), 'PERSON')
    await user.selectOptions(screen.getByLabelText('Role'), 'EMPLOYEE')
    await user.selectOptions(screen.getByLabelText('Status'), 'archived')
    await user.type(screen.getByLabelText('Search directory'), 'grace')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const last = server.callsTo('GET /parties').at(-1)?.query
    expect(last?.get('kind')).toBe('PERSON')
    expect(last?.get('role')).toBe('EMPLOYEE')
    expect(last?.get('archived')).toBe('true')
    expect(last?.get('q')).toBe('grace')
  })

  it('offers the employee filter and create buttons only with permission', async () => {
    setup(['directory.party.read'])
    await screen.findByRole('link', { name: 'Acme' })
    const roles = within(screen.getByLabelText('Role'))
      .getAllByRole('option')
      .map((o) => o.textContent)
    expect(roles).not.toContain('Employees')
    expect(screen.queryByRole('button', { name: 'New person' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New organization' })).not.toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Acme' })
    server.on('GET /parties', { body: pageOf([]) })
    expect(screen.queryByText('Nothing here yet.')).not.toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Show'), 'PERSON')
    expect(await screen.findByText('No records match these filters.')).toBeInTheDocument()
  })

  it('invites you to add the first record when the directory is empty', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /parties', { body: pageOf([]) })
    renderApp({ server, path: '/app/directory' })
    expect(await screen.findByText('Nothing here yet.')).toBeInTheDocument()
  })

  it('creates a person and opens the new record', async () => {
    const { server, user, router } = setup()
    server.on('POST /persons', { status: 201, body: aPerson({ id: 'p-new', name: 'Ada Byron' }) })
    await user.click(await screen.findByRole('button', { name: 'New person' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('First name'), 'Ada')
    await user.type(within(dialog).getByLabelText('Last name'), 'Byron')
    await user.selectOptions(within(dialog).getByLabelText('Organization'), 'p-acme')
    await user.type(within(dialog).getByLabelText('Email'), 'ada@acme.test')
    await user.click(within(dialog).getByRole('button', { name: 'Create person' }))
    expect(server.callsTo('POST /persons')[0].body).toEqual({
      firstName: 'Ada',
      lastName: 'Byron',
      jobTitle: null,
      organizationId: 'p-acme',
      email: 'ada@acme.test',
      phone: null,
      duplicateReason: null,
    })
    await screen.findByText('Ada Byron added.')
    expect(router.state.location.pathname).toBe('/app/directory/p-new')
  })

  it('shows probable duplicates and needs a reason to create anyway', async () => {
    const { server, user } = setup()
    server.on('POST /organizations', (req) =>
      (req.body as { duplicateReason: string | null }).duplicateReason
        ? { status: 201, body: aPerson({ id: 'p-new', kind: 'ORGANIZATION', name: 'ACME Inc' }) }
        : {
            status: 409,
            body: {
              detail: 'This looks like a record that already exists.',
              duplicates: [
                {
                  id: 'p-acme',
                  kind: 'ORGANIZATION',
                  name: 'Acme',
                  email: null,
                  domain: 'acme.test',
                  archived: true,
                },
              ],
            },
          },
    )
    await user.click(await screen.findByRole('button', { name: 'New organization' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'ACME Inc')
    await user.click(within(dialog).getByRole('button', { name: 'Create organization' }))
    const notice = await within(dialog).findByText('This looks like a record that already exists.')
    expect(
      within(notice.parentElement as HTMLElement).getByRole('link', { name: 'Acme' }),
    ).toHaveAttribute('href', '/app/directory/p-acme')
    expect(within(dialog).getByText(/archived/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Create anyway' }))
    expect(
      await within(dialog).findByText('Give a reason, or open the existing record instead.'),
    ).toBeInTheDocument()
    expect(server.callsTo('POST /organizations')).toHaveLength(1)
    await user.type(
      within(dialog).getByLabelText('Why keep a separate record?'),
      'Separate legal entity',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Create anyway' }))
    expect(server.callsTo('POST /organizations')[1].body).toMatchObject({
      name: 'ACME Inc',
      duplicateReason: 'Separate legal entity',
    })
  })

  it('puts server field errors on the form', async () => {
    const { server, user } = setup()
    server.on('POST /organizations', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'domain', message: 'Enter a domain like example.com.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New organization' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Acme')
    await user.type(within(dialog).getByLabelText('Domain'), 'nope')
    await user.click(within(dialog).getByRole('button', { name: 'Create organization' }))
    expect(await within(dialog).findByText('Enter a domain like example.com.')).toBeInTheDocument()
  })
})
