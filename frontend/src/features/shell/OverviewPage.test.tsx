import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aTask, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('OverviewPage', () => {
  it('shows my open tasks', async () => {
    const server = fakeServer()
    signedIn(server, testProfile()).on('GET /tasks', {
      body: pageOf([aTask({ dueOn: '2030-01-15' })]),
    })
    renderApp({ server, path: '/app' })
    const card = within(await screen.findByRole('region', { name: 'My open tasks' }))
    expect(await card.findByText('Send quote')).toBeInTheDocument()
    expect(card.getByRole('link', { name: 'All tasks' })).toHaveAttribute('href', '/app/tasks')
    const query = server.callsTo('GET /tasks')[0].query
    expect(query.get('assignee')).toBe('me')
    expect(query.get('size')).toBe('5')
  })
})
