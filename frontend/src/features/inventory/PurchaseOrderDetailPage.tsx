import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Badge } from '@/components/ui/badge'
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
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { PurchaseOrderView } from '@/lib/api/types'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import { PURCHASE_STATUS_LABELS } from './labels'
import { PurchaseOrderFormDialog } from './PurchaseOrderFormDialog'
import { formatQuantity } from './quantity'
import { ReceiveDialog } from './ReceiveDialog'
import { useOrderAction } from './useOrderAction'

export function PurchaseOrderDetailPage() {
  const { orderId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const key = ['inventory', 'purchase-order', orderId] as const
  const order = useQuery({
    queryKey: key,
    queryFn: () => api.get<PurchaseOrderView>(`/purchase-orders/${orderId}`),
  })
  const action = useOrderAction(key)
  const [dialog, setDialog] = useState<'edit' | 'receive' | 'cancel' | null>(null)
  const canManage = can(PERMISSIONS.purchaseManage)
  const back = (
    <Link
      to="/app/inventory/purchase-orders"
      className="text-sm underline-offset-4 hover:underline"
    >
      ← Purchase orders
    </Link>
  )
  if (order.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (order.isError)
    return (
      <>
        {back}
        <ErrorState error={order.error} onRetry={() => void order.refetch()} />
      </>
    )
  const o = order.data
  // a received order stays open for notes (quality issues, invoices); a cancelled one is history
  const closed = o.status === 'CANCELLED'
  const path = `/purchase-orders/${o.id}`

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={o.number}
        description={`${o.supplier?.name ?? 'Supplier'} · into ${o.warehouse.code}`}
        actions={
          <>
            <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
              {PURCHASE_STATUS_LABELS[o.status]}
            </Badge>
            {canManage && o.status === 'DRAFT' && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                <Button
                  size="sm"
                  disabled={action.busy}
                  onClick={() =>
                    void action.run(`${path}/order`, { version: o.version }, `${o.number} placed.`)
                  }
                >
                  Place order
                </Button>
              </>
            )}
            {canManage && (o.status === 'ORDERED' || o.status === 'PARTIALLY_RECEIVED') && (
              <Button size="sm" onClick={() => setDialog('receive')}>
                Receive
              </Button>
            )}
            {canManage && (o.status === 'DRAFT' || o.status === 'ORDERED') && (
              <Button variant="outline" size="sm" onClick={() => setDialog('cancel')}>
                Cancel order
              </Button>
            )}
          </>
        }
      />
      {!dialog && <FormError message={action.error} />}
      <Card>
        <CardHeader>
          <CardTitle>Lines</CardTitle>
        </CardHeader>
        <CardContent>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>SKU</TableHead>
                <TableHead>Product</TableHead>
                <TableHead className="text-right">Qty ordered</TableHead>
                <TableHead className="text-right">Qty received</TableHead>
                <TableHead className="text-right">Qty due</TableHead>
                <TableHead className="text-right">Unit cost</TableHead>
                <TableHead className="text-right">Total</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {o.lines.map((l) => (
                <TableRow key={l.id}>
                  <TableCell>
                    <Link
                      to={`/app/products/${l.product.id}`}
                      className="underline-offset-4 hover:underline"
                    >
                      {l.product.sku}
                    </Link>
                  </TableCell>
                  <TableCell>{l.product.name}</TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatQuantity(l.quantity, l.product.unit)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatQuantity(l.receivedQuantity)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatQuantity(l.remainingQuantity)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatMoney(l.unitCost, o.currency)}
                  </TableCell>
                  <TableCell className="text-right tabular-nums">
                    {formatMoney(l.lineTotal, o.currency)}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          <p className="mt-3 text-right text-sm font-medium">
            Total {formatMoney(o.total, o.currency)}
          </p>
        </CardContent>
      </Card>
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {o.notes && <p className="whitespace-pre-wrap">{o.notes}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Supplier</dt>
            <dd>
              {o.supplier ? (
                <Link
                  to={`/app/directory/${o.supplier.id}`}
                  className="underline-offset-4 hover:underline"
                >
                  {o.supplier.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Warehouse</dt>
            <dd>
              {o.warehouse.code} · {o.warehouse.name}
            </dd>
            <dt className="text-muted-foreground">Expected</dt>
            <dd>{formatDate(o.expectedOn)}</dd>
            <dt className="text-muted-foreground">Created</dt>
            <dd>
              {formatDateTime(o.createdAt)}
              {o.createdBy ? ` by ${o.createdBy.name}` : ''}
            </dd>
            {o.orderedAt && (
              <>
                <dt className="text-muted-foreground">Ordered on</dt>
                <dd>{formatDateTime(o.orderedAt)}</dd>
              </>
            )}
            {o.receivedAt && (
              <>
                <dt className="text-muted-foreground">Received on</dt>
                <dd>{formatDateTime(o.receivedAt)}</dd>
              </>
            )}
            {o.cancelledAt && (
              <>
                <dt className="text-muted-foreground">Cancelled on</dt>
                <dd>{formatDateTime(o.cancelledAt)}</dd>
              </>
            )}
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel
        subjectType="PURCHASE_ORDER"
        subjectId={o.id}
        label={o.number}
        archived={closed}
      />
      <ActivityPanel subjectType="PURCHASE_ORDER" subjectId={o.id} archived={closed} />
      <DocumentsPanel subjectType="PURCHASE_ORDER" subjectId={o.id} archived={closed} />
      {dialog === 'edit' && (
        <PurchaseOrderFormDialog
          order={o}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      {dialog === 'receive' && (
        <ReceiveDialog
          order={o}
          busy={action.busy}
          error={action.error}
          onClose={() => {
            action.setError(null)
            setDialog(null)
          }}
          onReceive={(lines) =>
            action.run(`${path}/receipts`, { lines, version: o.version }, 'Receipt recorded.')
          }
        />
      )}
      <ConfirmDialog
        open={dialog === 'cancel'}
        title={`Cancel ${o.number}?`}
        description="The order stays on record as cancelled. Nothing was received, so stock doesn't change."
        confirmLabel="Cancel order"
        busy={action.busy}
        error={action.error}
        onCancel={() => {
          action.setError(null)
          setDialog(null)
        }}
        onConfirm={() =>
          void action
            .run(`${path}/cancel`, { version: o.version }, `${o.number} cancelled.`)
            .then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}
