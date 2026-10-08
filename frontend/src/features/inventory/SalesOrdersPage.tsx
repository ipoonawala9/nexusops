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
import type { Page, SalesOrderSummary } from '@/lib/api/types'
import { formatDateTime, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { SALES_STATUS_LABELS } from './labels'
import { SalesOrderFormDialog } from './SalesOrderFormDialog'

const SIZE = 20

export function SalesOrdersPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.orderManage)

  const orders = useQuery({
    queryKey: ['inventory', 'sales-orders', { q, status, page }],
    queryFn: () =>
      api.get<Page<SalesOrderSummary>>(`/sales-orders?${toQuery({ q, status, page, size: SIZE })}`),
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
        title="Sales orders"
        description="Orders from customers: confirm to reserve stock, fulfil to ship it."
        actions={canManage && <Button onClick={() => setCreating(true)}>New sales order</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="so-q">Search sales orders</Label>
            <Input id="so-q" name="q" defaultValue={q} placeholder="Number" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="so-status">Status</Label>
          <NativeSelect
            id="so-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Any status</option>
            {Object.entries(SALES_STATUS_LABELS).map(([value, label]) => (
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
          title={q || status ? 'No sales orders match these filters.' : 'No sales orders yet.'}
          description="Create one when a customer orders goods you stock."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Number</TableHead>
                  <TableHead>Customer</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead className="text-right">Lines</TableHead>
                  <TableHead className="text-right">Total</TableHead>
                  <TableHead>Created</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {orders.data.items.map((o) => (
                  <TableRow key={o.id}>
                    <TableCell>
                      <Link
                        to={`/app/inventory/sales-orders/${o.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {o.number}
                      </Link>
                    </TableCell>
                    <TableCell>{o.customer?.name ?? '—'}</TableCell>
                    <TableCell>{o.warehouse.code}</TableCell>
                    <TableCell>
                      <Badge variant={o.status === 'CANCELLED' ? 'outline' : 'secondary'}>
                        {SALES_STATUS_LABELS[o.status]}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{o.lineCount}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatMoney(o.total, o.currency)}
                    </TableCell>
                    <TableCell>{formatDateTime(o.createdAt)}</TableCell>
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
        <SalesOrderFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/inventory/sales-orders/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
