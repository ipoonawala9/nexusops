import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCategory, anAgent, defaultSlaPolicies } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'] }))
    .on('GET /helpdesk/categories', (request) => ({
      body:
        request.query.get('archived') === 'true'
          ? [aCategory({ id: 'c-old', name: 'Old', archivedAt: '2026-10-01T00:00:00Z' })]
          : [
              aCategory(),
              aCategory({
                id: 'c-billing',
                name: 'Billing',
                position: 1,
                defaultAssignee: { id: 'u-ravi', name: 'Ravi Kumar' },
              }),
            ],
    }))
    .on('GET /helpdesk/sla-policies', { body: defaultSlaPolicies() })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
  return renderApp({ server, path: '/app/settings/helpdesk' })
}

describe('HelpDeskSettingsPage', () => {
  it('lists categories with their default assignee, and archived ones to restore', async () => {
    setup()
    const categories = await screen.findByRole('region', { name: 'Ticket categories' })
    const billing = (await within(categories).findByText('Billing')).closest('tr') as HTMLElement
    expect(within(billing).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(
      await within(categories).findByRole('button', { name: 'Restore Old' }),
    ).toBeInTheDocument()
  })

  it('creates a category that routes to a teammate', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/categories', { status: 201, body: aCategory({ id: 'c-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New category' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Warranty')
    await user.type(within(dialog).getByLabelText('Description'), 'Repairs under warranty')
    await user.selectOptions(within(dialog).getByLabelText('Default assignee'), 'u-ravi')
    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))
    expect(server.callsTo('POST /helpdesk/categories')[0].body).toEqual({
      name: 'Warranty',
      description: 'Repairs under warranty',
      defaultAssigneeId: 'u-ravi',
    })
  })

  it('shows a duplicate name from the server on the field', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/categories', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'name', message: 'A category with this name already exists.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'New category' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'billing')
    await user.click(within(dialog).getByRole('button', { name: 'Create category' }))
    expect(
      await within(dialog).findByText('A category with this name already exists.'),
    ).toBeInTheDocument()
  })

  it('archives a category after confirmation', async () => {
    const { server, user } = setup()
    server.on('POST /helpdesk/categories/:id/archive', { body: aCategory({ id: 'c-billing' }) })
    await user.click(await screen.findByRole('button', { name: 'Archive Billing' }))
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive' }),
    )
    expect(server.callsTo('POST /helpdesk/categories/:id/archive')[0].params.id).toBe('c-billing')
  })

  it('shows SLA targets most urgent first and edits one', async () => {
    const { server, user } = setup()
    const sla = await screen.findByRole('region', { name: 'SLA policies' })
    const rows = await within(sla).findAllByRole('row')
    expect(rows[1]).toHaveTextContent('Urgent')
    expect(rows[1]).toHaveTextContent('1 h')
    expect(rows[1]).toHaveTextContent('4 h')
    expect(rows[4]).toHaveTextContent('Low')
    server.on('PUT /helpdesk/sla-policies/:priority', {
      body: { priority: 'URGENT', firstResponseMinutes: 30, resolutionMinutes: 240, version: 1 },
    })
    await user.click(within(sla).getByRole('button', { name: 'Edit Urgent targets' }))
    const dialog = await screen.findByRole('dialog')
    const first = within(dialog).getByLabelText('First response (minutes)')
    await user.clear(first)
    await user.type(first, '30')
    await user.click(within(dialog).getByRole('button', { name: 'Save targets' }))
    const call = server.callsTo('PUT /helpdesk/sla-policies/:priority')[0]
    expect(call.params.priority).toBe('URGENT')
    expect(call.body).toEqual({ firstResponseMinutes: 30, resolutionMinutes: 240, version: 0 })
  })

  it('refuses a target of zero minutes before calling the server', async () => {
    const { server, user } = setup()
    const sla = await screen.findByRole('region', { name: 'SLA policies' })
    await user.click(await within(sla).findByRole('button', { name: 'Edit Low targets' }))
    const dialog = await screen.findByRole('dialog')
    const first = within(dialog).getByLabelText('First response (minutes)')
    await user.clear(first)
    await user.type(first, '0')
    await user.click(within(dialog).getByRole('button', { name: 'Save targets' }))
    expect(
      await within(dialog).findByText('Enter a number of minutes greater than 0.'),
    ).toBeInTheDocument()
    expect(server.callsTo('PUT /helpdesk/sla-policies/:priority')).toHaveLength(0)
  })
})
