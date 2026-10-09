import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, ProductView, TicketProductRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Any catalog product (goods or service): a search box over the first 20 matches plus a select. '' = none. */
export function CatalogProductPicker({
  id,
  label,
  value,
  onChange,
  current,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (id: string, product: TicketProductRef | null) => void
  current?: TicketProductRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<TicketProductRef | null>(null)
  const products = useQuery({
    queryKey: ['catalog-product-picker', search.trim()],
    queryFn: () =>
      api.get<Page<ProductView>>(`/products?${toQuery({ q: search.trim(), size: 20 })}`),
  })
  const options: TicketProductRef[] = (products.data?.items ?? []).map((p) => ({
    id: p.id,
    sku: p.sku,
    name: p.name,
  }))
  const pinned = [current, picked].filter(
    (p, i, list): p is TicketProductRef => !!p && list.findIndex((o) => o?.id === p.id) === i,
  )
  const all = [...pinned, ...options.filter((p) => !pinned.some((o) => o.id === p.id))]
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label={`Find ${label.toLowerCase()}`}>
        <Input
          id={searchId}
          type="search"
          autoComplete="off"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field id={id} label={label} error={error}>
        <NativeSelect
          id={id}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error)}
          onChange={(e) => {
            const chosen = all.find((p) => p.id === e.target.value) ?? null
            setPicked(chosen)
            onChange(e.target.value, chosen)
          }}
        >
          <option value="">None</option>
          {all.map((p) => (
            <option key={p.id} value={p.id}>
              {p.sku} · {p.name}
            </option>
          ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
