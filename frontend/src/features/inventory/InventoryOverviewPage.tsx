import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { InventoryOverview } from '@/lib/api/types'
import { MovementsTable } from './MovementsTable'

export function InventoryOverviewPage() {
  const api = useApi()
  const can = useCan()
  const overview = useQuery({
    queryKey: ['inventory', 'overview'],
    queryFn: () => api.get<InventoryOverview>('/inventory/overview'),
    refetchOnMount: 'always',
  })
  if (overview.isPending) return <ListSkeleton />
  if (overview.isError)
    return <ErrorState error={overview.error} onRetry={() => void overview.refetch()} />
  const o = overview.data
  const tiles = [
    { count: o.belowMinimum, label: 'below minimum', to: '/app/inventory/stock?belowMin=true' },
    // ordered and partly received orders both await receipt, so the list isn't filtered by status
    can(PERMISSIONS.purchaseRead) && {
      count: o.purchaseOrdersAwaitingReceipt,
      label: 'awaiting receipt',
      to: '/app/inventory/purchase-orders',
    },
    can(PERMISSIONS.orderRead) && {
      count: o.salesOrdersAwaitingFulfilment,
      label: 'awaiting fulfilment',
      to: '/app/inventory/sales-orders?status=CONFIRMED',
    },
  ].filter((t) => t !== false)
  return (
    <div className="space-y-6">
      <PageHeader title="Inventory" description="What needs attention, and what moved last." />
      <div className="grid gap-3 sm:grid-cols-3">
        {tiles.map((t) => (
          <Link key={t.label} to={t.to} className="rounded-lg border p-4 hover:bg-muted">
            <span className="block text-3xl font-semibold tabular-nums">{t.count}</span>
            <span className="text-sm text-muted-foreground">{t.label}</span>
          </Link>
        ))}
      </div>
      <Card>
        <CardHeader>
          <CardTitle>Recent movements</CardTitle>
        </CardHeader>
        <CardContent>
          {o.recentMovements.length === 0 ? (
            <EmptyState
              title="No stock has moved yet."
              description="Count opening stock or receive a purchase order to begin."
            />
          ) : (
            <MovementsTable movements={o.recentMovements} showProduct />
          )}
        </CardContent>
      </Card>
    </div>
  )
}
