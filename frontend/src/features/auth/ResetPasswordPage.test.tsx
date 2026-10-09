import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const INVALID = 'This reset link is invalid or has expired.'

function setup(path = '/reset-password?token=abc.def') {
  const server = fakeServer()
  signedOut(server)
  return { ...renderApp({ server, path, routes: authTestRoutes }) }
}

async function fill(
  user: ReturnType<typeof setup>['user'],
  password = 'a brand new passphrase',
  repeat = password,
) {
  await user.type(await screen.findByLabelText('New password', { exact: true }), password)
  await user.type(screen.getByLabelText('Repeat password', { exact: true }), repeat)
  await user.click(screen.getByRole('button', { name: 'Set new password' }))
}

describe('ResetPasswordPage', () => {
  it('posts the token and new password, then offers to sign in', async () => {
    const { user, server, router } = setup()
    server.on('POST /auth/password-reset', { status: 204 })
    expect(await screen.findByText('At least 12 characters.')).toBeInTheDocument()
    await fill(user)
    expect(await screen.findByRole('heading', { name: 'Password changed' })).toBeInTheDocument()
    expect(server.callsTo('POST /auth/password-reset')).toHaveLength(1)
    expect(server.callsTo('POST /auth/password-reset')[0].body).toEqual({
      token: 'abc.def',
      password: 'a brand new passphrase',
    })
    expect(router.state.location.search).toBe('') // the token left the address bar
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
  })

  it("doesn't call the server when the passwords differ", async () => {
    const { user, server } = setup()
    await fill(user, 'a brand new passphrase', 'a different passphrase')
    expect(await screen.findByText("Passwords don't match.")).toBeInTheDocument()
    expect(server.callsTo('POST /auth/password-reset')).toHaveLength(0)
  })

  it('checks the length before calling the server', async () => {
    const { user, server } = setup()
    await fill(user, 'short')
    expect(await screen.findAllByText('Use at least 12 characters.')).not.toHaveLength(0)
    expect(server.callsTo('POST /auth/password-reset')).toHaveLength(0)
  })

  it("puts the server's password complaint on the field and keeps the form", async () => {
    const { user, server } = setup()
    server.on('POST /auth/password-reset', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'password', message: 'This password is too common. Choose another.' }],
      },
    })
    await fill(user, 'password1234')
    expect(
      await screen.findByText('This password is too common. Choose another.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set new password' })).toBeInTheDocument()
  })

  it('explains an invalid or expired link and offers a new one', async () => {
    const { user, server } = setup()
    server.on('POST /auth/password-reset', { status: 400, body: { detail: INVALID } })
    await fill(user)
    expect(await screen.findByText(INVALID)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Request a new link' })).toHaveAttribute(
      'href',
      '/forgot-password',
    )
    expect(screen.queryByRole('button', { name: 'Set new password' })).not.toBeInTheDocument()
  })

  it('treats a link without a token as invalid', async () => {
    const { server } = setup('/reset-password')
    expect(await screen.findByText(INVALID)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Request a new link' })).toBeInTheDocument()
    expect(server.callsTo('POST /auth/password-reset')).toHaveLength(0)
  })

  it('shows a generic problem on a server error and lets the user retry', async () => {
    const { user, server } = setup()
    server.on('POST /auth/password-reset', { status: 500, body: {} })
    await fill(user)
    expect(await screen.findByText('Something went wrong. Please try again.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Set new password' })).toBeEnabled()
  })

  it('has show/hide toggles on both password fields', async () => {
    setup()
    expect(await screen.findByRole('button', { name: 'Show new password' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Show repeat password' })).toBeInTheDocument()
  })
})
