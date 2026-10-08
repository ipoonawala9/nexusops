import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { SalesOrderView } from '@/lib/api/types'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, aSalesOrder, aSummary, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(order: SalesOrderView, permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /sales-orders/:id', { body: order })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-deccan', name: 'Deccan Retail' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
  return renderApp({ server, path: `/app/inventory/sales-orders/${order.id}` })
}

const confirmedOrder = () =>
  aSalesOrder({ status: 'CONFIRMED', confirmedAt: '2026-10-08T10:00:00Z', version: 1 })

const STALE = 'This record was changed by someone else. Reload and try again.'

describe('SalesOrderDetailPage', () => {
  it('confirms a draft, reserving its stock', async () => {
    const { server, user } = setup(aSalesOrder())
    const confirmed = confirmedOrder()
    server.on('POST /sales-orders/:id/confirm', { body: confirmed })
    const confirm = await screen.findByRole('button', { name: 'Confirm' })
    server.on('GET /sales-orders/:id', { body: confirmed })
    await user.click(confirm)
    expect(server.callsTo('POST /sales-orders/:id/confirm')[0].body).toEqual({ version: 0 })
    expect(await screen.findByRole('button', { name: 'Fulfil' })).toBeInTheDocument()
  })

  it('lists every shortage when confirming fails', async () => {
    const { server, user } = setup(aSalesOrder())
    server.on('POST /sales-orders/:id/confirm', {
      status: 409,
      body: {
        detail: 'Not enough stock.',
        shortages: [
          { productId: 'pr-widget', sku: 'W-1', requested: 4, available: 1 },
          { productId: 'pr-gadget', sku: 'G-1', requested: 2, available: 0 },
        ],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    expect(
      await screen.findByText(
        'Not enough stock: W-1 needs 4, 1 available; G-1 needs 2, 0 available.',
      ),
    ).toBeInTheDocument()
  })

  it('fulfils a confirmed order', async () => {
    const confirmed = confirmedOrder()
    const { server, user } = setup(confirmed)
    const fulfilled = {
      ...confirmed,
      status: 'FULFILLED' as const,
      fulfilledAt: '2026-10-08T12:00:00Z',
      version: 2,
    }
    server.on('POST /sales-orders/:id/fulfil', { body: fulfilled })
    const fulfil = await screen.findByRole('button', { name: 'Fulfil' })
    server.on('GET /sales-orders/:id', { body: fulfilled })
    await user.click(fulfil)
    expect(server.callsTo('POST /sales-orders/:id/fulfil')[0].body).toEqual({ version: 1 })
    expect(await screen.findByText('Fulfilled')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel order' })).not.toBeInTheDocument()
  })

  it('cancels a confirmed order after saying its reservation is released', async () => {
    const confirmed = confirmedOrder()
    const { server, user } = setup(confirmed)
    const cancelled = {
      ...confirmed,
      status: 'CANCELLED' as const,
      cancelledAt: '2026-10-08T12:00:00Z',
      version: 2,
    }
    server.on('POST /sales-orders/:id/cancel', { body: cancelled })
    const cancel = await screen.findByRole('button', { name: 'Cancel order' })
    server.on('GET /sales-orders/:id', { body: cancelled })
    await user.click(cancel)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/reserved stock is released/i)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Cancel order' }))
    expect(server.callsTo('POST /sales-orders/:id/cancel')[0].body).toEqual({ version: 1 })
    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
  })

  it('saves an edited draft with its version and a blank price meaning the list price', async () => {
    const { server, user } = setup(aSalesOrder({ version: 3 }))
    const saved = aSalesOrder({ version: 4, notes: 'x' })
    server.on('PUT /sales-orders/:id', { body: saved })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    const quantity = within(dialog).getByLabelText('Line 1 quantity')
    await user.clear(quantity)
    await user.type(quantity, '6')
    server.on('GET /sales-orders/:id', { body: saved })
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(server.callsTo('PUT /sales-orders/:id')[0].body).toEqual({
      customerId: 'p-deccan',
      warehouseId: 'w-main',
      currency: 'USD',
      notes: null,
      lines: [{ productId: 'pr-widget', quantity: 6, unitPrice: 12.5 }],
      version: 3,
    })
  })

  it('reloads the order when an edit hits a version conflict', async () => {
    const { server, user } = setup(aSalesOrder())
    server.on('PUT /sales-orders/:id', { status: 409, body: { detail: STALE } })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    const before = server.callsTo('GET /sales-orders/:id').length
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(await within(dialog).findByText(STALE)).toBeInTheDocument()
    await waitFor(() =>
      expect(server.callsTo('GET /sales-orders/:id').length).toBeGreaterThan(before),
    )
  })

  it('does not carry a failed action error into the cancel dialog', async () => {
    const { server, user } = setup(aSalesOrder())
    server.on('POST /sales-orders/:id/confirm', { status: 409, body: { detail: STALE } })
    await user.click(await screen.findByRole('button', { name: 'Confirm' }))
    await screen.findByText(STALE)
    await user.click(screen.getByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByText(STALE)).not.toBeInTheDocument()
  })

  it('offers no actions to readers', async () => {
    setup(aSalesOrder(), ['inventory.order.read'])
    await screen.findByRole('heading', { name: 'SO-00001' })
    expect(screen.queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
