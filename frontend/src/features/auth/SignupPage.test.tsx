import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedOut(server)
  return renderApp({ server, path: '/signup', routes: authTestRoutes })
}

describe('SignupPage', () => {
  it('validates before calling the server', async () => {
    const { user, server } = setup()
    await user.click(await screen.findByRole('button', { name: 'Create workspace' }))
    expect(await screen.findAllByText('Required.')).not.toHaveLength(0)
    await user.type(screen.getByLabelText('Work email'), 'not-an-email')
    await user.type(screen.getByLabelText('Password'), 'short')
    await user.click(screen.getByRole('button', { name: 'Create workspace' }))
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(screen.getByText('Use at least 12 characters.')).toBeInTheDocument()
    expect(server.callsTo('POST /auth/signup')).toHaveLength(0)
  })

  it('suggests a workspace URL from the name until the user edits it', async () => {
    const { user } = setup()
    await user.type(await screen.findByLabelText('Workspace name'), 'Acme Trading Co.')
    expect(screen.getByLabelText('Workspace URL')).toHaveValue('acme-trading-co')
  })

  it('shows a server field error under the field and keeps the other values', async () => {
    const { user, server } = setup()
    server.on('POST /auth/signup', {
      status: 409,
      body: {
        detail: 'Conflict',
        errors: [{ field: 'slug', message: 'This workspace URL is already taken.' }],
      },
    })
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Create workspace' }))
    expect(await screen.findByText('This workspace URL is already taken.')).toBeInTheDocument()
    expect(screen.getByLabelText('Workspace URL')).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText('Work email')).toHaveValue('ada@acme.test')
  })

  it('asks the user to check their email, and can resend', async () => {
    const { user, server } = setup()
    server
      .on('POST /auth/signup', {
        status: 201,
        body: { slug: 'acme', status: 'PENDING_VERIFICATION' },
      })
      .on('POST /auth/resend-verification', { status: 202 })
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Create workspace' }))
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(server.callsTo('POST /auth/signup')[0].body).toEqual({
      workspaceName: 'Acme Inc',
      slug: 'acme',
      firstName: 'Ada',
      lastName: 'Lovelace',
      email: 'ada@acme.test',
      password: 'correct horse battery',
    })
    await user.click(screen.getByRole('button', { name: 'Resend the email' }))
    expect(await screen.findByText(/we've sent a new link/i)).toBeInTheDocument()
    expect(server.callsTo('POST /auth/resend-verification')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
    })
  })
})

async function fill(user: ReturnType<typeof setup>['user']) {
  await user.type(await screen.findByLabelText('Workspace name'), 'Acme Inc')
  await user.clear(screen.getByLabelText('Workspace URL'))
  await user.type(screen.getByLabelText('Workspace URL'), 'acme')
  await user.type(screen.getByLabelText('First name'), 'Ada')
  await user.type(screen.getByLabelText('Last name'), 'Lovelace')
  await user.type(screen.getByLabelText('Work email'), 'ada@acme.test')
  await user.type(screen.getByLabelText('Password'), 'correct horse battery')
}
