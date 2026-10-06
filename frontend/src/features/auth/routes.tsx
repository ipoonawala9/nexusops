import type { RouteObject } from 'react-router'
import { LoginPage } from './LoginPage'
import { SignupPage } from './SignupPage'
import { VerifyEmailPage } from './VerifyEmailPage'

/** Sign-in/sign-up: wrapped in RedirectIfTenantSignedIn by the router. */
export const signInRoutes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
]

/** Pages opened from emailed links: always reachable, signed in or not. */
export const linkRoutes: RouteObject[] = [{ path: '/verify-email', element: <VerifyEmailPage /> }]
