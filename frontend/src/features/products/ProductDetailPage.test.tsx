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
    .on('GET /products/:id', { body: aProduct({ description: 'A very good widget' }) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: '/app/products/pr-widget' })
}

describe('ProductDetailPage', () => {
  it('shows the product with its record panels', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Widget' })).toBeInTheDocument()
    expect(screen.getByText('A very good widget')).toBeInTheDocument()
    expect(screen.getByText(/12\.50/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Activity' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Tasks' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Documents' })).toBeInTheDocument()
    expect(server.callsTo('GET /activities')[0].query.get('subjectType')).toBe('PRODUCT')
  })

  it('edits with the loaded version and archives', async () => {
    const { server, user } = setup()
    server
      .on('PUT /products/:id', { body: aProduct({ name: 'Widget XL', version: 1 }) })
      .on('POST /products/:id/archive', {
        body: aProduct({ name: 'Widget XL', archivedAt: '2026-10-06T00:00:00Z' }),
      })
    await user.click(await screen.findByRole('button', { name: 'Edit' }))
    const dialog = await screen.findByRole('dialog')
    const name = within(dialog).getByLabelText('Name')
    await user.clear(name)
    await user.type(name, 'Widget XL')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(server.callsTo('PUT /products/:id')[0].body).toMatchObject({
      name: 'Widget XL',
      version: 0,
      listPrice: 12.5,
    })
    expect(await screen.findByRole('heading', { name: 'Widget XL' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Archive' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive' }),
    )
    expect(await screen.findByText(/This product is archived/)).toBeInTheDocument()
  })
})
