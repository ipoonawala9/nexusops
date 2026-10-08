import { screen, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aProduct,
  aPurchaseOrder,
  aReorderRule,
  aSuggestion,
  aSummary,
  aWarehouse,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

const GADGET = { id: 'pr-gadget', sku: 'G-1', name: 'Gadget', unit: 'box' }

function setup(permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /inventory/reorder-suggestions', {
      body: [
        aSuggestion(),
        aSuggestion({
          ruleId: 'rr-2',
          product: GADGET,
          supplier: null,
          daysOfCover: null,
          suggestedQuantity: 7,
          explanation:
            '3 available, 0 on order, below the minimum of 5; no usage in the last 30 days; order 7 to reach 10',
        }),
      ],
    })
    .on('GET /inventory/reorder-rules', { body: [aReorderRule()] })
    .on('GET /inventory/warehouses', { body: [aWarehouse()] })
    .on('GET /products', { body: pageOf([aProduct()]) })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-konkan', name: 'Konkan Supplies' })]) })
  return renderApp({ server, path: '/app/inventory/reorder' })
}

describe('ReorderPage', () => {
  it('explains each suggestion', async () => {
    setup()
    expect(
      await screen.findByText(
        '12 available, 0 on order, below the minimum of 20; 38 used in the last 30 days (about 9 days of cover); order 48 to reach 60',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText(/no usage in the last 30 days/)).toBeInTheDocument()
  })

  it('turns the selected suggestions into draft purchase orders', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/reorder-suggestions/purchase-orders', {
      status: 201,
      body: { orders: [aPurchaseOrder({ id: 'po-9', number: 'PO-00009' })] },
    })
    await user.click(await screen.findByLabelText('Select W-1 at MAIN'))
    const quantity = screen.getByLabelText('Order quantity for W-1 at MAIN')
    expect(quantity).toHaveValue('48')
    await user.clear(quantity)
    await user.type(quantity, '50')
    expect(screen.getByLabelText('Select G-1 at MAIN')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: 'Create purchase orders' }))
    expect(server.callsTo('POST /inventory/reorder-suggestions/purchase-orders')[0].body).toEqual({
      items: [{ productId: 'pr-widget', warehouseId: 'w-main', quantity: 50 }],
    })
    expect(await screen.findByRole('link', { name: 'PO-00009' })).toHaveAttribute(
      'href',
      '/app/inventory/purchase-orders/po-9',
    )
  })

  it('saves a new rule', async () => {
    const { server, user } = setup()
    server.on('PUT /inventory/reorder-rules', { body: aReorderRule({ id: 'rr-3' }) })
    await user.click(await screen.findByRole('button', { name: 'New rule' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.type(within(dialog).getByLabelText('Minimum'), '20')
    await user.type(within(dialog).getByLabelText('Maximum'), '60')
    await user.selectOptions(within(dialog).getByLabelText('Preferred supplier'), 'p-konkan')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(server.callsTo('PUT /inventory/reorder-rules')[0].body).toEqual({
      productId: 'pr-widget',
      warehouseId: 'w-main',
      minQuantity: 20,
      maxQuantity: 60,
      supplierId: 'p-konkan',
    })
  })

  it('needs a maximum above the minimum', async () => {
    const { user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New rule' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Product'), 'pr-widget')
    await user.selectOptions(within(dialog).getByLabelText('Warehouse'), 'w-main')
    await user.type(within(dialog).getByLabelText('Minimum'), '20')
    await user.type(within(dialog).getByLabelText('Maximum'), '20')
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(
      await within(dialog).findByText('Enter a maximum above the minimum.'),
    ).toBeInTheDocument()
  })

  it('deletes a rule after confirmation', async () => {
    const { server, user } = setup()
    server.on('DELETE /inventory/reorder-rules/:id', { status: 204 })
    await user.click(await screen.findByRole('button', { name: 'Delete rule for W-1 at MAIN' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete rule' }),
    )
    expect(server.callsTo('DELETE /inventory/reorder-rules/:id')[0].params.id).toBe('rr-1')
  })

  it('edits a rule with its version and reloads the rules after a conflict', async () => {
    const { server, user } = setup()
    server.on('PUT /inventory/reorder-rules', {
      status: 409,
      body: {
        status: 409,
        title: 'Conflict',
        detail: 'This record was changed by someone else. Reload and try again.',
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Edit rule for W-1 at MAIN' }))
    const dialog = await screen.findByRole('dialog')
    const maximum = within(dialog).getByLabelText('Maximum')
    await user.clear(maximum)
    await user.type(maximum, '80')
    const loads = server.callsTo('GET /inventory/reorder-rules').length
    await user.click(within(dialog).getByRole('button', { name: 'Save rule' }))
    expect(
      await within(dialog).findByText(
        'This record was changed by someone else. Reload and try again.',
      ),
    ).toBeInTheDocument()
    expect(server.callsTo('PUT /inventory/reorder-rules')[0].body).toEqual({
      productId: 'pr-widget',
      warehouseId: 'w-main',
      minQuantity: 20,
      maxQuantity: 80,
      supplierId: 'p-konkan',
      version: 0,
    })
    await vi.waitFor(() =>
      expect(server.callsTo('GET /inventory/reorder-rules').length).toBeGreaterThan(loads),
    )
  })

  it('lets a stock reader see suggestions but not act on them', async () => {
    setup(['inventory.stock.read'])
    await screen.findByText(/38 used in the last 30 days/)
    expect(screen.queryByRole('button', { name: 'Create purchase orders' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New rule' })).not.toBeInTheDocument()
  })
})
