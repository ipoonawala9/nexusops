import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PartyKind, PartyRef, PartySummary } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

/** Pick a person or organization: a search box over the server's first 20 matches plus a select. '' = none. */
export function PartyPicker({
  id,
  label,
  kind,
  value,
  onChange,
  current,
  error,
  noneLabel = 'None',
}: {
  id: string
  label: string
  kind?: PartyKind
  value: string
  onChange: (id: string, party: PartyRef | null) => void
  current?: PartyRef | null
  error?: string
  noneLabel?: string
}) {
  const api = useApi()
  const [search, setSearch] = useState('')
  const [picked, setPicked] = useState<PartyRef | null>(null)
  const parties = useQuery({
    queryKey: ['party-picker', kind ?? 'ANY', search.trim()],
    queryFn: () =>
      api.get<Page<PartySummary>>(`/parties?${toQuery({ kind, q: search.trim(), size: 20 })}`),
  })
  const options: PartyRef[] = (parties.data?.items ?? []).map((p) => ({ id: p.id, name: p.name }))
  const pinned = [current, picked].filter(
    (p, i, all): p is PartyRef => !!p && all.findIndex((o) => o?.id === p.id) === i,
  )
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
            const chosen =
              options.find((p) => p.id === e.target.value) ??
              pinned.find((p) => p.id === e.target.value) ??
              null
            setPicked(chosen)
            onChange(e.target.value, chosen)
          }}
        >
          <option value="">{noneLabel}</option>
          {pinned.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
          {options
            .filter((p) => !pinned.some((o) => o.id === p.id))
            .map((p) => (
              <option key={p.id} value={p.id}>
                {p.name}
              </option>
            ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
