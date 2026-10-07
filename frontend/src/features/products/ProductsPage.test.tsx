import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aProduct, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /products', {
      body: pageOf([aProduct(), aProduct({ id: 'pr-setup', sku: 'S-1', name: 'Setup', kind: 'SERVICE', listPrice: null, currency: null })]),
    })
    .on('GET /products/:id', { body: aProduct() })
  return renderApp({ server, path: '/app/products' })
}

describe('ProductsPage', () => {
  it('lists products with SKU, kind and price', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'Widget' })).closest('tr') as HTMLElement
    expect(within(row).getByText('W-1')).toBeInTheDocument()
    expect(within(row).getByText('Goods')).toBeInTheDocument()
    expect(within(row).getByText(/12\.50/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Widget' })).toHaveAttribute('href', '/app/products/pr-widget')
  })

  it('filters by kind, status and search', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Widget' })
    await user.selectOptions(screen.getByLabelText('Kind'), 'SERVICE')
    await user.selectOptions(screen.getByLabelText('Status'), 'archived')
    await user.type(screen.getByLabelText('Search products'), 'set')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const last = server.callsTo('GET /products').at(-1)?.query
    expect(last?.get('kind')).toBe('SERVICE')
    expect(last?.get('archived')).toBe('true')
    expect(last?.get('q')).toBe('set')
  })

  it('creates a product and shows SKU conflicts on the field', async () => {
    const { server, user, router } = setup()
    server.on('POST /products', (req) =>
      (req.body as { sku: string }).sku === 'W-1'
        ? { status: 409, body: { detail: 'Another product already uses this SKU.', errors: [{ field: 'sku', message: 'Another product already uses this SKU.' }] } }
        : { status: 201, body: aProduct({ id: 'pr-new', sku: 'G-1', name: 'Gadget' }) },
    )
    await user.click(await screen.findByRole('button', { name: 'New product' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('SKU'), 'W-1')
    await user.type(within(dialog).getByLabelText('Name'), 'Gadget')
    await user.type(within(dialog).getByLabelText('List price'), '9.99')
    await user.click(within(dialog).getByRole('button', { name: 'Create product' }))
    expect(await within(dialog).findByText('Another product already uses this SKU.')).toBeInTheDocument()
    const sku = within(dialog).getByLabelText('SKU')
    await user.clear(sku)
    await user.type(sku, 'G-1')
    await user.click(within(dialog).getByRole('button', { name: 'Create product' }))
    expect(server.callsTo('POST /products')[1].body).toEqual({
      sku: 'G-1',
      name: 'Gadget',
      description: null,
      kind: 'GOODS',
      unit: 'each',
      listPrice: 9.99,
      currency: null,
    })
    await screen.findByText('Gadget added.')
    expect(router.state.location.pathname).toBe('/app/products/pr-new')
  })

  it('validates the price before sending', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New product' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('SKU'), 'X-1')
    await user.type(within(dialog).getByLabelText('Name'), 'X')
    await user.type(within(dialog).getByLabelText('List price'), '-3')
    await user.click(within(dialog).getByRole('button', { name: 'Create product' }))
    expect(await within(dialog).findByText('Enter a price of 0 or more.')).toBeInTheDocument()
    expect(server.callsTo('POST /products')).toHaveLength(0)
  })

  it('hides the create button from readers', async () => {
    setup(['catalog.product.read'])
    await screen.findByRole('link', { name: 'Widget' })
    expect(screen.queryByRole('button', { name: 'New product' })).not.toBeInTheDocument()
  })
})
