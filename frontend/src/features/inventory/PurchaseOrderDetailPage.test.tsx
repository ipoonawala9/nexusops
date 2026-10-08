import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, aPurchaseOrder, aSummary, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { PurchaseOrderView } from '@/lib/api/types'

function setup(order: PurchaseOrderView, permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /purchase-orders/:id', { body: order })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-konkan', name: 'Konkan Supplies' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
  return renderApp({ server, path: `/app/inventory/purchase-orders/${order.id}` })
}

const ordered = aPurchaseOrder({
  status: 'ORDERED',
  orderedAt: '2026-10-08T10:00:00Z',
  version: 1,
  lines: [
    {
      id: 'pl-1',
      lineNo: 1,
      product: { id: 'pr-widget', sku: 'W-1', name: 'Widget', unit: 'each' },
      quantity: 10,
      receivedQuantity: 4,
      remainingQuantity: 6,
      unitCost: 2.5,
      lineTotal: 25,
    },
  ],
})

describe('PurchaseOrderDetailPage', () => {
  it('shows the lines and places a draft order', async () => {
    const { server, user } = setup(aPurchaseOrder())
    expect(await screen.findByRole('heading', { name: 'PO-00001' })).toBeInTheDocument()
    expect(screen.getByText('Draft')).toBeInTheDocument()
    const row = screen.getByText('W-1').closest('tr') as HTMLElement
    expect(within(row).getByText(/25\.00$/)).toBeInTheDocument()
    const placed = { ...ordered, lines: aPurchaseOrder().lines }
    // the page refetches after an action: the server now answers with the placed order
    server.on('POST /purchase-orders/:id/order', { body: placed })
    server.on('GET /purchase-orders/:id', { body: placed })
    await user.click(screen.getByRole('button', { name: 'Place order' }))
    expect(server.callsTo('POST /purchase-orders/:id/order')[0].body).toEqual({ version: 0 })
    expect(await screen.findByText('Ordered')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Place order' })).not.toBeInTheDocument()
  })

  it('receives part of what is still due', async () => {
    const { server, user } = setup(ordered)
    const received = { ...ordered, status: 'RECEIVED' as const, version: 2 }
    server.on('POST /purchase-orders/:id/receipts', { body: received })
    const receive = await screen.findByRole('button', { name: 'Receive' })
    // the page has loaded the ordered order; after the receipt it refetches and gets the received one
    server.on('GET /purchase-orders/:id', { body: received })
    await user.click(receive)
    const dialog = await screen.findByRole('dialog')
    const input = within(dialog).getByLabelText('Receive W-1 (6 due)')
    expect(input).toHaveValue('6')
    await user.clear(input)
    await user.type(input, '2')
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))
    expect(server.callsTo('POST /purchase-orders/:id/receipts')[0].body).toEqual({
      lines: [{ lineId: 'pl-1', quantity: 2 }],
      version: 1,
    })
    expect(await screen.findByText('Received')).toBeInTheDocument()
  })

  it('cancels after confirmation', async () => {
    const { server, user } = setup(aPurchaseOrder())
    const cancelled = aPurchaseOrder({
      status: 'CANCELLED',
      cancelledAt: '2026-10-08T11:00:00Z',
      version: 1,
    })
    server.on('POST /purchase-orders/:id/cancel', { body: cancelled })
    const cancel = await screen.findByRole('button', { name: 'Cancel order' })
    server.on('GET /purchase-orders/:id', { body: cancelled })
    await user.click(cancel)
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel order' }),
    )
    expect(await screen.findByText('Cancelled')).toBeInTheDocument()
  })

  it('shows a stale-version conflict and reloads', async () => {
    const { server, user } = setup(aPurchaseOrder())
    server.on('POST /purchase-orders/:id/order', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Place order' }))
    expect(
      await screen.findByText('This record was changed by someone else. Reload and try again.'),
    ).toBeInTheDocument()
    expect(server.callsTo('GET /purchase-orders/:id').length).toBeGreaterThan(1)
  })

  it('saves an edited draft with its version', async () => {
    const { server, user } = setup(aPurchaseOrder({ version: 3 }))
    const saved = aPurchaseOrder({ version: 4, notes: 'x' })
    server.on('PUT /purchase-orders/:id', { body: saved })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    const quantity = within(dialog).getByLabelText('Line 1 quantity')
    await user.clear(quantity)
    await user.type(quantity, '12')
    server.on('GET /purchase-orders/:id', { body: saved })
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(server.callsTo('PUT /purchase-orders/:id')[0].body).toEqual({
      supplierId: 'p-konkan',
      warehouseId: 'w-main',
      currency: 'USD',
      expectedOn: null,
      notes: null,
      lines: [{ productId: 'pr-widget', quantity: 12, unitCost: 2.5 }],
      version: 3,
    })
  })

  it('reloads the order when an edit hits a version conflict', async () => {
    const { server, user } = setup(aPurchaseOrder())
    server.on('PUT /purchase-orders/:id', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    const before = server.callsTo('GET /purchase-orders/:id').length
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(
      await within(dialog).findByText(
        'This record was changed by someone else. Reload and try again.',
      ),
    ).toBeInTheDocument()
    await waitFor(() =>
      expect(server.callsTo('GET /purchase-orders/:id').length).toBeGreaterThan(before),
    )
  })

  it('does not carry a failed action error into the next dialog', async () => {
    const { server, user } = setup(aPurchaseOrder())
    server.on('POST /purchase-orders/:id/order', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Place order' }))
    await screen.findByText('This record was changed by someone else. Reload and try again.')
    await user.click(screen.getByRole('button', { name: 'Cancel order' }))
    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).queryByText('This record was changed by someone else. Reload and try again.'),
    ).not.toBeInTheDocument()
  })

  it('closes the receipt dialog when a reload shows nothing is due', async () => {
    const { server, user } = setup(ordered)
    server.on('POST /purchase-orders/:id/receipts', {
      status: 409,
      body: { detail: 'This record was changed by someone else. Reload and try again.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Receive' }))
    const dialog = await screen.findByRole('dialog')
    server.on('GET /purchase-orders/:id', { body: { ...ordered, status: 'RECEIVED', version: 2 } })
    await user.click(within(dialog).getByRole('button', { name: 'Record receipt' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Received')).toBeInTheDocument()
  })

  it('offers no actions to readers', async () => {
    setup(aPurchaseOrder(), ['inventory.purchase.read'])
    await screen.findByRole('heading', { name: 'PO-00001' })
    expect(screen.queryByRole('button', { name: 'Place order' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
  })
})
