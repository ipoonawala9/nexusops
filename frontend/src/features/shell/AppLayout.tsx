import { useState } from 'react'
import { NavLink, Outlet } from 'react-router'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import { fullName } from '@/lib/format'
import { cn } from '@/lib/utils'
import { firstSettingsPath, NAV_ITEMS } from './nav'

const linkClass = ({ isActive }: { isActive: boolean }) =>
  cn(
    'flex items-center justify-between rounded-md px-3 py-2 text-sm hover:bg-muted',
    isActive && 'bg-muted font-medium',
  )

export function AppLayout() {
  const session = useTenantSession()
  const api = useApi()
  const can = useCan()
  const [menuOpen, setMenuOpen] = useState(false)
  if (session.state.status !== 'authenticated') return null // RequireTenantSession guarantees this
  const { profile } = session.state
  const settingsPath = firstSettingsPath(can)
  const items = NAV_ITEMS.filter(
    (item) =>
      (!item.module || profile.modules.includes(item.module)) &&
      (!item.anyOf || can(...item.anyOf)),
  )

  async function signOutEverywhere() {
    try {
      await api.post('/auth/logout-all')
      session.clear()
    } catch (error) {
      toast.error(problemMessage(error))
    }
  }

  return (
    <div className="min-h-screen md:grid md:grid-cols-[15rem_1fr]">
      <header className="flex items-center justify-between border-b px-4 py-3 md:hidden">
        <span className="font-semibold">{profile.tenant.name}</span>
        <Button
          variant="outline"
          size="sm"
          aria-expanded={menuOpen}
          aria-controls="app-sidebar"
          onClick={() => setMenuOpen((open) => !open)}
        >
          Menu
        </Button>
      </header>
      <aside
        id="app-sidebar"
        className={cn('border-r bg-muted/20 p-4 md:block', menuOpen ? 'block' : 'hidden')}
      >
        <div className="mb-6 hidden md:block">
          <p className="text-xs text-muted-foreground">NexusOps</p>
          <p className="font-semibold">{profile.tenant.name}</p>
        </div>
        <nav aria-label="Workspace" className="space-y-1">
          {items.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/app'}
              className={linkClass}
              onClick={() => setMenuOpen(false)}
            >
              <span>{item.label}</span>
              {item.phase && <span className="text-xs text-muted-foreground">Soon</span>}
            </NavLink>
          ))}
          {settingsPath && (
            <NavLink to={settingsPath} className={linkClass} onClick={() => setMenuOpen(false)}>
              Settings
            </NavLink>
          )}
        </nav>
        <div className="mt-8 space-y-2 border-t pt-4 text-sm">
          <p className="font-medium">{fullName(profile.user)}</p>
          <p className="truncate text-xs text-muted-foreground">{profile.user.email}</p>
          <div className="flex flex-col gap-2 pt-2">
            <Button variant="outline" size="sm" onClick={() => void session.logout()}>
              Sign out
            </Button>
            <Button variant="ghost" size="sm" onClick={() => void signOutEverywhere()}>
              Sign out everywhere
            </Button>
          </div>
        </div>
      </aside>
      <main className="min-w-0 p-4 md:p-8">
        <Outlet />
      </main>
    </div>
  )
}
