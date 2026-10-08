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
import type { SalesOrderView } from '@/lib/api/types'
import { formatDateTime, formatMoney } from '@/lib/format'
import { SALES_STATUS_LABELS } from './labels'
import { formatQuantity } from './quantity'
import { SalesOrderFormDialog } from './SalesOrderFormDialog'
import { useOrderAction } from './useOrderAction'

export function SalesOrderDetailPage() {
  const { orderId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const key = ['inventory', 'sales-order', orderId] as const
  const order = useQuery({
    queryKey: key,
    queryFn: () => api.get<SalesOrderView>(`/sales-orders/${orderId}`),
  })
  const action = useOrderAction(key)
  const [dialog, setDialog] = useState<'edit' | 'cancel' | null>(null)
  const canManage = can(PERMISSIONS.orderManage)
  function open(next: 'edit' | 'cancel') {
    action.setError(null)
    setDialog(next)
  }
  const back = (
    <Link to="/app/inventory/sales-orders" className="text-sm underline-offset-4 hover:underline">
      ← Sales orders
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
  // a fulfilled order stays open for notes; a cancelled one is history
  const closed = o.status === 'CANCELLED'
  const path = `/sales-orders/${o.id}`

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={o.number}
        description={`${o.customer?.name ?? 'Customer'} · from ${o.warehouse.code}`}
        actions={
          <>
            <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
              {SALES_STATUS_LABELS[o.status]}
            </Badge>
            {canManage && o.status === 'DRAFT' && (
              <>
                <Button variant="outline" size="sm" onClick={() => open('edit')}>
                  Edit
                </Button>
                <Button
                  size="sm"
                  disabled={action.busy}
                  onClick={() =>
                    void action.run(
                      `${path}/confirm`,
                      { version: o.version },
                      `${o.number} confirmed.`,
                    )
                  }
                >
                  Confirm
                </Button>
              </>
            )}
            {canManage && o.status === 'CONFIRMED' && (
              <Button
                size="sm"
                disabled={action.busy}
                onClick={() =>
                  void action.run(
                    `${path}/fulfil`,
                    { version: o.version },
                    `${o.number} fulfilled.`,
                  )
                }
              >
                Fulfil
              </Button>
            )}
            {canManage && (o.status === 'DRAFT' || o.status === 'CONFIRMED') && (
              <Button variant="outline" size="sm" onClick={() => open('cancel')}>
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
                <TableHead className="text-right">Quantity</TableHead>
                <TableHead className="text-right">Unit price</TableHead>
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
                    {formatMoney(l.unitPrice, o.currency)}
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
            <dt className="text-muted-foreground">Customer</dt>
            <dd>
              {o.customer ? (
                <Link
                  to={`/app/directory/${o.customer.id}`}
                  className="underline-offset-4 hover:underline"
                >
                  {o.customer.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Warehouse</dt>
            <dd>
              {o.warehouse.code} · {o.warehouse.name}
            </dd>
            <dt className="text-muted-foreground">Created</dt>
            <dd>
              {formatDateTime(o.createdAt)}
              {o.createdBy ? ` by ${o.createdBy.name}` : ''}
            </dd>
            {o.confirmedAt && (
              <>
                <dt className="text-muted-foreground">Confirmed on</dt>
                <dd>{formatDateTime(o.confirmedAt)}</dd>
              </>
            )}
            {o.fulfilledAt && (
              <>
                <dt className="text-muted-foreground">Fulfilled on</dt>
                <dd>{formatDateTime(o.fulfilledAt)}</dd>
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
        subjectType="SALES_ORDER"
        subjectId={o.id}
        label={o.number}
        archived={closed}
      />
      <ActivityPanel subjectType="SALES_ORDER" subjectId={o.id} archived={closed} />
      <DocumentsPanel subjectType="SALES_ORDER" subjectId={o.id} archived={closed} />
      {dialog === 'edit' && (
        <SalesOrderFormDialog
          order={o}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      <ConfirmDialog
        open={dialog === 'cancel'}
        title={`Cancel ${o.number}?`}
        description={
          o.status === 'CONFIRMED'
            ? 'Its reserved stock is released for other orders. The order stays on record as cancelled.'
            : 'The order stays on record as cancelled.'
        }
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
