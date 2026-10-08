import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLead, aSummary, defaultStages, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'], permissions: [...ALL_TENANT_PERMISSIONS] }))
    .on('GET /leads/:id', { body: aLead() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /tasks', { body: pageOf([]) })
    .on('GET /crm/pipeline/stages', { body: defaultStages() })
    .on('GET /parties', {
      body: pageOf([aSummary({ id: 'p-acme-ltd', name: 'Acme Robotics Ltd' })]),
    })
  return renderApp({ server, path: '/app/crm/leads/l-grace' })
}

async function open(user: ReturnType<typeof setup>['user']) {
  await user.click(await screen.findByRole('button', { name: 'Convert' }))
  return screen.findByRole('dialog')
}

const converted = () =>
  aLead({ status: 'CONVERTED', convertedAt: '2026-10-07T00:00:00Z', version: 1 })

describe('ConvertLeadDialog', () => {
  it('creates the person, organization and opportunity from the lead', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', { body: converted() })
    const dialog = await open(user)
    expect(within(dialog).getByLabelText('Organization name')).toHaveValue('Acme Robotics')
    expect(within(dialog).getByLabelText('First name')).toHaveValue('Grace')
    expect(within(dialog).getByLabelText('Opportunity name')).toHaveValue('Acme Robotics')
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(server.callsTo('POST /leads/:id/convert')[0].body).toEqual({
      organization: { name: 'Acme Robotics', domain: null, duplicateReason: null },
      person: {
        firstName: 'Grace',
        lastName: 'Hopper',
        jobTitle: null,
        email: 'grace@acme.test',
        phone: null,
        duplicateReason: null,
      },
      opportunity: {
        name: 'Acme Robotics',
        amount: 5000,
        currency: 'USD',
        stageId: 's-prospecting',
        expectedCloseOn: null,
      },
      version: 0,
    })
    expect(await screen.findByText('Lead converted.')).toBeInTheDocument()
  })

  it('shows a duplicate organization and lets the user link it instead', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', (req) => {
      const org = (req.body as { organization?: { existingId?: string } }).organization
      return org?.existingId
        ? { body: converted() }
        : {
            status: 409,
            body: {
              detail: 'This looks like a record that already exists.',
              party: 'organization',
              duplicates: [
                {
                  id: 'p-acme-ltd',
                  kind: 'ORGANIZATION',
                  name: 'Acme Robotics Ltd',
                  email: null,
                  domain: null,
                  archived: false,
                },
              ],
            },
          }
    })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const notice = await within(dialog).findByRole('alert')
    expect(within(notice).getByText('Acme Robotics Ltd')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Use Acme Robotics Ltd' }))
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(server.callsTo('POST /leads/:id/convert')[1].body).toMatchObject({
      organization: { existingId: 'p-acme-ltd' },
    })
  })

  it('can convert without an organization or opportunity', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', { body: converted() })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('radio', { name: 'No organization' }))
    await user.click(within(dialog).getByRole('checkbox', { name: 'Create an opportunity' }))
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const body = server.callsTo('POST /leads/:id/convert')[0].body as Record<string, unknown>
    expect(body.organization).toBeNull()
    expect(body.opportunity).toBeNull()
  })

  it('puts server field errors on the right section', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'person.firstName', message: 'Enter between 1 and 80 characters.' }],
      },
    })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(
      await within(dialog).findByText('Enter between 1 and 80 characters.'),
    ).toBeInTheDocument()
  })
})
