import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
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
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, StockRow } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { AdjustStockDialog } from './AdjustStockDialog'
import { formatQuantity } from './quantity'
import { TransferStockDialog } from './TransferStockDialog'
import { WarehouseSelect } from './WarehouseSelect'

const SIZE = 50

export function StockPage() {
  const api = useApi()
  const can = useCan()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const warehouseId = params.get('warehouseId') ?? ''
  const belowMin = params.get('belowMin') === 'true'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [dialog, setDialog] = useState<'count' | 'transfer' | null>(null)
  const canAdjust = can(PERMISSIONS.stockAdjust)

  const stock = useQuery({
    queryKey: ['inventory', 'stock', { q, warehouseId, belowMin, page }],
    queryFn: () =>
      api.get<Page<StockRow>>(
        `/inventory/stock?${toQuery({ q, warehouseId, belowMin: belowMin ? 'true' : '', page, size: SIZE })}`,
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
        title="Stock"
        description="On hand, reserved, available and on order, per product and warehouse."
        actions={
          canAdjust && (
            <>
              <Button variant="outline" onClick={() => setDialog('transfer')}>
                Transfer stock
              </Button>
              <Button onClick={() => setDialog('count')}>Count stock</Button>
            </>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="stock-q">Search stock</Label>
            <Input id="stock-q" name="q" defaultValue={q} placeholder="SKU or name" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <WarehouseSelect
          id="stock-warehouse"
          label="Warehouse"
          blankLabel="All warehouses"
          value={warehouseId}
          onChange={(id) => update({ warehouseId: id, page: '' })}
        />
        <label className="flex items-center gap-2 pb-2 text-sm">
          <input
            type="checkbox"
            checked={belowMin}
            onChange={(e) => update({ belowMin: e.target.checked ? 'true' : '', page: '' })}
          />
          Below minimum only
        </label>
      </div>

      {stock.isPending ? (
        <ListSkeleton />
      ) : stock.isError ? (
        <ErrorState error={stock.error} onRetry={() => void stock.refetch()} />
      ) : stock.data.items.length === 0 ? (
        <EmptyState
          title={q || warehouseId || belowMin ? 'Nothing matches these filters.' : 'No stock yet.'}
          description="Count opening stock, receive a purchase order or set a reorder rule to see products here."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>SKU</TableHead>
                  <TableHead>Product</TableHead>
                  <TableHead>Warehouse</TableHead>
                  <TableHead className="text-right">On hand</TableHead>
                  <TableHead className="text-right">Reserved</TableHead>
                  <TableHead className="text-right">Available</TableHead>
                  <TableHead className="text-right">On order</TableHead>
                  <TableHead>Min – max</TableHead>
                  <TableHead>Status</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {stock.data.items.map((r) => (
                  <TableRow key={`${r.product.id}:${r.warehouse.id}`}>
                    <TableCell>
                      <Link
                        to={`/app/products/${r.product.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {r.product.sku}
                      </Link>
                    </TableCell>
                    <TableCell>{r.product.name}</TableCell>
                    <TableCell>{r.warehouse.code}</TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(r.onHand)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(r.reserved)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(r.available)}
                    </TableCell>
                    <TableCell className="text-right tabular-nums">
                      {formatQuantity(r.onOrder)}
                    </TableCell>
                    <TableCell>
                      {r.minQuantity != null && r.maxQuantity != null
                        ? `${formatQuantity(r.minQuantity)} – ${formatQuantity(r.maxQuantity)}`
                        : 'No rule'}
                    </TableCell>
                    <TableCell>
                      {r.belowMin ? <Badge variant="destructive">Below minimum</Badge> : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={stock.data.page}
            size={stock.data.size}
            total={stock.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {dialog === 'count' && <AdjustStockDialog onClose={() => setDialog(null)} />}
      {dialog === 'transfer' && <TransferStockDialog onClose={() => setDialog(null)} />}
    </>
  )
}
