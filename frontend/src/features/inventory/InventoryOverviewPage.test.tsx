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
    expect(screen.getByRole('link', { name: /1\s*awaiting receipt/i })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders?status=ORDERED',
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
})
