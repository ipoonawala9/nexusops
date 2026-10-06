import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { platformMe, platformSignedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  platformSignedOut(server).on('GET /platform/tenants', {
    body: { items: [], page: 0, size: 20, total: 0 },
  })
  return renderApp({ server, path: '/platform/login' })
}

async function fill(user: ReturnType<typeof setup>['user'], code: string) {
  await user.type(await screen.findByLabelText('Email'), 'ops@nexusops.test')
  await user.type(screen.getByLabelText('Password'), 'a platform passphrase')
  await user.type(screen.getByLabelText('Authenticator code'), code)
  await user.click(screen.getByRole('button', { name: 'Sign in' }))
}

describe('PlatformLoginPage', () => {
  it('requires a six-digit code', async () => {
    const { user, server } = setup()
    await fill(user, '12ab')
    expect(await screen.findByText('Enter the 6-digit code.')).toBeInTheDocument()
    expect(server.callsTo('POST /platform/auth/login')).toHaveLength(0)
  })

  it('signs in and opens the workspace list', async () => {
    const { user, server, router } = setup()
    server
      .on('POST /platform/auth/login', {
        body: { accessToken: 'p', tokenType: 'Bearer', expiresIn: 900 },
      })
      .on('GET /platform/me', { body: platformMe() })
    await fill(user, '123 456')
    expect(await screen.findByRole('heading', { name: 'Workspaces' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/platform/tenants')
    expect(server.callsTo('POST /platform/auth/login')[0].body).toEqual({
      email: 'ops@nexusops.test',
      password: 'a platform passphrase',
      code: '123456',
    })
    expect(server.callsTo('POST /auth/refresh')).toHaveLength(0) // the tenant session is never touched
  })

  it('shows the uniform failure', async () => {
    const { user, server } = setup()
    server.on('POST /platform/auth/login', {
      status: 401,
      body: { detail: 'Invalid email, password or code.' },
    })
    await fill(user, '000000')
    expect(await screen.findByText('Invalid email, password or code.')).toBeInTheDocument()
  })
})
