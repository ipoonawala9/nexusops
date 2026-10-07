import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aParty, aTask, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /parties/:id', { body: aParty() })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([aTask()]) })
    .on('GET /tasks/assignees', {
      body: [{ id: 'u-ada', name: 'Ada Lovelace', email: 'ada@acme.test' }],
    })
  return renderApp({ server, path: '/app/directory/p-acme' })
}

describe('SubjectTasksPanel', () => {
  it("lists the record's tasks", async () => {
    const { server } = setup()
    const panel = within(await screen.findByRole('region', { name: 'Tasks' }))
    expect(await panel.findByText('Send quote')).toBeInTheDocument()
    const query = server.callsTo('GET /tasks')[0].query
    expect(query.get('subjectType')).toBe('PARTY')
    expect(query.get('subjectId')).toBe('p-acme')
  })

  it('creates a task attached to the record', async () => {
    const { server, user } = setup()
    server.on('POST /tasks', { status: 201, body: aTask({ id: 't-2', title: 'Follow up' }) })
    const panel = within(await screen.findByRole('region', { name: 'Tasks' }))
    await user.click(panel.getByRole('button', { name: 'New task' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText('For Acme')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Title'), 'Follow up')
    await user.click(within(dialog).getByRole('button', { name: 'Create task' }))
    expect(server.callsTo('POST /tasks')[0].body).toMatchObject({
      title: 'Follow up',
      subjectType: 'PARTY',
      subjectId: 'p-acme',
    })
  })

  it('is hidden without task permission', async () => {
    setup(['directory.party.read'])
    await screen.findByRole('heading', { name: 'Acme' })
    expect(screen.queryByRole('region', { name: 'Tasks' })).not.toBeInTheDocument()
  })
})
