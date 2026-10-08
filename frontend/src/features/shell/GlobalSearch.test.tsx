import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['CRM'] })).on('GET /search', (req) => ({
    body:
      req.query.get('q') === 'konkan'
        ? [
            {
              type: 'PARTY',
              id: 'p-konkan',
              label: 'Konkan Logistics',
              detail: 'konkan.test',
              archived: false,
            },
            {
              type: 'LEAD',
              id: 'l-konkan',
              label: 'Konkan Traders',
              detail: null,
              archived: false,
            },
            {
              type: 'OPPORTUNITY',
              id: 'o-konkan',
              label: 'Konkan renewal',
              detail: 'Konkan Logistics',
              archived: false,
            },
          ]
        : [],
  }))
  return renderApp({ server, path: '/app' })
}

describe('GlobalSearch', () => {
  it('searches as you type and links each result to its page', async () => {
    const { server, user } = setup()
    await user.type(await screen.findByLabelText('Search records'), 'konkan')
    const results = await screen.findByRole('list', { name: 'Search results' })
    expect(within(results).getByRole('link', { name: /^Konkan Logistics/ })).toHaveAttribute(
      'href',
      '/app/directory/p-konkan',
    )
    expect(within(results).getByRole('link', { name: /Konkan Traders/ })).toHaveAttribute(
      'href',
      '/app/crm/leads/l-konkan',
    )
    expect(within(results).getByRole('link', { name: /Konkan renewal/ })).toHaveAttribute(
      'href',
      '/app/crm/opportunities/o-konkan',
    )
    expect(within(results).getByText('Lead')).toBeInTheDocument()
    expect(server.callsTo('GET /search').every((c) => (c.query.get('q') ?? '').length >= 2)).toBe(
      true,
    )
  })

  it('says when nothing matches and clears on Escape', async () => {
    const { user } = setup()
    const input = await screen.findByLabelText('Search records')
    await user.type(input, 'zz')
    expect(await screen.findByText('No matches.')).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(input).toHaveValue('')
    expect(screen.queryByText('No matches.')).not.toBeInTheDocument()
  })

  it('focuses with the slash key', async () => {
    const { user } = setup()
    const input = await screen.findByLabelText('Search records')
    await user.keyboard('/')
    expect(input).toHaveFocus()
    expect(input).toHaveValue('')
  })
})
