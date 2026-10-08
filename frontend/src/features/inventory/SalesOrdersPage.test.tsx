import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, aSalesOrder, aSalesSummary, aSummary, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/sales-orders') {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'] }))
    .on('GET /sales-orders', {
      body: pageOf([
        aSalesSummary({ status: 'CONFIRMED' }),
        aSalesSummary({ id: 'so-2', number: 'SO-00002' }),
      ]),
    })
    .on('GET /sales-orders/:id', { body: aSalesOrder() })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-deccan', name: 'Deccan Retail' })]) })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path })
}

describe('SalesOrdersPage', () => {
  it('lists sales orders and filters by status from the URL', async () => {
    const { server } = setup('/app/inventory/sales-orders?status=CONFIRMED')
    const row = (await screen.findByRole('link', { name: 'SO-00001' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Deccan Retail')).toBeInTheDocument()
    expect(within(row).getByText('Confirmed')).toBeInTheDocument()
    expect(server.callsTo('GET /sales-orders')[0].query.get('status')).toBe('CONFIRMED')
  })

  it('creates a draft priced from the catalog when the price is left blank', async () => {
    const { server, user, router } = setup()
    server.on('POST /sales-orders', { status: 201, body: aSalesOrder({ id: 'so-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New sales order' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Customer'), 'p-deccan')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('Line 1 product'), 'pr-widget')
    await user.type(within(dialog).getByLabelText('Line 1 quantity'), '4')
    await user.click(within(dialog).getByRole('button', { name: 'Create sales order' }))
    expect(server.callsTo('POST /sales-orders')[0].body).toEqual({
      customerId: 'p-deccan',
      warehouseId: 'w-main',
      currency: null,
      notes: null,
      lines: [{ productId: 'pr-widget', quantity: 4, unitPrice: null }],
    })
    await screen.findByRole('heading', { name: 'SO-00001' })
    expect(router.state.location.pathname).toBe('/app/inventory/sales-orders/so-new')
  })
})
