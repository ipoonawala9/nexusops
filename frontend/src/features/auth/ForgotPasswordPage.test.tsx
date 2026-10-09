import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedOut(server)
  return { ...renderApp({ server, path: '/forgot-password', routes: authTestRoutes }) }
}

async function fillAndSubmit(user: ReturnType<typeof setup>['user']) {
  await user.type(await screen.findByLabelText('Workspace URL'), 'acme')
  await user.type(screen.getByLabelText('Email'), 'ada@acme.test')
  await user.click(screen.getByRole('button', { name: 'Send reset link' }))
}

describe('ForgotPasswordPage', () => {
  it('posts the workspace and email and shows the neutral confirmation', async () => {
    const { user, server } = setup()
    server.on('POST /auth/password-reset/request', { status: 204 })
    await fillAndSubmit(user)
    expect(await screen.findByRole('heading', { name: 'Check your email' })).toBeInTheDocument()
    expect(
      screen.getByText(
        "If an account matches, we've sent a link to reset your password. It expires in 1 hour.",
      ),
    ).toBeInTheDocument()
    expect(server.callsTo('POST /auth/password-reset/request')[0].body).toEqual({
      workspace: 'acme',
      email: 'ada@acme.test',
    })
    expect(screen.getByRole('link', { name: 'Back to sign in' })).toHaveAttribute('href', '/login')
  })

  it('validates both fields before calling the server', async () => {
    const { user, server } = setup()
    await user.type(await screen.findByLabelText('Email'), 'nope')
    await user.click(screen.getByRole('button', { name: 'Send reset link' }))
    expect(await screen.findByText('Enter a valid email address.')).toBeInTheDocument()
    expect(screen.getAllByText('Required.')).toHaveLength(1)
    expect(server.callsTo('POST /auth/password-reset/request')).toHaveLength(0)
  })

  it('shows the generic problem on a server error instead of the confirmation', async () => {
    const { user, server } = setup()
    server.on('POST /auth/password-reset/request', { status: 500, body: {} })
    await fillAndSubmit(user)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Check your email' })).not.toBeInTheDocument()
  })

  it('shows the rate-limit message', async () => {
    const { user, server } = setup()
    server.on('POST /auth/password-reset/request', {
      status: 429,
      body: { detail: 'Too many requests. Try again later.' },
    })
    await fillAndSubmit(user)
    expect(await screen.findByText('Too many requests. Try again later.')).toBeInTheDocument()
  })
})
