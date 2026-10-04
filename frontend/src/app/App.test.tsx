import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it } from 'vitest'
import { routes } from './router'

describe('App routes', () => {
  it('renders the home page', () => {
    render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/'] })} />)
    expect(screen.getByRole('heading', { name: /nexusops/i })).toBeInTheDocument()
  })

  it('renders a not-found page for unknown routes', () => {
    render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: ['/nope'] })} />)
    expect(screen.getByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })
})
