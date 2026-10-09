import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { TicketsPanel } from '@/features/helpdesk/TicketsPanel'
import { ProductStockPanel } from '@/features/inventory/ProductStockPanel'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { ArchiveControls } from '@/features/records/ArchiveControls'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { ProductView } from '@/lib/api/types'
import { formatMoney } from '@/lib/format'
import { ProductFormDialog } from './ProductFormDialog'
import { KIND_LABELS } from './schemas'

export function ProductDetailPage() {
  const { productId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const product = useQuery({
    queryKey: ['product', productId],
    queryFn: () => api.get<ProductView>(`/products/${productId}`),
  })
  const back = (
    <Link to="/app/products" className="text-sm underline-offset-4 hover:underline">
      ← Products
    </Link>
  )
  if (product.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (product.isError)
    return (
      <>
        {back}
        <ErrorState error={product.error} onRetry={() => void product.refetch()} />
      </>
    )

  const p = product.data
  const archived = p.archivedAt !== null
  const canManage = can(PERMISSIONS.productManage)
  function stored(updated: ProductView) {
    queryClient.setQueryData(['product', updated.id], updated)
    void queryClient.invalidateQueries({ queryKey: ['products'] })
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={p.name}
        description={`${p.sku} · ${KIND_LABELS[p.kind]}`}
        actions={
          canManage && (
            <>
              {!archived && (
                <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                  Edit
                </Button>
              )}
              <ArchiveControls
                name={p.name}
                archived={archived}
                onArchive={async () =>
                  stored(await api.post<ProductView>(`/products/${p.id}/archive`))
                }
                onRestore={async () =>
                  stored(await api.post<ProductView>(`/products/${p.id}/restore`))
                }
              />
            </>
          )
        }
      />
      {archived && (
        <p role="status" className="rounded-md border bg-muted/40 px-3 py-2 text-sm">
          This product is archived. Restore it to make changes or add activity, tasks and files.
        </p>
      )}
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {p.description && <p className="whitespace-pre-wrap">{p.description}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Unit</dt>
            <dd>{p.unit}</dd>
            <dt className="text-muted-foreground">List price</dt>
            <dd>
              {p.listPrice != null && p.currency ? formatMoney(p.listPrice, p.currency) : '—'}
            </dd>
          </dl>
        </CardContent>
      </Card>
      <ProductStockPanel productId={p.id} kind={p.kind} />
      <TicketsPanel productId={p.id} />
      <SubjectTasksPanel
        subjectType="PRODUCT"
        subjectId={p.id}
        label={p.name}
        archived={archived}
      />
      <ActivityPanel subjectType="PRODUCT" subjectId={p.id} archived={archived} />
      <DocumentsPanel subjectType="PRODUCT" subjectId={p.id} archived={archived} />
      {editing && (
        <ProductFormDialog
          product={p}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            stored(saved)
            setEditing(false)
          }}
        />
      )}
    </div>
  )
}
