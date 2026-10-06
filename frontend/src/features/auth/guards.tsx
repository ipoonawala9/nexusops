import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router'
import { FullPageLoading } from '@/components/states'
import { useTenantSession } from './tenantSession'

/** Only same-app paths under `prefix` are valid post-login targets (no open redirect). */
export function safeNext(next: string | null, prefix: string): string {
  if (next && next.startsWith(prefix) && !next.startsWith('//')) return next
  return prefix
}

export function RequireTenantSession() {
  const { state } = useTenantSession()
  const location = useLocation()
  if (state.status === 'loading') return <FullPageLoading label="Loading your workspace…" />
  if (state.status === 'anonymous') {
    const next = encodeURIComponent(location.pathname + location.search)
    return <Navigate to={`/login?next=${next}`} replace />
  }
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
