import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { authTestRoutes } from '@/test/authRoutes'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

describe('VerifyEmailPage', () => {
  it('verifies the token once and removes it from the address bar', async () => {
    const server = fakeServer()
    signedOut(server).on('POST /auth/verify-email', { status: 204 })
    const { router } = renderApp({
      server,
      path: '/verify-email?token=abc.def',
      routes: authTestRoutes,
    })
    expect(await screen.findByRole('heading', { name: 'Email verified' })).toBeInTheDocument()
    expect(server.callsTo('POST /auth/verify-email')).toHaveLength(1)
    expect(server.callsTo('POST /auth/verify-email')[0].body).toEqual({ token: 'abc.def' })
    expect(router.state.location.search).toBe('')
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
  })

  it('explains a bad link and offers to resend', async () => {
    const server = fakeServer()
    signedOut(server)
      .on('POST /auth/verify-email', {
        status: 400,
        body: { detail: 'This verification link is invalid or has expired.' },
      })
      .on('POST /auth/resend-verification', { status: 202 })
    const { user } = renderApp({ server, path: '/verify-email?token=bad', routes: authTestRoutes })
    expect(
      await screen.findByText('This verification link is invalid or has expired.'),
    ).toBeInTheDocument()
    await user.type(screen.getByLabelText('Workspace URL'), 'acme')
    await user.type(screen.getByLabelText('Work email'), 'ada@acme.test')
    await user.click(screen.getByRole('button', { name: 'Resend the email' }))
    expect(await screen.findByText(/we've sent a new link/i)).toBeInTheDocument()
  })

  it('handles a link without a token', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/verify-email', routes: authTestRoutes })
    expect(await screen.findByText('This verification link is incomplete.')).toBeInTheDocument()
  })
})
