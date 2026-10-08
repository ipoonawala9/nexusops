import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { InventoryOverviewPage } from './InventoryOverviewPage'
import { PurchaseOrderDetailPage } from './PurchaseOrderDetailPage'
import { PurchaseOrdersPage } from './PurchaseOrdersPage'
import { SalesOrderDetailPage } from './SalesOrderDetailPage'
import { SalesOrdersPage } from './SalesOrdersPage'
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
  {
    path: 'purchase-orders',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.purchaseRead]}>
        <PurchaseOrdersPage />
      </RequirePermission>
    ),
  },
  {
    path: 'purchase-orders/:orderId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.purchaseRead]}>
        <PurchaseOrderDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'sales-orders',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.orderRead]}>
        <SalesOrdersPage />
      </RequirePermission>
    ),
  },
  {
    path: 'sales-orders/:orderId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.orderRead]}>
        <SalesOrderDetailPage />
      </RequirePermission>
    ),
  },
]
