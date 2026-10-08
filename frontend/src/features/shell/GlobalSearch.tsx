import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { Input } from '@/components/ui/input'
import { subjectPath } from '@/features/records/SubjectLink'
import { useApi } from '@/lib/api/ApiContext'
import type { SearchHit } from '@/lib/api/types'
import { toQuery } from '@/lib/query'

const TYPE_LABELS: Record<string, string> = {
  PARTY: 'Directory',
  LEAD: 'Lead',
  OPPORTUNITY: 'Opportunity',
  PRODUCT: 'Product',
  PURCHASE_ORDER: 'Purchase order',
  SALES_ORDER: 'Sales order',
}

/** Workspace search (D12): 2+ characters, debounced; results are only the record types you may read. */
export function GlobalSearch() {
  const api = useApi()
  const container = useRef<HTMLDivElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const [text, setText] = useState('')
  const [query, setQuery] = useState('')
  const [open, setOpen] = useState(false)

  useEffect(() => {
    const handle = setTimeout(() => setQuery(text.trim()), 250)
    return () => clearTimeout(handle)
  }, [text])

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      const typing =
        target &&
        (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable)
      if (event.key === '/' && !typing) {
        event.preventDefault()
        input.current?.focus()
      }
    }
    function onPointerDown(event: MouseEvent) {
      if (!container.current?.contains(event.target as Node)) setOpen(false)
    }
    document.addEventListener('keydown', onKey)
    document.addEventListener('mousedown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.removeEventListener('mousedown', onPointerDown)
    }
  }, [])

  const results = useQuery({
    queryKey: ['search', query],
    queryFn: () => api.get<SearchHit[]>(`/search?${toQuery({ q: query })}`),
    enabled: query.length >= 2,
    placeholderData: keepPreviousData,
  })

  function clear() {
    setText('')
    setQuery('')
  }

  return (
    <div ref={container} className="relative mb-6 max-w-xl">
      <Input
        ref={input}
        type="search"
        aria-label="Search records"
        placeholder="Search people, leads, deals and products (press /)"
        autoComplete="off"
        value={text}
        onChange={(e) => {
          setText(e.target.value)
          setOpen(true)
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={(e) => {
          if (e.key === 'Escape') clear()
        }}
      />
      {open && query.length >= 2 && (results.isError || results.data) && (
        <div className="absolute z-20 mt-1 w-full rounded-md border bg-background p-1 shadow-md">
          {results.isError ? (
            <p className="px-2 py-1.5 text-sm text-muted-foreground">Search failed. Try again.</p>
          ) : results.data?.length === 0 ? (
            <p className="px-2 py-1.5 text-sm text-muted-foreground">No matches.</p>
          ) : (
            <ul aria-label="Search results">
              {results.data?.map((hit) => {
                const path = subjectPath(hit.type, hit.id)
                return (
                  <li key={`${hit.type}-${hit.id}`}>
                    {path && (
                      <Link
                        to={path}
                        onClick={clear}
                        className="flex items-baseline justify-between gap-3 rounded px-2 py-1.5 text-sm hover:bg-muted"
                      >
                        <span>
                          <span className="font-medium">{hit.label}</span>
                          {hit.detail && (
                            <span className="text-muted-foreground"> · {hit.detail}</span>
                          )}
                        </span>
                        <span className="text-xs text-muted-foreground">
                          {TYPE_LABELS[hit.type] ?? hit.type}
                          {hit.archived && ' · archived'}
                        </span>
                      </Link>
                    )}
                  </li>
                )
              })}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
