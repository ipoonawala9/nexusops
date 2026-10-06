import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const MODULES = [
  { code: 'CRM', name: 'CRM', enabled: true },
  { code: 'INVENTORY', name: 'Inventory', enabled: false },
]

describe('ModulesSettingsPage', () => {
  it('enables a module', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant/modules', { body: MODULES })
      .on('PUT /tenant/modules/:code', (req) => ({
        body: {
          code: req.params.code,
          name: 'Inventory',
          enabled: (req.body as { enabled: boolean }).enabled,
        },
      }))
    const { user } = renderApp({ server, path: '/app/settings/modules' })
    const toggle = await screen.findByRole('switch', { name: 'Inventory' })
    expect(toggle).not.toBeChecked()
    await user.click(toggle)
    expect(await screen.findByText('Inventory enabled.')).toBeInTheDocument()
    expect(server.callsTo('PUT /tenant/modules/:code')[0]).toMatchObject({
      params: { code: 'INVENTORY' },
      body: { enabled: true },
    })
    await waitFor(() => expect(screen.getByRole('switch', { name: 'Inventory' })).toBeChecked())
  })

  it('reports the plan limit and leaves the switch off', async () => {
    const server = fakeServer()
    signedIn(server)
      .on('GET /tenant/modules', { body: MODULES })
      .on('PUT /tenant/modules/:code', {
        status: 409,
        body: { detail: 'Your plan allows 2 modules. Upgrade to enable more.' },
      })
    const { user } = renderApp({ server, path: '/app/settings/modules' })
    await user.click(await screen.findByRole('switch', { name: 'Inventory' }))
    expect(
      await screen.findByText('Your plan allows 2 modules. Upgrade to enable more.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: 'Inventory' })).not.toBeChecked()
  })

  it('is read-only without the manage permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['tenant.settings.read'] })).on(
      'GET /tenant/modules',
      { body: MODULES },
    )
    renderApp({ server, path: '/app/settings/modules' })
    expect(await screen.findByRole('switch', { name: 'CRM' })).toBeDisabled()
  })
})
