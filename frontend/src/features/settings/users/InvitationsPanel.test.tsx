import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const PENDING = {
  id: 'i-1',
  email: 'linus@acme.test',
  roleId: 'r-support',
  roleName: 'Support',
  status: 'PENDING',
  invitedBy: 'u-ada',
  expiresAt: '2026-10-13T09:00:00Z',
  createdAt: '2026-10-06T09:00:00Z',
}
const ROLES = [
  { id: 'r-support', name: 'Support', description: null, system: false, permissions: [] },
  { id: 'r-admin', name: 'TENANT_ADMIN', description: null, system: true, permissions: [] },
]

function setup(profile = testProfile(), invitations: unknown[] = [PENDING]) {
  const server = fakeServer()
  signedIn(server, profile)
    .on('GET /users', { body: { items: [], page: 0, size: 20, total: 0 } })
    .on('GET /invitations', { body: invitations })
    .on('GET /roles', { body: ROLES })
  return renderApp({ server, path: '/app/settings/users' })
}

describe('InvitationsPanel', () => {
  it('invites someone with a role', async () => {
    const { server, user } = setup()
    server.on('POST /invitations', (req) => ({
      status: 201,
      body: { ...PENDING, id: 'i-2', email: (req.body as { email: string }).email },
    }))
    await user.click(await screen.findByRole('button', { name: 'Invite people' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'grace@acme.test')
    await user.selectOptions(within(dialog).getByLabelText('Role'), 'r-support')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(await screen.findByText('Invitation sent to grace@acme.test.')).toBeInTheDocument()
    expect(server.callsTo('POST /invitations')[0].body).toEqual({
      email: 'grace@acme.test',
      roleId: 'r-support',
    })
  })

  it('shows a conflicting email under the field', async () => {
    const { server, user } = setup()
    server.on('POST /invitations', {
      status: 409,
      body: {
        detail: 'Conflict',
        errors: [{ field: 'email', message: 'An invitation is already pending for this email.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Invite people' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'linus@acme.test')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(
      await within(dialog).findByText('An invitation is already pending for this email.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Email')).toHaveValue('linus@acme.test')
  })

  it('shows the plan limit as a form error', async () => {
    const { server, user } = setup()
    server.on('POST /invitations', {
      status: 409,
      body: { detail: 'Your plan allows 3 users. Upgrade to add more.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Invite people' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Email'), 'new@acme.test')
    await user.click(within(dialog).getByRole('button', { name: 'Send invitation' }))
    expect(
      await within(dialog).findByText('Your plan allows 3 users. Upgrade to add more.'),
    ).toBeInTheDocument()
  })

  it('revokes a pending invitation after confirming', async () => {
    const { server, user } = setup()
    server.on('DELETE /invitations/:id', { status: 204 })
    await user.click(
      await screen.findByRole('button', { name: 'Revoke invitation for linus@acme.test' }),
    )
    await user.click(await screen.findByRole('button', { name: 'Revoke' }))
    expect(await screen.findByText('Invitation revoked.')).toBeInTheDocument()
    expect(server.callsTo('DELETE /invitations/:id')[0].params).toEqual({ id: 'i-1' })
  })

  it('hides invite and revoke without the invite permission', async () => {
    setup(testProfile({ permissions: ['identity.user.read'] }))
    expect(await screen.findByText('linus@acme.test')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Invite people' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Revoke invitation/ })).not.toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    setup(testProfile(), [])
    expect(await screen.findByText('No invitations yet.')).toBeInTheDocument()
  })
})
