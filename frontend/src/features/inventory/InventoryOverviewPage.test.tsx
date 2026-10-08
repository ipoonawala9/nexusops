import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anOverview } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('InventoryOverviewPage', () => {
  it('counts what needs attention and lists recent movements', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['INVENTORY'] })).on('GET /inventory/overview', {
      body: anOverview(),
    })
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByRole('link', { name: /2\s*below minimum/i })).toHaveAttribute(
      'href',
      '/app/inventory/stock?belowMin=true',
    )
    // ordered and partly received orders both await receipt: no status filter
    expect(screen.getByRole('link', { name: /1\s*awaiting receipt/i })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders',
    )
    expect(screen.getByRole('link', { name: /3\s*awaiting fulfilment/i })).toHaveAttribute(
      'href',
      '/app/inventory/sales-orders?status=CONFIRMED',
    )
    expect(screen.getByRole('link', { name: 'PO-00001' })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders/po-1',
    )
    expect(screen.getByText('+10')).toBeInTheDocument()
  })

  it('says when nothing has moved yet', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['INVENTORY'] })).on('GET /inventory/overview', {
      body: anOverview({ recentMovements: [], belowMinimum: 0 }),
    })
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByText('No stock has moved yet.')).toBeInTheDocument()
  })

  it('shows only the order tiles the user may open', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({ modules: ['INVENTORY'], permissions: ['inventory.stock.read'] }),
    ).on('GET /inventory/overview', { body: anOverview() })
    renderApp({ server, path: '/app/inventory' })
    expect(await screen.findByRole('link', { name: /2\s*below minimum/i })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /awaiting receipt/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /awaiting fulfilment/i })).not.toBeInTheDocument()
  })

  it('shows the sales tile to a seller without purchase access', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({
        modules: ['INVENTORY'],
        permissions: ['inventory.stock.read', 'inventory.order.read'],
      }),
    ).on('GET /inventory/overview', { body: anOverview() })
    renderApp({ server, path: '/app/inventory' })
    expect(
      await screen.findByRole('link', { name: /3\s*awaiting fulfilment/i }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /awaiting receipt/i })).not.toBeInTheDocument()
  })
})
