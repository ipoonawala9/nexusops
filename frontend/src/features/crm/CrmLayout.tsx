import { NavLink, Outlet } from 'react-router'
import { NoAccess } from '@/components/states'
import { PERMISSIONS, useCan, type PermissionCode } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { ComingSoonPage } from '@/features/shell/ComingSoonPage'
import { MODULE_PHASES } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  {
    to: '/app/crm',
    label: 'Dashboard',
    anyOf: [PERMISSIONS.leadRead, PERMISSIONS.opportunityRead],
    end: true,
  },
  { to: '/app/crm/leads', label: 'Leads', anyOf: [PERMISSIONS.leadRead] },
  { to: '/app/crm/pipeline', label: 'Pipeline', anyOf: [PERMISSIONS.opportunityRead] },
  { to: '/app/crm/customers', label: 'Customers', anyOf: [PERMISSIONS.customerRead] },
]

/** The CRM area: only when the module is enabled; tabs follow the user's CRM permissions. */
export function CrmLayout() {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  if (!modules.includes('CRM')) {
    const info = MODULE_PHASES.CRM
    return (
      <ComingSoonPage title={info.label} phase={info.phase} description={info.description} module="CRM" />
    )
  }
  const tabs = TABS.filter((tab) => can(...tab.anyOf))
  if (tabs.length === 0) return <NoAccess />
  return (
    <div className="space-y-6">
      <nav aria-label="CRM" className="flex flex-wrap gap-1 border-b pb-2">
        {tabs.map((tab) => (
          <NavLink
            key={tab.to}
            to={tab.to}
            end={tab.end}
            className={({ isActive }) =>
              cn('rounded-md px-3 py-1.5 text-sm hover:bg-muted', isActive && 'bg-muted font-medium')
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
