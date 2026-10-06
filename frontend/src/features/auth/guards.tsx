import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router'
import { FullPageLoading } from '@/components/states'
import { safeNext, signInPath } from './redirects'
import { useTenantSession } from './tenantSession'

export function RequireTenantSession() {
  const { state } = useTenantSession()
  const location = useLocation()
  if (state.status === 'loading') return <FullPageLoading label="Loading your workspace…" />
  if (state.status === 'anonymous')
    return <Navigate to={signInPath('/login', state, location)} replace />
  return <Outlet />
}

export function RedirectIfTenantSignedIn() {
  const { state } = useTenantSession()
  const [params] = useSearchParams()
  if (state.status === 'loading') return <FullPageLoading label="Loading…" />
  if (state.status === 'authenticated')
    return <Navigate to={safeNext(params.get('next'), '/app')} replace />
  return <Outlet />
}
