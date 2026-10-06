import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const PREVIEW = {
  workspace: 'acme',
  workspaceName: 'Acme Inc',
  email: 'grace@acme.test',
  roleName: 'Support',
  expiresAt: '2026-10-13T09:00:00Z',
}

function setup(path = '/invite/accept?token=t0k.en') {
  const server = fakeServer()
  signedOut(server).on('GET /invitations/preview', { body: PREVIEW })
  return renderApp({ server, path, routes: authTestRoutes })
}

async function fillAndSubmit(
  user: ReturnType<typeof setup>['user'],
  repeat = 'a long enough passphrase',
) {
  await user.type(await screen.findByLabelText('First name'), 'Grace')
  await user.type(screen.getByLabelText('Last name'), 'Hopper')
  await user.type(screen.getByLabelText('Password'), 'a long enough passphrase')
  await user.type(screen.getByLabelText('Repeat password'), repeat)
  await user.click(screen.getByRole('button', { name: 'Join Acme Inc' }))
}

describe('AcceptInvitationPage', () => {
  it('previews the invitation, accepts it and links to sign-in', async () => {
    const { user, server, router } = setup()
    server.on('POST /invitations/accept', {
      status: 201,
      body: { workspace: 'acme', email: 'grace@acme.test' },
    })
    expect(await screen.findByRole('heading', { name: 'Join Acme Inc' })).toBeInTheDocument()
    expect(screen.getByText(/grace@acme.test/)).toBeInTheDocument()
    expect(screen.getByText(/Support/)).toBeInTheDocument()
    expect(server.callsTo('GET /invitations/preview')[0].query.get('token')).toBe('t0k.en')
    expect(router.state.location.search).toBe('')
    await fillAndSubmit(user)
    expect(await screen.findByRole('heading', { name: "You're in" })).toBeInTheDocument()
    expect(server.callsTo('POST /invitations/accept')[0].body).toEqual({
      token: 't0k.en',
      firstName: 'Grace',
      lastName: 'Hopper',
      password: 'a long enough passphrase',
    })
    expect(screen.getByRole('link', { name: 'Sign in to Acme Inc' })).toHaveAttribute(
      'href',
      '/login?workspace=acme&email=grace%40acme.test',
    )
  })

  it('explains an invalid or expired invitation', async () => {
    const server = fakeServer()
    signedOut(server).on('GET /invitations/preview', {
      status: 400,
      body: { detail: 'This invitation link is invalid or has expired.' },
    })
    renderApp({ server, path: '/invite/accept?token=bad', routes: authTestRoutes })
    expect(
      await screen.findByText('This invitation link is invalid or has expired.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/ask the person who invited you/i)).toBeInTheDocument()
  })

  it('focuses the field a server error names', async () => {
    const { user, server } = setup()
    server.on('POST /invitations/accept', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'lastName', message: 'Enter a last name.' }],
      },
    })
    await fillAndSubmit(user)
    expect(await screen.findByText('Enter a last name.')).toBeInTheDocument()
    expect(screen.getByLabelText('Last name')).toHaveFocus()
  })

  it('checks the repeated password and shows server errors', async () => {
    const { user, server } = setup()
    await fillAndSubmit(user, 'something else entirely')
    expect(await screen.findByText("The passwords don't match.")).toBeInTheDocument()
    expect(server.callsTo('POST /invitations/accept')).toHaveLength(0)

    server.on('POST /invitations/accept', {
      status: 409,
      body: {
        detail: 'Conflict',
        errors: [{ field: 'email', message: 'This person is already a member of the workspace.' }],
      },
    })
    await user.clear(screen.getByLabelText('Repeat password'))
    await user.type(screen.getByLabelText('Repeat password'), 'a long enough passphrase')
    await user.click(screen.getByRole('button', { name: 'Join Acme Inc' }))
    expect(
      await screen.findByText('This person is already a member of the workspace.'),
    ).toBeInTheDocument()
  })

  it('keeps the success screen when the consumed token later fails to preview', async () => {
    const { user, server, queryClient } = setup()
    server.on('POST /invitations/accept', {
      status: 201,
      body: { workspace: 'acme', email: 'grace@acme.test' },
    })
    await fillAndSubmit(user)
    expect(await screen.findByRole('heading', { name: "You're in" })).toBeInTheDocument()

    server.on('GET /invitations/preview', {
      status: 400,
      body: { detail: 'This invitation link is invalid or has expired.' },
    })
    await queryClient.refetchQueries({ queryKey: ['invitation-preview'] })
    // the failed refetch has been recorded; let React render it
    await waitFor(() =>
      expect(queryClient.getQueryState(['invitation-preview', 't0k.en'])?.status).toBe('error'),
    )
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(screen.getByRole('heading', { name: "You're in" })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Sign in to Acme Inc' })).toBeInTheDocument()
    expect(screen.queryByText("This invitation can't be used")).not.toBeInTheDocument()
  })
})
