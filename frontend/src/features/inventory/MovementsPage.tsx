import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { MovementKind, MovementView, Page } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { MOVEMENT_KIND_LABELS } from './labels'
import { MovementsTable } from './MovementsTable'
import { WarehouseSelect } from './WarehouseSelect'

const SIZE = 50

/** The whole ledger, newest first; filters live in the URL so a product's panel can link here. */
export function MovementsPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const productId = params.get('productId') ?? ''
  const warehouseId = params.get('warehouseId') ?? ''
  const kindParam = params.get('kind') ?? ''
  const kind = kindParam in MOVEMENT_KIND_LABELS ? (kindParam as MovementKind) : ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)

  const movements = useQuery({
    queryKey: ['inventory', 'movements', { productId, warehouseId, kind, page }],
    queryFn: () =>
      api.get<Page<MovementView>>(
        `/inventory/movements?${toQuery({ productId, warehouseId, kind, page, size: SIZE })}`,
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

  // the product's name comes with its movements, so no catalog permission is needed to title the page
  const product = productId
    ? movements.data?.items.find((m) => m.product.id === productId)?.product
    : undefined
  const title = product ? `Movements of ${product.sku} · ${product.name}` : 'Movements'

  return (
    <>
      <PageHeader title={title} description="Every change to on-hand stock, newest first." />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <WarehouseSelect
          id="movements-warehouse"
          label="Warehouse"
          blankLabel="All warehouses"
          value={warehouseId}
          onChange={(id) => update({ warehouseId: id, page: '' })}
        />
        <Field id="movements-kind" label="Movement">
          <NativeSelect
            id="movements-kind"
            value={kind}
            onChange={(e) => update({ kind: e.target.value, page: '' })}
          >
            <option value="">Any movement</option>
            {Object.entries(MOVEMENT_KIND_LABELS).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </NativeSelect>
        </Field>
      </div>
      {movements.isPending ? (
        <ListSkeleton />
      ) : movements.isError ? (
        <ErrorState error={movements.error} onRetry={() => void movements.refetch()} />
      ) : movements.data.items.length === 0 ? (
        <EmptyState
          title="No movements match these filters."
          description="Try another warehouse or movement type."
        />
      ) : (
        <>
          <MovementsTable movements={movements.data.items} showProduct={!productId} />
          <Pagination
            page={movements.data.page}
            size={movements.data.size}
            total={movements.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
    </>
  )
}
