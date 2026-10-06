import { Outlet } from 'react-router'
import { useApiHandles } from '@/app/handles'
import { ApiProvider } from '@/lib/api/ApiContext'
import { PlatformSessionProvider } from './platformSession'

/** /platform/* runs with the platform client and session only; the tenant session is never mounted here. */
export function PlatformRoot() {
  const { platform } = useApiHandles()
  return (
    <ApiProvider client={platform.client}>
      <PlatformSessionProvider api={platform}>
        <Outlet />
      </PlatformSessionProvider>
    </ApiProvider>
  )
}
