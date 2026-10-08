import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { InventoryOverviewPage } from './InventoryOverviewPage'
import { StockPage } from './StockPage'

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
  {
    path: 'stock',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.stockRead]}>
        <StockPage />
      </RequirePermission>
    ),
  },
]
