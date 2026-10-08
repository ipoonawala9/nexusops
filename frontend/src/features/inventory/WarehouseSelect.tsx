import { useQuery } from '@tanstack/react-query'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { WarehouseView } from '@/lib/api/types'

/** Active warehouses. With `blankLabel`, '' is an option ("All warehouses", "Choose…"). */
export function WarehouseSelect({
  id,
  label,
  value,
  onChange,
  error,
  blankLabel,
}: {
  id: string
  label: string
  value: string
  onChange: (id: string) => void
  error?: string
  blankLabel?: string
}) {
  const api = useApi()
  const warehouses = useQuery({
    queryKey: ['inventory', 'warehouses', false],
    queryFn: () => api.get<WarehouseView[]>('/inventory/warehouses'),
  })
  return (
    <Field id={id} label={label} error={error}>
      <NativeSelect
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error)}
        onChange={(e) => onChange(e.target.value)}
      >
        {blankLabel !== undefined && <option value="">{blankLabel}</option>}
        {(warehouses.data ?? []).map((w) => (
          <option key={w.id} value={w.id}>
            {w.code} · {w.name}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}
