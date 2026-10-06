import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router'
import { FullPageLoading } from '@/components/states'
import { safeNext } from '@/features/auth/guards'
import { usePlatformSession } from './platformSession'

export function RequirePlatformSession() {
  const { state } = usePlatformSession()
  const location = useLocation()
  if (state.status === 'loading') return <FullPageLoading label="Loading the platform console…" />
  if (state.status === 'anonymous') {
    return (
      <Navigate
        to={`/platform/login?next=${encodeURIComponent(location.pathname + location.search)}`}
        replace
      />
    )
  }
  return <Outlet />
}

export function RedirectIfPlatformSignedIn() {
  const { state } = usePlatformSession()
  const [params] = useSearchParams()
  if (state.status === 'loading') return <FullPageLoading label="Loading…" />
  if (state.status === 'authenticated') {
    return <Navigate to={safeNext(params.get('next'), '/platform/tenants')} replace />
  }
  return <Outlet />
}
