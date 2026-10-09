import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup(path = '/login') {
  const server = fakeServer()
  signedOut(server)
  return renderApp({ server, path, routes: authTestRoutes })
}

async function signIn(user: ReturnType<typeof setup>['user']) {
  await user.type(await screen.findByLabelText('Workspace URL'), 'acme')
  await user.type(screen.getByLabelText('Email'), 'ada@acme.test')
  await user.type(screen.getByLabelText('Password'), 'correct horse battery')
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('LoginPage', () => {
  it('signs in and returns to next', async () => {
    const { user, server, router } = setup(`/login?next=${encodeURIComponent('/app/audit')}`)
    server
      .on('POST /auth/login', { body: { accessToken: 'tok', tokenType: 'Bearer', expiresIn: 900 } })
      .on('GET /me', { body: testProfile() })
    await signIn(user)
    expect(await screen.findByRole('heading', { name: 'App home' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/audit')
    expect(server.callsTo('POST /auth/login')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
      password: 'correct horse battery',
    })
  })

  it('shows the uniform failure message', async () => {
    const { user, server } = setup()
    server.on('POST /auth/login', {
      status: 401,
      body: { detail: 'Invalid workspace, email or password.' },
    })
    await signIn(user)
    expect(await screen.findByText('Invalid workspace, email or password.')).toBeInTheDocument()
    expect(screen.getByLabelText('Email')).toHaveValue('ada@acme.test')
  })

  it('shows a suspended workspace', async () => {
    const { user, server } = setup()
    server.on('POST /auth/login', { status: 403, body: { detail: 'Workspace suspended.' } })
    await signIn(user)
    expect(await screen.findByText('Workspace suspended.')).toBeInTheDocument()
  })

  it('offers to resend verification for an unverified address', async () => {
    const { user, server } = setup()
    server
      .on('POST /auth/login', { status: 403, body: { detail: 'Email address not verified.' } })
      .on('POST /auth/resend-verification', { status: 202 })
    await signIn(user)
    await user.click(await screen.findByRole('button', { name: 'Resend the email' }))
    expect(await screen.findByText(/we've sent a new link/i)).toBeInTheDocument()
  })

  it('links to the forgot-password page', async () => {
    setup()
    expect(await screen.findByRole('link', { name: 'Forgot password?' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
    expect(screen.getByRole('link', { name: 'Create a workspace' })).toHaveAttribute(
      'href',
      '/signup',
    )
  })

  it('pre-fills the workspace and email from the link', async () => {
    setup('/login?workspace=acme&email=ada%40acme.test')
    expect(await screen.findByLabelText('Workspace URL')).toHaveValue('acme')
    expect(screen.getByLabelText('Email')).toHaveValue('ada@acme.test')
  })
})
