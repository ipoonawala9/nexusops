import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { AssigneeView, MemberRef } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Owner of a lead or opportunity. '' = unassigned. The current and picked owner always stay listed. */
export function OwnerSelect({
  value,
  onChange,
  current,
  error,
}: {
  value: string
  onChange: (id: string) => void
  current?: MemberRef | null
  error?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<MemberRef | null>(null)
  const owners = useQuery({
    queryKey: ['crm-owners', search.trim()],
    queryFn: () => api.get<AssigneeView[]>(`/crm/owners?${toQuery({ q: search.trim() })}`),
  })
  const options = owners.data ?? []
  const pinned = [current, picked].filter(
    (m, i, all): m is MemberRef => !!m && all.findIndex((o) => o?.id === m.id) === i,
  )
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id="field-ownerSearch" label="Find a teammate">
        <Input
          id="field-ownerSearch"
          type="search"
          autoComplete="off"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
      </Field>
      <Field id="field-ownerId" label="Owner" error={error}>
        <NativeSelect
          id="field-ownerId"
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy('field-ownerId', error)}
          onChange={(e) => {
            const id = e.target.value
            setPicked(options.find((m) => m.id === id) ?? pinned.find((m) => m.id === id) ?? null)
            onChange(id)
          }}
        >
          <option value="">Unassigned</option>
          {pinned.map((m) => (
            <option key={m.id} value={m.id}>
              {m.name}
            </option>
          ))}
          {options
            .filter((m) => !pinned.some((p) => p.id === m.id))
            .map((m) => (
              <option key={m.id} value={m.id}>
                {m.name}
              </option>
            ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
