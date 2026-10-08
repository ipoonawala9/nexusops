import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aMovement, aProduct, aProductStock, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(modules: string[], kind: 'GOODS' | 'SERVICE' = 'GOODS') {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /products/:id', { body: aProduct({ kind }) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /inventory/stock/products/:id', { body: aProductStock() })
    .on('GET /inventory/movements', { body: pageOf([aMovement()]) })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
  return renderApp({ server, path: '/app/products/pr-widget' })
}

describe('ProductStockPanel', () => {
  it('shows stock per warehouse and recent movements when Inventory is on', async () => {
    const { server } = setup(['INVENTORY'])
    const panel = await screen.findByRole('region', { name: 'Stock' })
    // the levels table comes first; the movements table repeats the warehouse code
    const row = (await within(panel).findAllByText('MAIN'))[0].closest('tr') as HTMLElement
    expect(within(row).getByText('12')).toBeInTheDocument()
    expect(within(row).getByText('10')).toBeInTheDocument()
    expect(await within(panel).findByRole('link', { name: 'PO-00001' })).toBeInTheDocument()
    expect(server.callsTo('GET /inventory/movements')[0].query.get('productId')).toBe('pr-widget')
  })

  it('is absent without Inventory', async () => {
    setup([])
    await screen.findByRole('heading', { name: 'Widget' })
    expect(screen.queryByRole('region', { name: 'Stock' })).not.toBeInTheDocument()
  })

  it('is absent for a service', async () => {
    setup(['INVENTORY'], 'SERVICE')
    await screen.findByRole('heading', { name: 'Widget' })
    expect(screen.queryByRole('region', { name: 'Stock' })).not.toBeInTheDocument()
  })
})
