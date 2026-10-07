import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
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
import type { Page, ProductView } from '@/lib/api/types'
import { formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { ProductFormDialog } from './ProductFormDialog'
import { KIND_LABELS } from './schemas'

const SIZE = 20

export function ProductsPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const kind = params.get('kind') ?? ''
  const archived = params.get('status') === 'archived'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const canManage = can(PERMISSIONS.productManage)

  const products = useQuery({
    queryKey: ['products', { q, kind, archived, page }],
    queryFn: () =>
      api.get<Page<ProductView>>(
        `/products?${toQuery({ q, kind, archived: archived ? 'true' : '', page, size: SIZE })}`,
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
        title="Products"
        description="Products and services you sell or stock."
        actions={canManage && <Button onClick={() => setCreating(true)}>New product</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="products-q">Search products</Label>
            <Input id="products-q" name="q" defaultValue={q} placeholder="SKU or name" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="products-kind">Kind</Label>
          <NativeSelect
            id="products-kind"
            value={kind}
            onChange={(e) => update({ kind: e.target.value, page: '' })}
          >
            <option value="">Any kind</option>
            <option value="GOODS">Goods</option>
            <option value="SERVICE">Services</option>
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="products-status">Status</Label>
          <NativeSelect
            id="products-status"
            value={archived ? 'archived' : ''}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Active</option>
            <option value="archived">Archived</option>
          </NativeSelect>
        </div>
      </div>

      {products.isPending ? (
        <ListSkeleton />
      ) : products.isError ? (
        <ErrorState error={products.error} onRetry={() => void products.refetch()} />
      ) : products.data.items.length === 0 ? (
        <EmptyState
          title={q || kind || archived ? 'No products match these filters.' : 'No products yet.'}
          description={canManage ? 'Add the products and services you sell.' : 'Try a different search or filter.'}
          action={
            page > 0 && (
              <Button variant="outline" onClick={() => update({ page: '' })}>
                Back to first page
              </Button>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Name</TableHead>
                  <TableHead>SKU</TableHead>
                  <TableHead>Kind</TableHead>
                  <TableHead>Unit</TableHead>
                  <TableHead>List price</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {products.data.items.map((product) => (
                  <TableRow key={product.id}>
                    <TableCell>
                      <Link
                        to={`/app/products/${product.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {product.name}
                      </Link>
                    </TableCell>
                    <TableCell className="font-mono text-xs">{product.sku}</TableCell>
                    <TableCell>{KIND_LABELS[product.kind]}</TableCell>
                    <TableCell>{product.unit}</TableCell>
                    <TableCell>
                      {product.listPrice != null && product.currency
                        ? formatMoney(product.listPrice, product.currency)
                        : '—'}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={products.data.page}
            size={products.data.size}
            total={products.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}

      {creating && (
        <ProductFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/products/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
