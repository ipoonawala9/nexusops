import { Navigate } from 'react-router'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { InventoryOverviewPage } from './InventoryOverviewPage'
import { TABS } from './InventoryLayout'

/** The overview for stock readers; anyone else lands on the first section they may open. */
export function InventoryIndex() {
  const can = useCan()
  if (can(PERMISSIONS.stockRead)) return <InventoryOverviewPage />
  const first = TABS.find((tab) => can(...tab.anyOf))
  return first ? <Navigate to={first.to} replace /> : null
}
