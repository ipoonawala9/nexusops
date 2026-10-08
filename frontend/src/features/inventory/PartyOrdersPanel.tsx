import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PurchaseOrderSummary, SalesOrderSummary } from '@/lib/api/types'
import { formatMoney } from '@/lib/format'
import { PURCHASE_STATUS_LABELS, SALES_STATUS_LABELS } from './labels'

/** The party's latest purchase orders (as supplier) and sales orders (as customer), when Inventory is on. */
export function PartyOrdersPanel({ partyId }: { partyId: string }) {
  const session = useTenantSession()
  const can = useCan()
  const api = useApi()
  const inventory =
    session.state.status === 'authenticated' && session.state.profile.modules.includes('INVENTORY')
  const canPurchases = inventory && can(PERMISSIONS.purchaseRead)
  const canSales = inventory && can(PERMISSIONS.orderRead)
  const purchases = useQuery({
    queryKey: ['inventory', 'purchase-orders', { supplierId: partyId }],
    queryFn: () =>
      api.get<Page<PurchaseOrderSummary>>(`/purchase-orders?supplierId=${partyId}&size=10`),
    enabled: canPurchases,
  })
  const sales = useQuery({
    queryKey: ['inventory', 'sales-orders', { customerId: partyId }],
    queryFn: () => api.get<Page<SalesOrderSummary>>(`/sales-orders?customerId=${partyId}&size=10`),
    enabled: canSales,
  })
  const bought = canPurchases ? (purchases.data?.items ?? []) : []
  const sold = canSales ? (sales.data?.items ?? []) : []
  if (bought.length === 0 && sold.length === 0) return null
  return (
    <Card role="region" aria-label="Orders">
      <CardHeader>
        <CardTitle>Orders</CardTitle>
      </CardHeader>
      <CardContent className="grid gap-4 text-sm lg:grid-cols-2">
        {bought.length > 0 && (
          <section aria-label="Purchase orders">
            <h3 className="mb-2 font-medium">Purchase orders</h3>
            <ul className="space-y-1">
              {bought.map((o) => (
                <li key={o.id} className="flex flex-wrap gap-2">
                  <Link
                    to={`/app/inventory/purchase-orders/${o.id}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {o.number}
                  </Link>
                  <span className="text-muted-foreground">{PURCHASE_STATUS_LABELS[o.status]}</span>
                  <span className="tabular-nums">{formatMoney(o.total, o.currency)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
        {sold.length > 0 && (
          <section aria-label="Sales orders">
            <h3 className="mb-2 font-medium">Sales orders</h3>
            <ul className="space-y-1">
              {sold.map((o) => (
                <li key={o.id} className="flex flex-wrap gap-2">
                  <Link
                    to={`/app/inventory/sales-orders/${o.id}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {o.number}
                  </Link>
                  <span className="text-muted-foreground">{SALES_STATUS_LABELS[o.status]}</span>
                  <span className="tabular-nums">{formatMoney(o.total, o.currency)}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </CardContent>
    </Card>
  )
}
