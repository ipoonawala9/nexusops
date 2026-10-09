import { useQuery } from '@tanstack/react-query'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { CategoryRef, CategoryView } from '@/lib/api/types'

/** Active ticket categories ('' = none). A ticket's current category stays listed even if archived since. */
export function CategorySelect({
  id,
  label,
  value,
  onChange,
  current,
  error,
  blankLabel = 'No category',
}: {
  id: string
  label: string
  value: string
  onChange: (id: string, category: CategoryView | null) => void
  current?: CategoryRef | null
  error?: string
  blankLabel?: string
}) {
  const api = useApi()
  const canList = useCan()(PERMISSIONS.ticketRead)
  const categories = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
    enabled: canList,
  })
  const options = categories.data ?? []
  const extra = current && !options.some((c) => c.id === current.id) ? [current] : []
  return (
    <Field id={id} label={label} error={error}>
      <NativeSelect
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error)}
        onChange={(e) =>
          onChange(e.target.value, options.find((c) => c.id === e.target.value) ?? null)
        }
      >
        <option value="">{blankLabel}</option>
        {extra.map((c) => (
          <option key={c.id} value={c.id}>
            {canList ? `${c.name} (archived)` : c.name}
          </option>
        ))}
        {options.map((c) => (
          <option key={c.id} value={c.id}>
            {c.name}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}
