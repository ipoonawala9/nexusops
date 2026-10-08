import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aWarehouse } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['INVENTORY'] })).on(
    'GET /inventory/warehouses',
    (request) => ({
      body:
        request.query.get('archived') === 'true'
          ? [
              aWarehouse({
                id: 'w-old',
                code: 'OLD',
                name: 'Old shed',
                archivedAt: '2026-10-01T00:00:00Z',
              }),
            ]
          : [aWarehouse()],
    }),
  )
  return renderApp({ server, path: '/app/settings/warehouses' })
}

describe('WarehousesSettingsPage', () => {
  it('lists active and archived warehouses', async () => {
    setup()
    expect(await screen.findByText('Main warehouse')).toBeInTheDocument()
    expect(await screen.findByText('Old shed')).toBeInTheDocument()
  })

  it('adds a warehouse', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/warehouses', {
      status: 201,
      body: aWarehouse({ id: 'w-pune', code: 'PUNE', name: 'Pune' }),
    })
    await user.click(await screen.findByRole('button', { name: 'New warehouse' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Code'), 'PUNE')
    await user.type(within(dialog).getByLabelText('Name'), 'Pune')
    await user.click(within(dialog).getByRole('button', { name: 'Add warehouse' }))
    expect(server.callsTo('POST /inventory/warehouses')[0].body).toEqual({
      code: 'PUNE',
      name: 'Pune',
      address: null,
    })
  })

  it('shows why a warehouse cannot be archived', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/warehouses/:id/archive', {
      status: 409,
      body: { detail: "Move or count out this warehouse's stock first." },
    })
    await user.click(await screen.findByRole('button', { name: 'Archive MAIN' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive' }),
    )
    expect(
      await screen.findByText("Move or count out this warehouse's stock first."),
    ).toBeInTheDocument()
  })

  it('maps a taken code onto the code field', async () => {
    const { server, user } = setup()
    server.on('POST /inventory/warehouses', {
      status: 409,
      body: {
        detail: 'Another warehouse already uses this code.',
        errors: [{ field: 'code', message: 'Another warehouse already uses this code.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New warehouse' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Code'), 'MAIN')
    await user.type(within(dialog).getByLabelText('Name'), 'Again')
    await user.click(within(dialog).getByRole('button', { name: 'Add warehouse' }))
    expect(
      await within(dialog).findByText('Another warehouse already uses this code.'),
    ).toBeInTheDocument()
  })
})
