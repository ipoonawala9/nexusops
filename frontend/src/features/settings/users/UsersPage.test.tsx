import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile, user as aUser } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const GRACE = aUser({
  id: 'u-grace',
  email: 'grace@acme.test',
  firstName: 'Grace',
  lastName: 'Hopper',
  roles: [{ id: 'r-support', name: 'Support' }],
})
const ROLES = [
  { id: 'r-owner', name: 'TENANT_OWNER', description: null, system: true, permissions: [] },
  {
    id: 'r-support',
    name: 'Support',
    description: null,
    system: false,
    permissions: ['identity.user.read'],
  },
  {
    id: 'r-auditor',
    name: 'Auditor',
    description: null,
    system: false,
    permissions: ['audit.event.read'],
  },
]

function page(items = [aUser(), GRACE], total = items.length) {
  return { items, page: 0, size: 20, total }
}

function setup(profile = testProfile()) {
  const server = fakeServer()
  signedIn(server, profile)
    .on('GET /users', { body: page() })
    .on('GET /roles', { body: ROLES })
    .on('GET /invitations', { body: [] })
  return renderApp({ server, path: '/app/settings/users' })
}

describe('UsersPage', () => {
  it('lists people with their roles and status', async () => {
    const { server } = setup()
    const row = (await screen.findByText('grace@acme.test')).closest('tr')
    expect(row).not.toBeNull()
    expect(within(row as HTMLElement).getByText('Support')).toBeInTheDocument()
    expect(within(row as HTMLElement).getByText('Active')).toBeInTheDocument()
    const query = server.callsTo('GET /users')[0].query
    expect(query.get('page')).toBe('0')
    expect(query.get('size')).toBe('20')
  })

  it('filters by status and search, and pages', async () => {
    const { server, user } = setup()
    server.on('GET /users', (req) => ({
      body: { ...page(), total: 45, page: Number(req.query.get('page')) },
    }))
    await screen.findByText('grace@acme.test')
    await user.selectOptions(screen.getByLabelText('Status'), 'DISABLED')
    await user.type(screen.getByLabelText('Search people'), 'grace')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await user.click(await screen.findByRole('button', { name: 'Next page' }))
    const last = server.callsTo('GET /users').at(-1)?.query
    expect(last?.get('status')).toBe('DISABLED')
    expect(last?.get('q')).toBe('grace')
    expect(last?.get('page')).toBe('1')
    expect(screen.getByText('Page 2 of 3')).toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    const { server } = setup()
    server.on('GET /users', { body: page([], 0) })
    expect(await screen.findByText('No people match these filters.')).toBeInTheDocument()
  })

  it('renames a person', async () => {
    const { server, user } = setup()
    server.on('PATCH /users/:id', (req) => ({ body: { ...GRACE, ...(req.body as object) } }))
    await user.click(await screen.findByRole('button', { name: 'Manage Grace Hopper' }))
    const dialog = await screen.findByRole('dialog')
    await user.clear(within(dialog).getByLabelText('First name'))
    await user.type(within(dialog).getByLabelText('First name'), 'Rear Admiral')
    await user.click(within(dialog).getByRole('button', { name: 'Save name' }))
    expect(await screen.findByText('Name updated.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /users/:id')[0]).toMatchObject({
      params: { id: 'u-grace' },
      body: { firstName: 'Rear Admiral', lastName: 'Hopper' },
    })
  })

  it("shows the server's 403 detail when disabling a stronger user", async () => {
    const { server, user } = setup()
    server.on('PATCH /users/:id', {
      status: 403,
      body: { detail: "You can't manage a user with permissions you don't have." },
    })
    await user.click(await screen.findByRole('button', { name: 'Manage Grace Hopper' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Disable user' }))
    await user.click(within(dialog).getByRole('button', { name: 'Confirm disable' }))
    expect(
      await within(dialog).findByText("You can't manage a user with permissions you don't have."),
    ).toBeInTheDocument()
    expect(server.callsTo('PATCH /users/:id')[0].body).toEqual({ status: 'DISABLED' })
  })

  it("changes a person's roles", async () => {
    const { server, user } = setup()
    server.on('PUT /users/:id/roles', (req) => ({
      body: {
        ...GRACE,
        roles: [
          { id: 'r-support', name: 'Support' },
          { id: 'r-auditor', name: 'Auditor' },
        ],
        _echo: req.body,
      },
    }))
    await user.click(await screen.findByRole('button', { name: 'Manage Grace Hopper' }))
    const dialog = await screen.findByRole('dialog')
    expect(await within(dialog).findByRole('checkbox', { name: 'Support' })).toBeChecked()
    await user.click(within(dialog).getByRole('checkbox', { name: 'Auditor' }))
    await user.click(within(dialog).getByRole('button', { name: 'Save roles' }))
    expect(await screen.findByText('Roles updated.')).toBeInTheDocument()
    expect(server.callsTo('PUT /users/:id/roles')[0].body).toEqual({
      roleIds: ['r-support', 'r-auditor'],
    })
  })

  it('offers no management actions to a read-only viewer', async () => {
    setup(testProfile({ permissions: ['identity.user.read'] }))
    await screen.findByText('grace@acme.test')
    expect(screen.queryByRole('button', { name: /^Manage / })).not.toBeInTheDocument()
  })

  it('cannot disable your own account from the dialog', async () => {
    const { user } = setup()
    await user.click(await screen.findByRole('button', { name: 'Manage Ada Lovelace' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByRole('button', { name: 'Disable user' })).not.toBeInTheDocument()
  })
})
