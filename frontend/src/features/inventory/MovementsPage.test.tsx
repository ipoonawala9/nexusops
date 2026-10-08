import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aMovement, aWarehouse, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/inventory/movements', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'], ...(permissions ? { permissions } : {}) }))
    .on('GET /inventory/movements', { body: pageOf([aMovement()]) })
    .on('GET /inventory/warehouses', {
      body: [aWarehouse(), aWarehouse({ id: 'w-pune', code: 'PUNE', name: 'Pune' })],
    })
  return renderApp({ server, path })
}

describe('MovementsPage', () => {
  it('lists the ledger with each product', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Movements' })).toBeInTheDocument()
    const row = (await screen.findByRole('link', { name: 'W-1' })).closest('tr') as HTMLElement
    expect(within(row).getByText('Receipt')).toBeInTheDocument()
    expect(within(row).getByRole('link', { name: 'PO-00001' })).toBeInTheDocument()
  })

  it('sends the filters from the URL and from the controls', async () => {
    const { server, user } = setup('/app/inventory/movements?kind=ISSUE')
    await screen.findByRole('link', { name: 'W-1' })
    expect(server.callsTo('GET /inventory/movements')[0].query.get('kind')).toBe('ISSUE')
    expect(screen.getByLabelText('Movement')).toHaveValue('ISSUE')
    await user.selectOptions(screen.getByLabelText('Warehouse'), 'w-pune')
    await user.selectOptions(screen.getByLabelText('Movement'), 'TRANSFER_IN')
    const last = server.callsTo('GET /inventory/movements').at(-1)?.query
    expect(last?.get('warehouseId')).toBe('w-pune')
    expect(last?.get('kind')).toBe('TRANSFER_IN')
    expect(last?.get('productId')).toBeNull()
  })

  it("shows one product's history under its name, without the product column", async () => {
    const { server } = setup('/app/inventory/movements?productId=pr-widget')
    expect(
      await screen.findByRole('heading', { name: 'Movements of W-1 · Widget' }),
    ).toBeInTheDocument()
    expect(server.callsTo('GET /inventory/movements')[0].query.get('productId')).toBe('pr-widget')
    expect(screen.queryByRole('columnheader', { name: 'Product' })).not.toBeInTheDocument()
  })

  it('pages through the ledger', async () => {
    const { server, user } = setup()
    server.on('GET /inventory/movements', { body: pageOf([aMovement()], 120, 0, 50) })
    await screen.findByRole('link', { name: 'W-1' })
    expect(await screen.findByText('Page 1 of 3')).toBeInTheDocument()
    server.on('GET /inventory/movements', { body: pageOf([aMovement()], 120, 1, 50) })
    await user.click(screen.getByRole('button', { name: 'Next page' }))
    const query = server.callsTo('GET /inventory/movements').at(-1)?.query
    expect(query?.get('page')).toBe('1')
    expect(query?.get('size')).toBe('50')
    expect(await screen.findByText('Page 2 of 3')).toBeInTheDocument()
  })

  it('says so when nothing matches', async () => {
    const { server } = setup('/app/inventory/movements?kind=ISSUE')
    server.on('GET /inventory/movements', { body: pageOf([]) })
    expect(await screen.findByText('No movements match these filters.')).toBeInTheDocument()
  })

  it('needs stock access', async () => {
    setup('/app/inventory/movements', ['inventory.purchase.read'])
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
  })
})
