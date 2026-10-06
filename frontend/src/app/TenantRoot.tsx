import { Outlet } from 'react-router'
import { TenantSessionProvider } from '@/features/auth/tenantSession'
import { ApiProvider } from '@/lib/api/ApiContext'
import { useApiHandles } from './handles'

/** Everything outside /platform runs with the tenant API client and session. */
export function TenantRoot() {
  const { tenant } = useApiHandles()
  return (
    <ApiProvider client={tenant.client}>
      <TenantSessionProvider api={tenant}>
        <Outlet />
      </TenantSessionProvider>
    </ApiProvider>
  )
}
