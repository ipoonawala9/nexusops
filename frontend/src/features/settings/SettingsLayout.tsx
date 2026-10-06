import { Navigate, NavLink, Outlet, useLocation } from 'react-router'
import { NoAccess } from '@/components/states'
import { useCan } from '@/features/auth/permissions'
import { firstSettingsPath, SETTINGS_TABS } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

export function SettingsLayout() {
  const can = useCan()
  const location = useLocation()
  const tabs = SETTINGS_TABS.filter((tab) => can(...tab.anyOf))
  const first = firstSettingsPath(can)
  if (!first) return <NoAccess />
  if (location.pathname.replace(/\/$/, '') === '/app/settings')
    return <Navigate to={first} replace />
  return (
    <div className="space-y-6">
      <nav aria-label="Settings" className="flex flex-wrap gap-2 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            className={({ isActive }) =>
              cn(
                'rounded-md px-3 py-1.5 text-sm hover:bg-muted',
                isActive && 'bg-muted font-medium',
              )
            }
          >
            {tab.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
