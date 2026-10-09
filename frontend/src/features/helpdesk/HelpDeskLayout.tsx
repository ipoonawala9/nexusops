import { NavLink, Outlet } from 'react-router'
import { NoAccess } from '@/components/states'
import { PERMISSIONS, useCan, type PermissionCode } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { ComingSoonPage } from '@/features/shell/ComingSoonPage'
import { MODULE_PHASES } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

export const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  { to: '/app/helpdesk', label: 'Dashboard', anyOf: [PERMISSIONS.ticketRead], end: true },
  { to: '/app/helpdesk/tickets', label: 'Tickets', anyOf: [PERMISSIONS.ticketRead] },
  { to: '/app/helpdesk/articles', label: 'Knowledge base', anyOf: [PERMISSIONS.articleRead] },
]

/** The HelpDesk area: only when the module is enabled; tabs follow the user's HelpDesk permissions. */
export function HelpDeskLayout() {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  if (!modules.includes('HELPDESK')) {
    const info = MODULE_PHASES.HELPDESK
    return (
      <ComingSoonPage
        title={info.label}
        phase={info.phase}
        description={info.description}
        module="HELPDESK"
      />
    )
  }
  const tabs = TABS.filter((tab) => can(...tab.anyOf))
  if (tabs.length === 0) return <NoAccess />
  return (
    <div className="space-y-6">
      <nav aria-label="HelpDesk" className="flex flex-wrap gap-1 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
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
