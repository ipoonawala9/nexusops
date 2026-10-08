import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, aProductStock, aStockRow, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/stock', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /inventory/stock', {
      body: pageOf([
        aStockRow(),
        aStockRow({
          product: { id: 'pr-gadget', sku: 'G-1', name: 'Gadget', unit: 'box' },
          onHand: 3,
          reserved: 0,
          available: 3,
          onOrder: 5,
          ruleId: null,
          minQuantity: null,
          maxQuantity: null,
          belowMin: false,
        }),
      ]),
    })
    .on('GET /inventory/warehouses', {
      body: [aWarehouse(), aWarehouse({ id: 'w-pune', code: 'PUNE', name: 'Pune' })],
    })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /inventory/stock/products/:id', { body: aProductStock() })
  return renderApp({ server, path })
}

describe('StockPage', () => {
  it('lists stock per product and warehouse with the rule and what is on order', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'W-1' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Widget')).toBeInTheDocument()
    expect(within(row).getByText('MAIN')).toBeInTheDocument()
    expect(within(row).getByText('20 – 60')).toBeInTheDocument()
    expect(within(row).getByText('Below minimum')).toBeInTheDocument()
    const gadget = screen.getByRole('link', { name: 'G-1' }).closest('tr') as HTMLElement
    expect(within(gadget).getByText('5')).toBeInTheDocument()
    expect(within(gadget).getByText('No rule')).toBeInTheDocument()
  })

  it('filters by search, warehouse and below minimum, starting from the URL', async () => {
    const { server, user } = setup('/app/inventory/stock?belowMin=true')
    await screen.findByRole('link', { name: 'W-1' })
    expect(server.callsTo('GET /inventory/stock')[0].query.get('belowMin')).toBe('true')
    expect(screen.getByLabelText('Below minimum only')).toBeChecked()
    await user.selectOptions(screen.getByLabelText('Warehouse'), 'w-pune')
    await user.type(screen.getByLabelText('Search stock'), 'wid')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const last = server.callsTo('GET /inventory/stock').at(-1)?.query
    expect(last?.get('warehouseId')).toBe('w-pune')
    expect(last?.get('q')).toBe('wid')
  })

  it('counts stock and shows the new figures', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/adjustments', { body: aProductStock({ onHand: 15, available: 13 }) })
    await user.click(await screen.findByRole('button', { name: 'Count stock' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.type(within(dialog).getByLabelText('Counted quantity'), '15')
    await user.type(within(dialog).getByLabelText('Reason'), 'Cycle count')
    await user.click(within(dialog).getByRole('button', { name: 'Save count' }))
    expect(server.callsTo('POST /inventory/adjustments')[0].body).toEqual({
      productId: 'pr-widget',
      warehouseId: 'w-main',
      countedQuantity: 15,
      reason: 'Cycle count',
    })
    expect(await screen.findByText('Count saved.')).toBeInTheDocument()
  })

  it('explains a transfer shortage', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/transfers', {
      status: 409,
      body: {
        detail: 'Not enough stock.',
        shortages: [{ productId: 'pr-widget', sku: 'W-1', requested: 50, available: 10 }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Transfer stock' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('From'), 'w-main')
    await user.selectOptions(within(dialog).getByLabelText('To'), 'w-pune')
    await user.type(within(dialog).getByLabelText('Quantity'), '50')
    await user.click(within(dialog).getByRole('button', { name: 'Transfer' }))
    expect(
      await within(dialog).findByText('Not enough stock: W-1 needs 50, 10 available.'),
    ).toBeInTheDocument()
  })

  it('hides the stock actions from readers', async () => {
    setup('/app/inventory/stock', ['inventory.stock.read'])
    await screen.findByRole('link', { name: 'W-1' })
    expect(screen.queryByRole('button', { name: 'Count stock' })).not.toBeInTheDocument()
  })
})
