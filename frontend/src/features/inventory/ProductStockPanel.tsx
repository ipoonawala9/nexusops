import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { MovementView, Page, ProductKind, ProductStock } from '@/lib/api/types'
import { AdjustStockDialog } from './AdjustStockDialog'
import { MovementsTable } from './MovementsTable'
import { formatQuantity } from './quantity'
import { TransferStockDialog } from './TransferStockDialog'

/** Stock per active warehouse and the last 10 movements, on a GOODS product's page when Inventory is on. */
export function ProductStockPanel({ productId, kind }: { productId: string; kind: ProductKind }) {
  const session = useTenantSession()
  const can = useCan()
  const modules = session.state.status === 'authenticated' ? session.state.profile.modules : []
  const visible = kind === 'GOODS' && modules.includes('INVENTORY') && can(PERMISSIONS.stockRead)
  if (!visible) return null
  return <StockCard productId={productId} canAdjust={can(PERMISSIONS.stockAdjust)} />
}

function StockCard({ productId, canAdjust }: { productId: string; canAdjust: boolean }) {
  const api = useApi()
  const [dialog, setDialog] = useState<'count' | 'transfer' | null>(null)
  const stock = useQuery({
    queryKey: ['inventory', 'product-stock', productId],
    queryFn: () => api.get<ProductStock>(`/inventory/stock/products/${productId}`),
  })
  const movements = useQuery({
    queryKey: ['inventory', 'movements', { productId }],
    queryFn: () =>
      api.get<Page<MovementView>>(`/inventory/movements?productId=${productId}&size=10`),
  })
  return (
    <Card role="region" aria-label="Stock">
      <CardHeader className="flex flex-row items-center justify-between gap-2">
        <CardTitle>Stock</CardTitle>
        {canAdjust && stock.data && (
          <div className="space-x-2">
            <Button size="sm" variant="outline" onClick={() => setDialog('transfer')}>
              Transfer
            </Button>
            <Button size="sm" variant="outline" onClick={() => setDialog('count')}>
              Count
            </Button>
          </div>
        )}
      </CardHeader>
      <CardContent className="space-y-4">
        {stock.isPending ? (
          <ListSkeleton rows={2} />
        ) : stock.isError ? (
          <ErrorState error={stock.error} onRetry={() => void stock.refetch()} />
        ) : (
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Warehouse</TableHead>
                <TableHead className="text-right">On hand</TableHead>
                <TableHead className="text-right">Reserved</TableHead>
                <TableHead className="text-right">Available</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {stock.data.levels.map((l) => (
                <TableRow key={l.warehouse.id}>
                  <TableCell>{l.warehouse.code}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatQuantity(l.onHand)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatQuantity(l.reserved)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatQuantity(l.available)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {movements.isError ? (
          <ErrorState error={movements.error} onRetry={() => void movements.refetch()} />
        ) : (
          movements.data &&
          (movements.data.items.length === 0 ? (
            <p className="text-sm text-muted-foreground">No stock movements yet.</p>
          ) : (
            <>
              <MovementsTable movements={movements.data.items} />
              <Link
                to={`/app/inventory/movements?productId=${productId}`}
                className="text-sm underline-offset-4 hover:underline"
              >
                View all movements
              </Link>
            </>
          ))
        )}
      </CardContent>
      {dialog === 'count' && stock.data && (
        <AdjustStockDialog product={stock.data.product} onClose={() => setDialog(null)} />
      )}
      {dialog === 'transfer' && stock.data && (
        <TransferStockDialog product={stock.data.product} onClose={() => setDialog(null)} />
      )}
    </Card>
  )
}
