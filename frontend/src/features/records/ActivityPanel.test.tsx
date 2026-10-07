import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aParty, anActivity, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(
  permissions: string[] = [...ALL_TENANT_PERMISSIONS],
  archivedAt: string | null = null,
) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /parties/:id', { body: aParty({ archivedAt }) })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([anActivity()]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
  return renderApp({ server, path: '/app/directory/p-acme' })
}

describe('ActivityPanel', () => {
  it('shows the timeline for the subject', async () => {
    const { server } = setup()
    const panel = within(await screen.findByRole('region', { name: 'Activity' }))
    expect(await panel.findByText('Kick-off call booked')).toBeInTheDocument()
    expect(panel.getByText('Thursday 10:00')).toBeInTheDocument()
    expect(panel.getByText(/Ada Lovelace/)).toBeInTheDocument()
    const query = server.callsTo('GET /activities')[0].query
    expect(query.get('subjectType')).toBe('PARTY')
    expect(query.get('subjectId')).toBe('p-acme')
  })

  it('logs a call and refreshes the timeline', async () => {
    const { server, user } = setup()
    server.on('POST /activities', {
      status: 201,
      body: anActivity({ id: 'a-2', type: 'CALL', summary: 'Called back' }),
    })
    const panel = within(await screen.findByRole('region', { name: 'Activity' }))
    await user.selectOptions(panel.getByLabelText('Type'), 'CALL')
    await user.type(panel.getByLabelText('Summary'), 'Called back')
    await user.click(panel.getByRole('button', { name: 'Log activity' }))
    expect(server.callsTo('POST /activities')[0].body).toEqual({
      subjectType: 'PARTY',
      subjectId: 'p-acme',
      type: 'CALL',
      summary: 'Called back',
      body: null,
    })
    await screen.findByText('Activity logged.')
    expect(server.callsTo('GET /activities').length).toBeGreaterThan(1)
    expect(panel.getByLabelText('Summary')).toHaveValue('')
  })

  it('requires a summary', async () => {
    const { server, user } = setup()
    const panel = within(await screen.findByRole('region', { name: 'Activity' }))
    await user.click(panel.getByRole('button', { name: 'Log activity' }))
    expect(await panel.findByText('Required.')).toBeInTheDocument()
    expect(server.callsTo('POST /activities')).toHaveLength(0)
  })

  it('hides the form without permission or on archived records', async () => {
    setup(['directory.party.read'])
    const panel = within(await screen.findByRole('region', { name: 'Activity' }))
    await panel.findByText('Kick-off call booked')
    expect(panel.queryByRole('button', { name: 'Log activity' })).not.toBeInTheDocument()
  })

  it('shows an empty state', async () => {
    const { server } = setup([...ALL_TENANT_PERMISSIONS], '2026-10-06T00:00:00Z')
    server.on('GET /activities', { body: pageOf([]) })
    const panel = within(await screen.findByRole('region', { name: 'Activity' }))
    expect(await panel.findByText('No activity yet.')).toBeInTheDocument()
    expect(panel.queryByRole('button', { name: 'Log activity' })).not.toBeInTheDocument()
  })
})
