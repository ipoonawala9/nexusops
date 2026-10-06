import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { RoleView } from '@/lib/api/types'
import { fakeServer } from '@/test/fakeServer'
import { signedIn } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const CATALOG = [
  { code: 'identity.user.read', module: null, description: 'View users', moduleEnabled: true },
  {
    code: 'audit.event.read',
    module: null,
    description: 'View the audit log',
    moduleEnabled: true,
  },
  { code: 'crm.customer.read', module: 'CRM', description: 'View customers', moduleEnabled: false },
]
const SUPPORT: RoleView = {
  id: 'r-support',
  name: 'Support',
  description: 'Front line',
  system: false,
  permissions: ['identity.user.read'],
}
const OWNER: RoleView = {
  id: 'r-owner',
  name: 'TENANT_OWNER',
  description: null,
  system: true,
  permissions: ['identity.user.read'],
}

function setup(role = SUPPORT) {
  const server = fakeServer()
  signedIn(server).on('GET /roles/:id', { body: role }).on('GET /permissions', { body: CATALOG })
  return renderApp({ server, path: `/app/settings/roles/${role.id}` })
}

describe('RoleDetailPage', () => {
  it('groups permissions by module and saves the selection', async () => {
    const { server, user } = setup()
    server.on('PUT /roles/:id/permissions', (req) => ({
      body: { ...SUPPORT, ...(req.body as object) },
    }))
    expect(await screen.findByRole('heading', { name: 'Support' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Workspace administration' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'CRM module (not enabled)' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /View users/ })).toBeChecked()
    await user.click(screen.getByRole('checkbox', { name: /View the audit log/ }))
    await user.click(screen.getByRole('button', { name: 'Save permissions' }))
    expect(await screen.findByText('Permissions saved.')).toBeInTheDocument()
    expect(server.callsTo('PUT /roles/:id/permissions')[0].body).toEqual({
      permissions: ['audit.event.read', 'identity.user.read'],
    })
  })

  it("shows the server's hierarchy refusal", async () => {
    const { server, user } = setup()
    server.on('PUT /roles/:id/permissions', {
      status: 403,
      body: { detail: "You can't change a role with permissions you don't have." },
    })
    await user.click(await screen.findByRole('button', { name: 'Save permissions' }))
    expect(
      await screen.findByText("You can't change a role with permissions you don't have."),
    ).toBeInTheDocument()
  })

  it('renames a role', async () => {
    const { server, user } = setup()
    server.on('PATCH /roles/:id', (req) => ({ body: { ...SUPPORT, ...(req.body as object) } }))
    const name = await screen.findByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Support L1')
    await user.click(screen.getByRole('button', { name: 'Save details' }))
    expect(await screen.findByText('Role updated.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /roles/:id')[0].body).toEqual({
      name: 'Support L1',
      description: 'Front line',
    })
  })

  it("limits the description to the server's 255 characters", async () => {
    const { server, user } = setup()
    const description = await screen.findByLabelText('Description')
    await user.clear(description)
    await user.click(description)
    await user.paste('x'.repeat(256))
    await user.click(screen.getByRole('button', { name: 'Save details' }))
    expect(await screen.findByText('Use at most 255 characters.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /roles/:id')).toHaveLength(0)
  })

  it('deletes a role, or explains why it cannot', async () => {
    const { server, user, router } = setup()
    server.on('DELETE /roles/:id', {
      status: 409,
      body: { detail: 'This role is assigned to 2 users. Unassign it first.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Delete role' }))
    await user.click(await screen.findByRole('button', { name: 'Delete' }))
    expect(
      await screen.findByText('This role is assigned to 2 users. Unassign it first.'),
    ).toBeInTheDocument()
    server.on('DELETE /roles/:id', { status: 204 }).on('GET /roles', { body: [] })
    await user.click(screen.getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText('Role deleted.')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/settings/roles')
  })

  it('keeps system roles read-only', async () => {
    setup(OWNER)
    expect(await screen.findByText("System roles can't be changed.")).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /View users/ })).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save permissions' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete role' })).not.toBeInTheDocument()
  })
})
