import { NavLink, Outlet } from 'react-router'
import { NoAccess } from '@/components/states'
import { PERMISSIONS, useCan, type PermissionCode } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { ComingSoonPage } from '@/features/shell/ComingSoonPage'
import { MODULE_PHASES } from '@/features/shell/nav'
import { cn } from '@/lib/utils'

/** Later tasks append Reorder. */
export const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  { to: '/app/inventory', label: 'Overview', anyOf: [PERMISSIONS.stockRead], end: true },
  { to: '/app/inventory/stock', label: 'Stock', anyOf: [PERMISSIONS.stockRead] },
  {
    to: '/app/inventory/purchase-orders',
    label: 'Purchase orders',
    anyOf: [PERMISSIONS.purchaseRead],
  },
  { to: '/app/inventory/sales-orders', label: 'Sales orders', anyOf: [PERMISSIONS.orderRead] },
]

/** The Inventory area: only when the module is enabled; tabs follow the user's Inventory permissions. */
export function InventoryLayout() {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  if (!modules.includes('INVENTORY')) {
    const info = MODULE_PHASES.INVENTORY
    return (
      <ComingSoonPage
        title={info.label}
        phase={info.phase}
        description={info.description}
        module="INVENTORY"
      />
    )
  }
  const tabs = TABS.filter((tab) => can(...tab.anyOf))
  if (tabs.length === 0) return <NoAccess />
  return (
    <div className="space-y-6">
      <nav aria-label="Inventory" className="flex flex-wrap gap-1 border-b pb-2">
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
