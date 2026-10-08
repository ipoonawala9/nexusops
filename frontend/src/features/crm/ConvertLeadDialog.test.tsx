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

  it('keeps the organization and person duplicate reasons apart', async () => {
    const { server, user } = setup()
    const duplicate = (party: string, id: string, name: string) => ({
      status: 409,
      body: {
        detail: 'This looks like a record that already exists.',
        party,
        duplicates: [
          {
            id,
            kind: party === 'organization' ? 'ORGANIZATION' : 'PERSON',
            name,
            email: null,
            domain: null,
            archived: false,
          },
        ],
      },
    })
    server.on('POST /leads/:id/convert', (req) => {
      const body = req.body as {
        organization?: { duplicateReason?: string | null }
        person?: { duplicateReason?: string | null }
      }
      if (!body.organization?.duplicateReason)
        return duplicate('organization', 'p-acme-ltd', 'Acme Ltd')
      if (!body.person?.duplicateReason) return duplicate('person', 'p-grace-h', 'Grace H.')
      return { body: converted() }
    })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const orgGroup = within(await within(dialog).findByRole('group', { name: 'Organization' }))
    await user.type(await orgGroup.findByLabelText(/keep a separate record/), 'Another branch')
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const personGroup = within(within(dialog).getByRole('group', { name: 'Person' }))
    await personGroup.findByText('Grace H.')
    expect(within(dialog).getAllByRole('alert')).toHaveLength(2)
    await user.type(personGroup.getByLabelText(/keep a separate record/), 'Different Grace')
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const calls = server.callsTo('POST /leads/:id/convert')
    expect(calls).toHaveLength(3)
    expect(calls[2].body).toMatchObject({
      organization: { duplicateReason: 'Another branch' },
      person: { duplicateReason: 'Different Grace' },
    })
  })

  it('shows server errors for fields the dialog has no input for', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'person.email', message: 'Enter a valid email address.' }],
      },
    })
    const dialog = await open(user)
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(await within(dialog).findByText('Enter a valid email address.')).toBeInTheDocument()
  })

  it('sends no amount when the field is cleared', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', { body: converted() })
    const dialog = await open(user)
    await user.clear(within(dialog).getByLabelText('Amount (USD)'))
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    const body = server.callsTo('POST /leads/:id/convert')[0].body as {
      opportunity: { amount: number | null; currency: string | null }
    }
    expect(body.opportunity).toMatchObject({ amount: null, currency: null })
  })

  it('does not submit an amount that is not a number', async () => {
    const { server, user } = setup()
    server.on('POST /leads/:id/convert', { body: converted() })
    const dialog = await open(user)
    const amount = within(dialog).getByLabelText('Amount (USD)')
    await user.clear(amount)
    await user.type(amount, '1,000')
    await user.click(within(dialog).getByRole('button', { name: 'Convert lead' }))
    expect(await within(dialog).findByText('Enter a number like 1200.50.')).toBeInTheDocument()
    expect(server.callsTo('POST /leads/:id/convert')).toHaveLength(0)
  })
})
