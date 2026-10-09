import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { Input } from '@/components/ui/input'
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { useApi } from '@/lib/api/ApiContext'
import type { LinkedRecord, SearchHit } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

const TYPE_LABELS: Record<string, string> = {
  PARTY: 'Directory',
  LEAD: 'Lead',
  OPPORTUNITY: 'Opportunity',
  PRODUCT: 'Product',
  PURCHASE_ORDER: 'Purchase order',
  SALES_ORDER: 'Sales order',
}
/** A ticket can't point at another ticket or at an article. */
const EXCLUDED = new Set(['TICKET', 'KB_ARTICLE'])

interface Option {
  key: string
  text: string
}

function optionOf(type: string, id: string, label: string | null): Option {
  return {
    key: `${type}:${id}`,
    text: `${TYPE_LABELS[type] ?? type} · ${label ?? 'Restricted record'}`,
  }
}

/** Any record the ticket concerns (D3), found with workspace search. Value 'TYPE:uuid', '' = none. */
export function LinkedRecordPicker({
  id,
  value,
  onChange,
  current,
  error,
}: {
  id: string
  value: string
  onChange: (value: string) => void
  current?: LinkedRecord | null
  error?: string
}) {
  const api = useApi()
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [picked, setPicked] = useState<Option | null>(null)
  useEffect(() => {
    const handle = setTimeout(() => setQuery(text.trim()), 250)
    return () => clearTimeout(handle)
  }, [text])
  const hits = useQuery({
    queryKey: ['search', query],
    queryFn: () => api.get<SearchHit[]>(`/search?${toQuery({ q: query })}`),
    enabled: query.length >= 2,
    placeholderData: keepPreviousData,
  })
  const found = (query.length >= 2 ? (hits.data ?? []) : [])
    .filter((h) => !EXCLUDED.has(h.type))
    .map((h) => optionOf(h.type, h.id, h.label))
  const pinned = [
    current ? optionOf(current.type, current.id, current.label) : null,
    picked,
  ].filter((o, i, all): o is Option => !!o && all.findIndex((x) => x?.key === o.key) === i)
  const options = [...pinned, ...found.filter((o) => !pinned.some((p) => p.key === o.key))]
  const searchId = `${id}-search`
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <Field id={searchId} label="Find a related record" hint="An order, a deal, a lead…">
        <Input
          id={searchId}
          type="search"
          autoComplete="off"
          value={text}
          aria-describedby={`${searchId}-hint`}
          onChange={(e) => setText(e.target.value)}
        />
      </Field>
      <Field id={id} label="Related record" error={error}>
        <NativeSelect
          id={id}
          value={value}
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy(id, error)}
          onChange={(e) => {
            setPicked(options.find((o) => o.key === e.target.value) ?? null)
            onChange(e.target.value)
          }}
        >
          <option value="">None</option>
          {options.map((o) => (
            <option key={o.key} value={o.key}>
              {o.text}
            </option>
          ))}
        </NativeSelect>
      </Field>
    </div>
  )
}
