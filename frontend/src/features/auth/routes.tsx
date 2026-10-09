import type { RouteObject } from 'react-router'
import { AcceptInvitationPage } from './AcceptInvitationPage'
import { ForgotPasswordPage } from './ForgotPasswordPage'
import { LoginPage } from './LoginPage'
import { ResetPasswordPage } from './ResetPasswordPage'
import { SignupPage } from './SignupPage'
import { VerifyEmailPage } from './VerifyEmailPage'

/** Sign-in/sign-up: wrapped in RedirectIfTenantSignedIn by the router. */
export const signInRoutes: RouteObject[] = [
  { path: '/login', element: <LoginPage /> },
  { path: '/signup', element: <SignupPage /> },
  { path: '/forgot-password', element: <ForgotPasswordPage /> },
]

/** Pages opened from emailed links: always reachable, signed in or not. */
export const linkRoutes: RouteObject[] = [
  { path: '/verify-email', element: <VerifyEmailPage /> },
  { path: '/reset-password', element: <ResetPasswordPage /> },
  { path: '/invite/accept', element: <AcceptInvitationPage /> },
]
