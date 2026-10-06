import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import type { PlatformTenant } from '@/lib/api/types'
import { fakeServer } from '@/test/fakeServer'
import { platformMe, platformSignedIn } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

const ACME: PlatformTenant = {
  id: 't-acme',
  slug: 'acme',
  name: 'Acme Inc',
  status: 'ACTIVE',
  planCode: 'FREE',
  createdAt: '2026-10-01T09:00:00Z',
  activeUsers: 3,
  ownerEmails: ['ada@acme.test'],
}

function setup(me = platformMe()) {
  const server = fakeServer()
  platformSignedIn(server, me).on('GET /platform/tenants', {
    body: { items: [ACME], page: 0, size: 20, total: 1 },
  })
  return renderApp({ server, path: '/platform/tenants' })
}

describe('PlatformTenantsPage', () => {
  it('lists workspaces with counts and owners', async () => {
    setup()
    const row = (await screen.findByText('Acme Inc')).closest('tr') as HTMLElement
    expect(within(row).getByText('acme')).toBeInTheDocument()
    expect(within(row).getByText('3')).toBeInTheDocument()
    expect(within(row).getByText('ada@acme.test')).toBeInTheDocument()
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(screen.getByText('ops@nexusops.test')).toBeInTheDocument()
  })

  it('searches and filters', async () => {
    const { server, user } = setup()
    await screen.findByText('Acme Inc')
    await user.type(screen.getByLabelText('Search workspaces'), 'acme')
    await user.selectOptions(screen.getByLabelText('Status'), 'SUSPENDED')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    const query = server.callsTo('GET /platform/tenants').at(-1)?.query
    expect(query?.get('q')).toBe('acme')
    expect(query?.get('status')).toBe('SUSPENDED')
  })

  it('suspends with a required reason', async () => {
    const { server, user } = setup()
    server.on('POST /platform/tenants/:id/suspend', { body: { ...ACME, status: 'SUSPENDED' } })
    await user.click(await screen.findByRole('button', { name: 'Suspend Acme Inc' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Suspend workspace' }))
    expect(
      await within(dialog).findByText('Enter a reason between 1 and 500 characters.'),
    ).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason'), 'Abuse report 42')
    await user.click(within(dialog).getByRole('button', { name: 'Suspend workspace' }))
    expect(await screen.findByText('Acme Inc suspended.')).toBeInTheDocument()
    expect(server.callsTo('POST /platform/tenants/:id/suspend')[0]).toMatchObject({
      params: { id: 't-acme' },
      body: { reason: 'Abuse report 42' },
    })
  })

  it('offers reactivation for a suspended workspace and shows conflicts', async () => {
    const { server, user } = setup()
    server
      .on('GET /platform/tenants', {
        body: { items: [{ ...ACME, status: 'SUSPENDED' }], page: 0, size: 20, total: 1 },
      })
      .on('POST /platform/tenants/:id/reactivate', {
        status: 409,
        body: { detail: 'Only a suspended workspace can be reactivated.' },
      })
    await user.click(await screen.findByRole('button', { name: 'Reactivate Acme Inc' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Reason'), 'Resolved')
    await user.click(within(dialog).getByRole('button', { name: 'Reactivate workspace' }))
    expect(
      await within(dialog).findByText('Only a suspended workspace can be reactivated.'),
    ).toBeInTheDocument()
  })

  it('gives support staff no suspend actions', async () => {
    setup(platformMe({ role: 'PLATFORM_SUPPORT', permissions: ['platform.tenant.read'] }))
    await screen.findByText('Acme Inc')
    expect(screen.queryByRole('button', { name: /Suspend|Reactivate/ })).not.toBeInTheDocument()
  })

  it('sends signed-out staff to the platform sign-in', async () => {
    const server = fakeServer()
    server.on('POST /platform/auth/refresh', { status: 401, body: {} })
    const { router } = renderApp({ server, path: '/platform/tenants' })
    expect(await screen.findByRole('heading', { name: 'Staff sign-in' })).toBeInTheDocument()
    expect(router.state.location.search).toBe(`?next=${encodeURIComponent('/platform/tenants')}`)
  })

  it('signs staff out to a plain platform sign-in page (no next)', async () => {
    const { server, user, router } = setup()
    server.on('POST /platform/auth/logout', { status: 204 })
    await user.click(await screen.findByRole('button', { name: 'Sign out' }))
    expect(await screen.findByRole('heading', { name: 'Staff sign-in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/platform/login')
    expect(router.state.location.search).toBe('')
  })
})
