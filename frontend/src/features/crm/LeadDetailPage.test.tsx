import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLead, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { LeadView } from '@/lib/api/types'

function setup(lead: LeadView = aLead(), permissions: string[] = [...ALL_TENANT_PERMISSIONS]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions }))
    .on('GET /leads/:id', { body: lead })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
    .on('GET /crm/owners', { body: [] })
  return renderApp({ server, path: `/app/crm/leads/${lead.id}` })
}

describe('LeadDetailPage', () => {
  it('shows the lead with its record panels', async () => {
    const { server } = setup()
    expect(await screen.findByRole('heading', { name: 'Grace Hopper' })).toBeInTheDocument()
    expect(screen.getByText('grace@acme.test')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Activity' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Tasks' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Documents' })).toBeInTheDocument()
    expect(server.callsTo('GET /activities')[0].query.get('subjectType')).toBe('LEAD')
  })

  it('moves the lead through its statuses', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/status', { body: aLead({ status: 'CONTACTED', version: 1 }) })
    await user.click(await screen.findByRole('button', { name: 'Mark contacted' }))
    expect(server.callsTo('POST /leads/:id/status')[0].body).toEqual({
      status: 'CONTACTED',
      version: 0,
    })
    expect(await screen.findByText('Contacted')).toBeInTheDocument()
  })

  it('asks for a reason to disqualify and can reopen', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/status', (req) =>
      (req.body as { status: string }).status === 'DISQUALIFIED'
        ? { body: aLead({ status: 'DISQUALIFIED', disqualifyReason: 'No budget', version: 1 }) }
        : { body: aLead({ status: 'NEW', version: 2 }) },
    )
    await user.click(await screen.findByRole('button', { name: 'Disqualify' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Disqualify' }))
    expect(await within(dialog).findByText('Required.')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Reason'), 'No budget')
    await user.click(within(dialog).getByRole('button', { name: 'Disqualify' }))
    expect(await screen.findByText(/No budget/)).toBeInTheDocument()
    expect(server.callsTo('POST /leads/:id/status')[0].body).toEqual({
      status: 'DISQUALIFIED',
      reason: 'No budget',
      version: 0,
    })
    await user.click(screen.getByRole('button', { name: 'Reopen' }))
    expect(server.callsTo('POST /leads/:id/status')[1].body).toEqual({ status: 'NEW', version: 1 })
  })

  it('shows what a converted lead became and freezes it', async () => {
    setup(
      aLead({
        status: 'CONVERTED',
        convertedAt: '2026-10-06T10:00:00Z',
        convertedPerson: { id: 'p-grace', name: 'Grace Hopper' },
        convertedOrganization: { id: 'p-acme', name: 'Acme Robotics' },
        convertedOpportunityId: 'o-renewal',
      }),
    )
    expect(await screen.findByText(/This lead was converted/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Acme Robotics' })).toHaveAttribute(
      'href',
      '/app/directory/p-acme',
    )
    expect(screen.getByRole('link', { name: 'Open the opportunity' })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-renewal',
    )
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Convert' })).not.toBeInTheDocument()
  })

  it('shows no actions to readers', async () => {
    setup(aLead(), ['crm.lead.read'])
    await screen.findByRole('heading', { name: 'Grace Hopper' })
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark contacted' })).not.toBeInTheDocument()
  })
})
