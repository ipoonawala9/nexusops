import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aStage, defaultStages } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] })).on(
    'GET /crm/pipeline/stages',
    { body: defaultStages() },
  )
  return renderApp({ server, path: '/app/settings/pipeline' })
}

async function stageRows() {
  return within(await screen.findByRole('list', { name: 'Stages' })).getAllByRole('listitem')
}

describe('PipelineSettingsPage', () => {
  it('lists the stages in order with fixed Won and Lost stages', async () => {
    setup()
    const rows = await stageRows()
    expect(rows.map((r) => within(r).getByRole('textbox', { name: /Stage name/ }))).toHaveLength(6)
    const won = rows[4]
    expect(within(won).getByText('Won stage')).toBeInTheDocument()
    expect(within(won).queryByRole('button', { name: /Delete/ })).not.toBeInTheDocument()
    expect(within(rows[0]).getByRole('button', { name: 'Move Prospecting up' })).toBeDisabled()
  })

  it('adds a stage', async () => {
    const { server, user } = setup()
    server.on('POST /crm/pipeline/stages', {
      status: 201,
      body: aStage({ id: 's-demo', name: 'Demo', probability: 40, position: 4 }),
    })
    await stageRows()
    await user.type(screen.getByLabelText('New stage name'), 'Demo')
    await user.clear(screen.getByLabelText('New stage probability (%)'))
    await user.type(screen.getByLabelText('New stage probability (%)'), '40')
    await user.click(screen.getByRole('button', { name: 'Add stage' }))
    expect(server.callsTo('POST /crm/pipeline/stages')[0].body).toEqual({ name: 'Demo', probability: 40 })
  })

  it('saves a renamed stage with its version', async () => {
    const { server, user } = setup()
    server.on('PUT /crm/pipeline/stages/:id', { body: aStage({ name: 'Discovery', version: 1 }) })
    const rows = await stageRows()
    const name = within(rows[0]).getByRole('textbox', { name: 'Stage name Prospecting' })
    await user.clear(name)
    await user.type(name, 'Discovery')
    await user.click(within(rows[0]).getByRole('button', { name: 'Save' }))
    expect(server.callsTo('PUT /crm/pipeline/stages/:id')[0].body).toEqual({
      name: 'Discovery',
      probability: 10,
      version: 0,
    })
  })

  it('moves an open stage down by sending the new order', async () => {
    const { server, user } = setup()
    server.on('PUT /crm/pipeline/stages/order', { body: defaultStages() })
    await stageRows()
    await user.click(screen.getByRole('button', { name: 'Move Prospecting down' }))
    expect(server.callsTo('PUT /crm/pipeline/stages/order')[0].body).toEqual({
      stageIds: ['s-qualification', 's-prospecting', 's-proposal', 's-negotiation'],
    })
  })

  it('shows why a stage cannot be deleted', async () => {
    const { server, user } = setup()
    server.on('DELETE /crm/pipeline/stages/:id', {
      status: 409,
      body: { detail: "Move this stage's opportunities first." },
    })
    const rows = await stageRows()
    await user.click(within(rows[0]).getByRole('button', { name: 'Delete Prospecting' }))
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Delete' }))
    expect(await screen.findByText("Move this stage's opportunities first.")).toBeInTheDocument()
  })
})
