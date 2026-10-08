import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aProduct,
  aPurchaseOrder,
  aPurchaseSummary,
  aSummary,
  aWarehouse,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/purchase-orders', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /purchase-orders', {
      body: pageOf([
        aPurchaseSummary({ status: 'ORDERED', expectedOn: '2026-11-15' }),
        aPurchaseSummary({ id: 'po-2', number: 'PO-00002', total: 7.5, lineCount: 3 }),
      ]),
    })
    .on('GET /purchase-orders/:id', { body: aPurchaseOrder() })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-konkan', name: 'Konkan Supplies' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path })
}

describe('PurchaseOrdersPage', () => {
  it('lists purchase orders with supplier, status and total', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'PO-00001' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Konkan Supplies')).toBeInTheDocument()
    expect(within(row).getByText('Ordered')).toBeInTheDocument()
    expect(within(row).getByText(/25\.00/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'PO-00001' })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders/po-1',
    )
  })

  it('filters by status from the URL and by search', async () => {
    const { server, user } = setup('/app/inventory/purchase-orders?status=ORDERED')
    await screen.findByRole('link', { name: 'PO-00001' })
    expect(server.callsTo('GET /purchase-orders')[0].query.get('status')).toBe('ORDERED')
    await user.type(screen.getByLabelText('Search purchase orders'), '00002')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    expect(server.callsTo('GET /purchase-orders').at(-1)?.query.get('q')).toBe('00002')
  })

  it('creates a draft and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /purchase-orders', { status: 201, body: aPurchaseOrder({ id: 'po-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New purchase order' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Supplier'), 'p-konkan')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('Line 1 product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Line 1 quantity'), '10')
    await user.type(within(dialog).getByLabelText('Line 1 unit cost'), '2.5')
    await user.click(within(dialog).getByRole('button', { name: 'Create purchase order' }))
    expect(server.callsTo('POST /purchase-orders')[0].body).toEqual({
      supplierId: 'p-konkan',
      warehouseId: 'w-main',
      currency: null,
      expectedOn: null,
      notes: null,
      lines: [{ productId: 'pr-widget', quantity: 10, unitCost: 2.5 }],
    })
    await screen.findByRole('heading', { name: 'PO-00001' })
    expect(router.state.location.pathname).toBe('/app/inventory/purchase-orders/po-new')
  })

  it('shows line errors from the server next to the line', async () => {
    const { server, user } = setup()
    server.on('POST /purchase-orders', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'lines[0].productId', message: "Services don't carry stock." }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New purchase order' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Supplier'), 'p-konkan')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('Line 1 product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Line 1 quantity'), '1')
    await user.type(within(dialog).getByLabelText('Line 1 unit cost'), '1')
    await user.click(within(dialog).getByRole('button', { name: 'Create purchase order' }))
    expect(await within(dialog).findByText("Services don't carry stock.")).toBeInTheDocument()
  })

  it('hides New purchase order from readers', async () => {
    setup('/app/inventory/purchase-orders', ['inventory.purchase.read'])
    await screen.findByRole('link', { name: 'PO-00001' })
    expect(screen.queryByRole('button', { name: 'New purchase order' })).not.toBeInTheDocument()
  })
})
