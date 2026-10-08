import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PurchaseOrderSummary } from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { PURCHASE_STATUS_LABELS } from './labels'
import { PurchaseOrderFormDialog } from './PurchaseOrderFormDialog'

const SIZE = 20

export function PurchaseOrdersPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.purchaseManage)

  const orders = useQuery({
    queryKey: ['inventory', 'purchase-orders', { q, status, page }],
    queryFn: () =>
      api.get<Page<PurchaseOrderSummary>>(
        `/purchase-orders?${toQuery({ q, status, page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Purchase orders"
        description="What you've ordered from suppliers, and what has arrived."
        actions={canManage && <Button onClick={() => setCreating(true)}>New purchase order</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="po-q">Search purchase orders</Label>
            <Input id="po-q" name="q" defaultValue={q} placeholder="Number" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="po-status">Status</Label>
          <NativeSelect
            id="po-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Any status</option>
            {Object.entries(PURCHASE_STATUS_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </div>
      </div>
      {orders.isPending ? (
        <ListSkeleton />
      ) : orders.isError ? (
        <ErrorState error={orders.error} onRetry={() => void orders.refetch()} />
      ) : orders.data.items.length === 0 ? (
        <EmptyState
          title={
            q || status ? 'No purchase orders match these filters.' : 'No purchase orders yet.'
          }
          description="Create one here, or from the reorder suggestions."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Number</TableHead>
                  <TableHead>Supplier</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Lines</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                  <TableHead>Expected</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {orders.data.items.map((o) => (
                  <TableRow key={o.id}>
                    <TableCell>
                      <Link
                        to={`/app/inventory/purchase-orders/${o.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {o.number}
                      </Link>
                    </TableCell>
                    <TableCell>{o.supplier?.name ?? '—'}</TableCell>
                    <TableCell>{o.warehouse.code}</TableCell>
                    <TableCell>
                      <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
                        {PURCHASE_STATUS_LABELS[o.status]}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{o.lineCount}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatMoney(o.total, o.currency)}
                    </TableCell>
                    <TableCell>{formatDate(o.expectedOn)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={orders.data.page}
            size={orders.data.size}
            total={orders.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <PurchaseOrderFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/inventory/purchase-orders/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
