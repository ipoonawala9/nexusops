import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { Fragment, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import type { AuditEvent, Page } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'

const SIZE = 25
const FILTERS = ['action', 'entityType', 'from', 'to'] as const

/**
 * Local calendar days → ISO instants (the API expects ISO-8601 instants). A value that isn't a real day (e.g. a
 * hand-edited `?from=garbage`) is treated as absent instead of throwing a RangeError.
 */
function instant(day: string, time: string): string | undefined {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(day)) return undefined
  const date = new Date(`${day}T${time}`)
  return Number.isNaN(date.getTime()) ? undefined : date.toISOString()
}
const dayStart = (day: string) => instant(day, '00:00:00')
const dayEnd = (day: string) => instant(day, '23:59:59.999')
function shortId(id: string | null): string {
  return id ? id.slice(0, 8) : '—'
}

export function AuditPage() {
  const api = useApi()
  const [params, setParams] = useSearchParams()
  const filters = Object.fromEntries(FILTERS.map((key) => [key, params.get(key) ?? ''])) as Record<
    (typeof FILTERS)[number],
    string
  >
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [open, setOpen] = useState<string | null>(null)

  const events = useQuery({
    queryKey: ['audit-events', filters, page],
    queryFn: () =>
      api.get<Page<AuditEvent>>(
        `/audit-events?${toQuery({
          action: filters.action.trim(),
          entityType: filters.entityType.trim(),
          from: dayStart(filters.from),
          to: dayEnd(filters.to),
          page,
          size: SIZE,
        })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function onFilter(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const next = new URLSearchParams()
    for (const key of FILTERS) {
      const value = data.get(key)
      if (typeof value === 'string' && value.trim()) next.set(key, value.trim())
    }
    setParams(next, { replace: true })
  }

  function goToPage(p: number) {
    const next = new URLSearchParams(params)
    if (p > 0) next.set('page', String(p))
    else next.delete('page')
    setParams(next, { replace: true })
  }

  const fieldErrors = events.error instanceof ApiError ? (events.error.problem.errors ?? []) : []

  return (
    <>
      <PageHeader
        title="Audit log"
        description="Every security-relevant action in this workspace. Read-only."
      />
      <form onSubmit={onFilter} className="mb-4 grid gap-3 sm:grid-cols-5 sm:items-end">
        <div className="space-y-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Input
            id="audit-action"
            name="action"
            defaultValue={filters.action}
            placeholder="e.g. LoginFailed"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-entity">Entity type</Label>
          <Input
            id="audit-entity"
            name="entityType"
            defaultValue={filters.entityType}
            placeholder="e.g. User"
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-from">From</Label>
          <Input id="audit-from" name="from" type="date" defaultValue={filters.from} />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="audit-to">To</Label>
          <Input id="audit-to" name="to" type="date" defaultValue={filters.to} />
        </div>
        <Button type="submit" variant="outline">
          Apply filters
        </Button>
      </form>

      {events.isPending ? (
        <ListSkeleton />
      ) : events.isError ? (
        fieldErrors.length ? (
          <div role="alert" className="text-sm text-destructive">
            {fieldErrors.map((e) => (
              <p key={e.field}>{e.message}</p>
            ))}
          </div>
        ) : (
          <ErrorState error={events.error} onRetry={() => void events.refetch()} />
        )
      ) : events.data.items.length === 0 ? (
        <EmptyState
          title="No events match these filters."
          description="Try a wider date range or clear the filters."
          action={
            page > 0 && (
              <Button variant="outline" onClick={() => goToPage(0)}>
                Back to first page
              </Button>
            )
          }
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>When</TableHead>
                  <TableHead>Action</TableHead>
                  <TableHead>Actor</TableHead>
                  <TableHead>Entity</TableHead>
                  <TableHead>
                    <span className="sr-only">Details</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {events.data.items.map((event) => {
                  const expanded = open === event.id
                  const details = Object.fromEntries(
                    Object.entries({
                      before: event.before,
                      after: event.after,
                      metadata: event.metadata,
                      requestId: event.requestId,
                      ip: event.ip,
                      userAgent: event.userAgent,
                    }).filter(([, value]) => value !== null && value !== undefined),
                  )
                  return (
                    <Fragment key={event.id}>
                      <TableRow>
                        <TableCell className="whitespace-nowrap">
                          {formatDateTime(event.occurredAt)}
                        </TableCell>
                        <TableCell className="font-medium">{event.action}</TableCell>
                        <TableCell>
                          {event.actorType} {shortId(event.actorId)}
                        </TableCell>
                        <TableCell>
                          {event.entityType ?? '—'} {event.entityId ?? ''}
                        </TableCell>
                        <TableCell className="text-right">
                          <Button
                            variant="ghost"
                            size="sm"
                            aria-expanded={expanded}
                            aria-controls={`audit-${event.id}`}
                            aria-label={`${expanded ? 'Hide' : 'Show'} details for ${event.action}`}
                            onClick={() => setOpen(expanded ? null : event.id)}
                          >
                            {expanded ? 'Hide' : 'Details'}
                          </Button>
                        </TableCell>
                      </TableRow>
                      {expanded && (
                        <TableRow id={`audit-${event.id}`}>
                          <TableCell colSpan={5}>
                            <pre className="max-h-80 overflow-auto rounded bg-muted p-3 text-xs">
                              {JSON.stringify(details, null, 2)}
                            </pre>
                          </TableCell>
                        </TableRow>
                      )}
                    </Fragment>
                  )
                })}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={events.data.page}
            size={events.data.size}
            total={events.data.total}
            onPage={goToPage}
          />
        </>
      )}
    </>
  )
}
