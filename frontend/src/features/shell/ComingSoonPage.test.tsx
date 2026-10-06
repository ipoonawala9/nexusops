import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

describe('ComingSoonPage', () => {
  it('names the blueprint phase of an enabled module', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['CRM'] }))
    renderApp({ server, path: '/app/crm' })
    expect(await screen.findByRole('heading', { name: 'CRM' })).toBeInTheDocument()
    expect(screen.getByText(/Phase 5/)).toBeInTheDocument()
  })

  it('explains a module that is not enabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/inventory' })
    expect(
      await screen.findByText('Inventory is not enabled for this workspace.'),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Manage modules' })).toHaveAttribute(
      'href',
      '/app/settings/modules',
    )
  })

  it('shows platform features as coming soon', async () => {
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/app/assistant' })
    expect(await screen.findByRole('heading', { name: 'AI Assistant' })).toBeInTheDocument()
    expect(screen.getByText(/Phase 12/)).toBeInTheDocument()
  })
})
