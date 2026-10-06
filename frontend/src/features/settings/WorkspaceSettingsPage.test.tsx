import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const SETTINGS = {
  id: 't-acme',
  slug: 'acme',
  name: 'Acme Inc',
  status: 'ACTIVE',
  planCode: 'FREE',
  timezone: 'UTC',
  locale: 'en',
  currency: 'USD',
}

describe('WorkspaceSettingsPage', () => {
  it('saves changed settings and refreshes the profile', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant', { body: SETTINGS })
      .on('PATCH /tenant', (req) => ({ body: { ...SETTINGS, ...(req.body as object) } }))
    const { user } = renderApp({ server, path: '/app/settings/workspace' })
    const name = await screen.findByLabelText('Workspace name')
    expect(name).toHaveValue('Acme Inc')
    expect(screen.getByText('acme')).toBeInTheDocument()
    await user.clear(name)
    await user.type(name, 'Acme Group')
    await user.clear(screen.getByLabelText('Currency'))
    await user.type(screen.getByLabelText('Currency'), 'inr')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Workspace settings saved.')).toBeInTheDocument()
    expect(server.callsTo('PATCH /tenant')[0].body).toEqual({
      name: 'Acme Group',
      timezone: 'UTC',
      locale: 'en',
      currency: 'INR',
    })
    expect(server.callsTo('GET /me').length).toBeGreaterThan(1)
  })

  it('shows a server field error', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant', { body: SETTINGS })
      .on('PATCH /tenant', {
        status: 400,
        body: {
          detail: 'Bad Request',
          errors: [{ field: 'locale', message: 'Use a language tag such as en or en-IN.' }],
        },
      })
    const { user } = renderApp({ server, path: '/app/settings/workspace' })
    await user.clear(await screen.findByLabelText('Locale'))
    await user.type(screen.getByLabelText('Locale'), '!!')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Use a language tag such as en or en-IN.')).toBeInTheDocument()
  })

  it('is read-only without the update permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['tenant.settings.read'] })).on('GET /tenant', {
      body: SETTINGS,
    })
    renderApp({ server, path: '/app/settings/workspace' })
    expect(await screen.findByLabelText('Workspace name')).toBeDisabled()
    expect(screen.queryByRole('button', { name: 'Save changes' })).not.toBeInTheDocument()
    expect(screen.getByText(/can view these settings but not change them/i)).toBeInTheDocument()
  })

  it('shows an error state with a retry', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /tenant', {
      status: 500,
      body: { detail: 'Boom.', requestId: 'req-1' },
    })
    renderApp({ server, path: '/app/settings/workspace' })
    expect(await screen.findByText('Boom.')).toBeInTheDocument()
    expect(screen.getByText('Reference: req-1')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
