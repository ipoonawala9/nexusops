import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, ProductView, StockProductRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Pick a stocked (GOODS) product: a search box over the first 20 matches plus a select. '' = none. */
export function ProductPicker({
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
  onChange: (id: string, product: StockProductRef | null) => void
  current?: StockProductRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const products = useQuery({
    queryKey: ['product-picker', search.trim()],
    queryFn: () =>
      api.get<Page<ProductView>>(
        `/products?${toQuery({ kind: 'GOODS', q: search.trim(), size: 20 })}`,
      ),
  })
  const options: StockProductRef[] = (products.data?.items ?? []).map((p) => ({
    id: p.id,
    sku: p.sku,
    name: p.name,
    unit: p.unit,
  }))
  const all = current && !options.some((p) => p.id === current.id) ? [current, ...options] : options
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
          onChange={(e) =>
            onChange(e.target.value, all.find((p) => p.id === e.target.value) ?? null)
          }
        >
          <option value="">Choose…</option>
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
