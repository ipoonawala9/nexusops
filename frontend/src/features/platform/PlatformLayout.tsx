import { Outlet } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { usePlatformSession } from './platformSession'

export function PlatformLayout() {
  const session = usePlatformSession()
  if (session.state.status !== 'authenticated') return null
  const { profile } = session.state
  return (
    <div className="min-h-screen">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b px-4 py-3 md:px-8">
        <p className="font-semibold">NexusOps Platform</p>
        <div className="flex items-center gap-3 text-sm">
          <span>{profile.email}</span>
          <Badge variant="secondary">
            {profile.role === 'PLATFORM_ADMIN' ? 'Admin' : 'Support'}
          </Badge>
          <Button variant="outline" size="sm" onClick={() => void session.logout()}>
            Sign out
          </Button>
        </div>
      </header>
      <main className="p-4 md:p-8">
        <Outlet />
      </main>
    </div>
  )
}
