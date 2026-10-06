import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'

describe('App routes', () => {
  it('renders a not-found page for unknown routes', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/nope' })
    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })
})
