import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { InventoryOverviewPage } from './InventoryOverviewPage'

/** /app/inventory/* children; later tasks append theirs. */
export const inventoryChildren: RouteObject[] = [
  {
    index: true,
    element: (
      <RequirePermission anyOf={[PERMISSIONS.stockRead]}>
        <InventoryOverviewPage />
      </RequirePermission>
    ),
  },
]
