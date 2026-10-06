import { render, screen } from '@testing-library/react'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { RouteError } from '@/pages/RouteError'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, signedOut } from '@/test/fixtures'
import { renderApp } from '@/test/renderApp'
import { routes } from './router'

function Bomb(): never {
  throw new Error('kaboom in render')
}

/** The app's real top-level routes, with a throwing page added under each of the two roots. */
function routesWithBombs(): RouteObject[] {
  return routes.map((route): RouteObject => {
    if (route.index || !route.children) return route
    const bomb = { path: route.path === '/platform' ? 'boom' : '/boom', element: <Bomb /> }
    return { ...route, children: [...route.children, bomb] }
  })
}

describe('App routes', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders a not-found page for unknown routes', async () => {
    const server = fakeServer()
    signedOut(server)
    renderApp({ server, path: '/nope' })
    expect(await screen.findByRole('heading', { name: /page not found/i })).toBeInTheDocument()
  })

  it('shows an error page, not a stack trace, when a tenant page throws', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const server = fakeServer()
    signedIn(server)
    renderApp({ server, path: '/boom', routes: routesWithBombs() })
    expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reload' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go home' })).toHaveAttribute('href', '/')
    // Development builds name the error; there is never a stack.
    expect(screen.getByText('kaboom in render')).toBeInTheDocument()
    expect(document.body.textContent).not.toMatch(/at Bomb|\.tsx:\d+/)
  })

  it('shows the error page when a platform page throws', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const server = fakeServer()
    renderApp({ server, path: '/platform/boom', routes: routesWithBombs() })
    expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
  })

  it('hides the error message outside development', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined)
    const router = createMemoryRouter(
      [{ path: '/', element: <Bomb />, errorElement: <RouteError showDetails={false} /> }],
      { initialEntries: ['/'] },
    )
    render(<RouterProvider router={router} />)
    expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    expect(screen.queryByText('kaboom in render')).not.toBeInTheDocument()
  })
})
