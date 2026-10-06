import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider, type RouteObject } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { ApiHandlesProvider, type ApiHandles } from '@/app/handles'
import { routes as appRoutes } from '@/app/router'
import { createSessionApi } from '@/lib/api/session'
import type { FakeServer } from './fakeServer'

/** Renders routes exactly as App does, with both sessions talking to the fake server. */
export function renderApp({
  server,
  path,
  routes = appRoutes,
}: {
  server: FakeServer
  path: string
  routes?: RouteObject[]
}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const handles: ApiHandles = {
    tenant: createSessionApi({ refreshPath: '/auth/refresh', fetchImpl: server.fetchImpl }),
    platform: createSessionApi({
      refreshPath: '/platform/auth/refresh',
      fetchImpl: server.fetchImpl,
    }),
  }
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  const user = userEvent.setup()
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <ApiHandlesProvider handles={handles}>
        <RouterProvider router={router} />
        <Toaster />
      </ApiHandlesProvider>
    </QueryClientProvider>,
  )
  return { ...utils, user, router, server, handles, queryClient }
}
