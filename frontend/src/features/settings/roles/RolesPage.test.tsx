import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const ROLES = [
  {
    id: 'r-owner',
    name: 'TENANT_OWNER',
    description: 'Full control',
    system: true,
    permissions: ['a', 'b'],
  },
  {
    id: 'r-support',
    name: 'Support',
    description: 'Front line',
    system: false,
    permissions: ['identity.user.read'],
  },
]

describe('RolesPage', () => {
  it('lists roles and creates one, then opens it', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /roles', { body: ROLES })
      .on('POST /roles', (req) => ({
        status: 201,
        body: { id: 'r-new', system: false, permissions: [], ...(req.body as object) },
      }))
      .on('GET /roles/:id', {
        body: { id: 'r-new', name: 'Auditor', description: null, system: false, permissions: [] },
      })
      .on('GET /permissions', { body: [] })
    const { user, router } = renderApp({ server, path: '/app/settings/roles' })
    const row = (await screen.findByRole('link', { name: 'Support' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Custom')).toBeInTheDocument()
    expect(within(row).getByText('1')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Auditor')
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }))
    expect(await screen.findByText('Role created. Now choose its permissions.')).toBeInTheDocument()
    expect(server.callsTo('POST /roles')[0].body).toEqual({
      name: 'Auditor',
      description: '',
      permissions: [],
    })
    expect(router.state.location.pathname).toBe('/app/settings/roles/r-new')
  })

  it('shows a duplicate-name error under the field', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /roles', { body: ROLES })
      .on('POST /roles', {
        status: 409,
        body: {
          detail: 'Conflict',
          errors: [{ field: 'name', message: 'A role with this name already exists.' }],
        },
      })
    const { user } = renderApp({ server, path: '/app/settings/roles' })
    await user.click(await screen.findByRole('button', { name: 'New role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Support')
    await user.click(within(dialog).getByRole('button', { name: 'Create role' }))
    expect(
      await within(dialog).findByText('A role with this name already exists.'),
    ).toBeInTheDocument()
  })

  it('hides New role without the manage permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['authorization.role.read'] })).on('GET /roles', {
      body: ROLES,
    })
    renderApp({ server, path: '/app/settings/roles' })
    await screen.findByRole('link', { name: 'Support' })
    expect(screen.queryByRole('button', { name: 'New role' })).not.toBeInTheDocument()
  })
})
