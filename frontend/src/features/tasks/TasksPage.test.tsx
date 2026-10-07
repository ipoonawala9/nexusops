import { fireEvent, screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aTask, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

const ON_ACME = aTask({
  id: 't-2',
  title: 'Renew contract',
  priority: 'HIGH',
  dueOn: '2020-01-01',
  subject: { type: 'PARTY', id: 'p-acme', label: 'Acme', archived: false },
})
const RESTRICTED = aTask({
  id: 't-3',
  title: 'Restricted one',
  assignee: { id: 'u-other', name: 'Other Person' },
  subject: { type: 'PARTY', id: 'p-x', label: null, archived: false },
})

function setup(permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ permissions }))
    .on('GET /tasks', { body: pageOf([aTask(), ON_ACME, RESTRICTED]) })
    .on('GET /tasks/assignees', {
      body: [
        { id: 'u-ada', name: 'Ada Lovelace', email: 'ada@acme.test' },
        { id: 'u-grace', name: 'Grace Hopper', email: 'grace@acme.test' },
      ],
    })
  return renderApp({ server, path: '/app/tasks' })
}

describe('TasksPage', () => {
  it('shows my open tasks by default with record links and overdue dates', async () => {
    const { server } = setup()
    const row = (await screen.findByText('Renew contract')).closest('tr') as HTMLElement
    expect(within(row).getByRole('link', { name: 'Acme' })).toHaveAttribute(
      'href',
      '/app/directory/p-acme',
    )
    expect(within(row).getByText('Overdue')).toBeInTheDocument()
    expect(within(row).getByText('High')).toBeInTheDocument()
    expect(screen.getByText('Restricted record')).toBeInTheDocument()
    const query = server.callsTo('GET /tasks')[0].query
    expect(query.get('assignee')).toBe('me')
    expect(query.get('status')).toBe('OPEN,IN_PROGRESS')
  })

  it('switches views and status filters', async () => {
    const { server, user } = setup()
    await screen.findByText('Renew contract')
    await user.selectOptions(screen.getByLabelText('Show'), 'all')
    await user.selectOptions(screen.getByLabelText('Status'), 'done')
    const last = server.callsTo('GET /tasks').at(-1)?.query
    expect(last?.get('assignee')).toBeNull()
    expect(last?.get('status')).toBe('DONE')
  })

  it('creates a task assigned to a teammate', async () => {
    const { server, user } = setup()
    server.on('POST /tasks', { status: 201, body: aTask({ id: 't-9', title: 'Call Grace' }) })
    await user.click(await screen.findByRole('button', { name: 'New task' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Call Grace')
    await user.selectOptions(within(dialog).getByLabelText('Priority'), 'URGENT')
    // jsdom's date input only accepts a complete value, so set it in one change event
    fireEvent.change(within(dialog).getByLabelText('Due date'), { target: { value: '2030-01-15' } })
    await within(dialog).findByRole('option', { name: 'Grace Hopper' })
    await user.selectOptions(within(dialog).getByLabelText('Assignee'), 'u-grace')
    await user.click(within(dialog).getByRole('button', { name: 'Create task' }))
    expect(server.callsTo('POST /tasks')[0].body).toEqual({
      title: 'Call Grace',
      description: null,
      priority: 'URGENT',
      dueOn: '2030-01-15',
      assigneeId: 'u-grace',
      subjectType: null,
      subjectId: null,
    })
    expect(await screen.findByText('Task created.')).toBeInTheDocument()
  })

  it('edits a task with its version', async () => {
    const { server, user } = setup()
    server.on('PUT /tasks/:id', { body: aTask({ title: 'Send revised quote', version: 1 }) })
    await user.click(await screen.findByRole('button', { name: 'Edit Send quote' }))
    const dialog = await screen.findByRole('dialog')
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Send revised quote')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(server.callsTo('PUT /tasks/:id')[0].body).toMatchObject({
      title: 'Send revised quote',
      version: 0,
    })
  })

  it('keeps the current assignee when the assignee list loads after the dialog opens', async () => {
    const { server, user } = setup()
    server.on('PUT /tasks/:id', { body: aTask({ version: 1 }) })
    await user.click(await screen.findByRole('button', { name: 'Edit Send quote' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('option', { name: 'Grace Hopper' })
    expect(within(dialog).getByLabelText('Assignee')).toHaveValue('u-ada')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    expect(server.callsTo('PUT /tasks/:id')[0].body).toMatchObject({ assigneeId: 'u-ada' })
  })

  it('changes status from the list; readers can only move their own tasks', async () => {
    const { server, user } = setup(['collaboration.task.read'])
    server.on('POST /tasks/:id/status', { body: aTask({ status: 'DONE' }) })
    await screen.findByText('Renew contract')
    expect(screen.queryByRole('button', { name: 'New task' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Status of Restricted one')).toBeDisabled()
    await user.selectOptions(screen.getByLabelText('Status of Send quote'), 'DONE')
    expect(server.callsTo('POST /tasks/:id/status')[0]).toMatchObject({
      params: { id: 't-1' },
      body: { status: 'DONE' },
    })
  })

  it('shows an empty state', async () => {
    const { server } = setup()
    server.on('GET /tasks', { body: pageOf([]) })
    expect(await screen.findByText('No tasks here.')).toBeInTheDocument()
  })
})
