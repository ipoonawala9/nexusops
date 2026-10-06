import { Navigate, type RouteObject } from 'react-router'
import { RedirectIfTenantSignedIn, RequireTenantSession } from '@/features/auth/guards'
import { linkRoutes, signInRoutes } from '@/features/auth/routes'
import { platformRoutes } from '@/features/platform/routes'
import { appRoutes } from '@/features/shell/routes'
import { NotFoundPage } from '@/pages/NotFoundPage'
import { RouteError } from '@/pages/RouteError'
import { TenantRoot } from './TenantRoot'

/** Every top-level route gets an errorElement, so a render-time exception never shows a stack trace. */
export const routes: RouteObject[] = [
  {
    element: <TenantRoot />,
    errorElement: <RouteError />,
    children: [
      { path: '/', element: <Navigate to="/app" replace /> },
      {
        element: <RedirectIfTenantSignedIn />,
        children: signInRoutes,
      },
      ...linkRoutes,
      {
        element: <RequireTenantSession />,
        children: appRoutes,
      },
    ],
  },
  ...platformRoutes.map((route) => ({
    ...route,
    errorElement: <RouteError home="/platform" />,
  })),
  { path: '*', element: <NotFoundPage />, errorElement: <RouteError /> },
]
