import { QueryClientProvider } from '@tanstack/react-query'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { Toaster } from '@/components/ui/sonner'
import { ApiHandlesProvider, createDefaultHandles } from './handles'
import { queryClient } from './queryClient'
import { routes } from './router'

const router = createBrowserRouter(routes)
const handles = createDefaultHandles()

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ApiHandlesProvider handles={handles}>
        <RouterProvider router={router} />
        <Toaster richColors closeButton />
      </ApiHandlesProvider>
    </QueryClientProvider>
  )
}
