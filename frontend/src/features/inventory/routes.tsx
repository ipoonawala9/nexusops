import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { InventoryIndex } from './InventoryIndex'
import { MovementsPage } from './MovementsPage'
import { PurchaseOrderDetailPage } from './PurchaseOrderDetailPage'
import { PurchaseOrdersPage } from './PurchaseOrdersPage'
import { ReorderPage } from './ReorderPage'
import { SalesOrderDetailPage } from './SalesOrderDetailPage'
import { SalesOrdersPage } from './SalesOrdersPage'
import { StockPage } from './StockPage'

/** /app/inventory/* children. */
export const inventoryChildren: RouteObject[] = [
  {
    index: true,
    element: <InventoryIndex />,
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
    path: 'movements',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.stockRead]}>
        <MovementsPage />
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
  {
    path: 'reorder',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.stockRead]}>
        <ReorderPage />
      </RequirePermission>
    ),
  },
]
