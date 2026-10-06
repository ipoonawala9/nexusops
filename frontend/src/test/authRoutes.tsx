import type { RouteObject } from 'react-router'
import { TenantRoot } from '@/app/TenantRoot'
import { RedirectIfTenantSignedIn, RequireTenantSession } from '@/features/auth/guards'
import { linkRoutes, signInRoutes } from '@/features/auth/routes'

export const authTestRoutes: RouteObject[] = [
  {
    element: <TenantRoot />,
    children: [
      { element: <RedirectIfTenantSignedIn />, children: signInRoutes },
      ...linkRoutes,
      {
        element: <RequireTenantSession />,
        children: [{ path: '/app/*', element: <h1>App home</h1> }],
      },
    ],
  },
]
