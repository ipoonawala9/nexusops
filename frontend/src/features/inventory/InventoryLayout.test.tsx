import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anOverview, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('InventoryLayout', () => {
  it('explains that Inventory is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/inventory' })
    expect(
      await screen.findByText('Inventory is not enabled for this workspace.'),
    ).toBeInTheDocument()
  })

  it('shows the sections a stock reader may open', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['INVENTORY'], permissions: ['inventory.stock.read'] }),
    ).on('GET /inventory/overview', { body: anOverview() })
    renderApp({ server, path: '/app/inventory' })
    const nav = await screen.findByRole('navigation', { name: 'Inventory' })
    expect(nav).toHaveTextContent('Overview')
  })

  it('has no sections without any Inventory permission', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({
        modules: ['INVENTORY'],
        permissions: [...ALL_TENANT_PERMISSIONS].filter((p) => !p.startsWith('inventory.')),
      }),
    )
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
  })

  it('opens the first section a user may see when the overview is not one of them', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['INVENTORY'], permissions: ['inventory.purchase.read'] }),
    ).on('GET /purchase-orders', { body: pageOf([]) })
    const { router } = renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByRole('heading', { name: 'Purchase orders' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/inventory/purchase-orders')
    expect(server.callsTo('GET /inventory/overview')).toHaveLength(0)
  })
})
