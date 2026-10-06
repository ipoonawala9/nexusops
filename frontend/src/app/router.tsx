import { Navigate, type RouteObject } from 'react-router'
import { RedirectIfTenantSignedIn, RequireTenantSession } from '@/features/auth/guards'
import { linkRoutes, signInRoutes } from '@/features/auth/routes'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { TenantRoot } from './TenantRoot'

export const routes: RouteObject[] = [
  {
    element: <TenantRoot />,
    children: [
      { path: '/', element: <Navigate to="/app" replace /> },
      {
        element: <RedirectIfTenantSignedIn />,
        children: signInRoutes,
      },
      ...linkRoutes,
      {
        element: <RequireTenantSession />,
        children: [
          // the workspace shell and its pages (Tasks 4–9)
        ],
      },
    ],
  },
  { path: '*', element: <NotFoundPage /> },
]
