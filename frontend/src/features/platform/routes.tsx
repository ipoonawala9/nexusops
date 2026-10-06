import { Navigate, type RouteObject } from 'react-router'
import { RedirectIfPlatformSignedIn, RequirePlatformSession } from './guards'
import { PlatformLayout } from './PlatformLayout'
import { PlatformLoginPage } from './PlatformLoginPage'
import { PlatformRoot } from './PlatformRoot'
import { PlatformTenantsPage } from './PlatformTenantsPage'

export const platformRoutes: RouteObject[] = [
  {
    path: '/platform',
    element: <PlatformRoot />,
    children: [
      {
        element: <RedirectIfPlatformSignedIn />,
        children: [{ path: 'login', element: <PlatformLoginPage /> }],
      },
      {
        element: <RequirePlatformSession />,
        children: [
          {
            element: <PlatformLayout />,
            children: [
              { index: true, element: <Navigate to="/platform/tenants" replace /> },
              { path: 'tenants', element: <PlatformTenantsPage /> },
            ],
          },
        ],
      },
    ],
  },
]
