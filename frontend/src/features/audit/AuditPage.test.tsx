import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const EVENT = {
  id: 'e-1',
  occurredAt: '2026-10-06T09:30:00Z',
  actorType: 'USER',
  actorId: '0192aaaa-bbbb-7ccc-8ddd-eeeeffff0000',
  action: 'RoleCreated',
  entityType: 'Role',
  entityId: 'r-1',
  ip: '10.0.0.1',
  userAgent: 'Firefox',
  requestId: 'req-9',
  correlationId: 'req-9',
  before: null,
  after: { name: 'Support' },
  metadata: null,
}

describe('AuditPage', () => {
  it('lists events and expands their details', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', {
      body: { items: [EVENT], page: 0, size: 25, total: 1 },
    })
    const { user } = renderApp({ server, path: '/app/audit' })
    expect(await screen.findByText('RoleCreated')).toBeInTheDocument()
    expect(screen.getByText(/0192aaaa/)).toBeInTheDocument()
    const toggle = screen.getByRole('button', { name: 'Show details for RoleCreated' })
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText(/"name": "Support"/)).toBeInTheDocument()
    expect(screen.getByText(/req-9/)).toBeInTheDocument()
  })

  it('filters by action and date range', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', { body: { items: [], page: 0, size: 25, total: 0 } })
    const { user } = renderApp({ server, path: '/app/audit' })
    expect(await screen.findByText('No events match these filters.')).toBeInTheDocument()
    await user.type(screen.getByLabelText('Action'), 'LoginFailed')
    await user.type(screen.getByLabelText('From'), '2026-10-01')
    await user.type(screen.getByLabelText('To'), '2026-10-06')
    await user.click(screen.getByRole('button', { name: 'Apply filters' }))
    const query = server.callsTo('GET /audit-events').at(-1)?.query
    expect(query?.get('action')).toBe('LoginFailed')
    expect(query?.get('from')).toBe(new Date('2026-10-01T00:00:00').toISOString())
    expect(query?.get('to')).toBe(new Date('2026-10-06T23:59:59.999').toISOString())
    expect(query?.get('size')).toBe('25')
  })

  it('shows a server field error', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', {
      status: 400,
      body: {
        detail: 'Bad Request',
        errors: [{ field: 'from', message: "'from' must not be after 'to'." }],
      },
    })
    renderApp({ server, path: '/app/audit?from=2026-10-09&to=2026-10-01' })
    expect(await screen.findByText("'from' must not be after 'to'.")).toBeInTheDocument()
  })

  it('ignores dates in the URL that are not dates', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', {
      body: { items: [EVENT], page: 0, size: 25, total: 1 },
    })
    renderApp({ server, path: '/app/audit?from=garbage&to=2026-13-45&action=RoleCreated' })
    expect(await screen.findByRole('cell', { name: 'RoleCreated' })).toBeInTheDocument()
    const query = server.callsTo('GET /audit-events')[0].query
    expect(query.has('from')).toBe(false)
    expect(query.has('to')).toBe(false)
    expect(query.get('action')).toBe('RoleCreated')
  })

  it('offers the first page when a later page is empty', async () => {
    const server = fakeServer()
    signedIn(server).on('GET /audit-events', (req) => ({
      body:
        req.query.get('page') === '0'
          ? { items: [EVENT], page: 0, size: 25, total: 1 }
          : { items: [], page: 4, size: 25, total: 1 },
    }))
    const { user, router } = renderApp({ server, path: '/app/audit?action=RoleCreated&page=4' })
    expect(await screen.findByText('No events match these filters.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Back to first page' }))
    expect(await screen.findByRole('cell', { name: 'RoleCreated' })).toBeInTheDocument()
    expect(router.state.location.search).toBe('?action=RoleCreated')
  })

  it('needs the audit permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: [] }))
    renderApp({ server, path: '/app/audit' })
    expect(
      await screen.findByRole('heading', { name: "You don't have access to this page" }),
    ).toBeInTheDocument()
  })
})
