import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
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
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { AssigneeView, CategoryView, Page, TicketSummary } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { PRIORITIES, PRIORITY_LABELS, TICKET_STATUS_LABELS } from './labels'
import { currentTarget } from './sla'
import { SlaBadge } from './SlaBadge'
import { TicketFormDialog } from './TicketFormDialog'

const SIZE = 20
const VIEWS: Array<{ value: string; label: string; status?: string }> = [
  { value: '', label: 'Open tickets' },
  { value: 'PENDING', label: 'Waiting on customer', status: 'PENDING' },
  { value: 'RESOLVED', label: 'Resolved', status: 'RESOLVED' },
  { value: 'CLOSED', label: 'Closed', status: 'CLOSED' },
  { value: 'all', label: 'All tickets', status: 'NEW,OPEN,PENDING,RESOLVED,CLOSED' },
]

export function TicketsPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const view = params.get('view') ?? ''
  const priority = params.get('priority') ?? ''
  const assignee = params.get('assignee') ?? ''
  const category = params.get('category') ?? ''
  const sla = params.get('sla') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)
  const status = VIEWS.find((v) => v.value === view)?.status

  const tickets = useQuery({
    queryKey: ['helpdesk', 'tickets', { q, view, priority, assignee, category, sla, page }],
    queryFn: () =>
      api.get<Page<TicketSummary>>(
        `/helpdesk/tickets?${toQuery({
          q,
          status,
          priority,
          assignee,
          categoryId: category,
          sla,
          page,
          size: SIZE,
        })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })
  const agents = useQuery({
    queryKey: ['helpdesk', 'agents', ''],
    queryFn: () => api.get<AssigneeView[]>('/helpdesk/agents'),
  })
  const categories = useQuery({
    queryKey: ['helpdesk', 'categories', false],
    queryFn: () => api.get<CategoryView[]>('/helpdesk/categories'),
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  const filtered = Boolean(q || view || priority || assignee || category || sla)

  return (
    <>
      <PageHeader
        title="Tickets"
        description="Customer issues with their SLA, newest first."
        actions={
          can(PERMISSIONS.ticketManage) && (
            <Button onClick={() => setCreating(true)}>New ticket</Button>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="ticket-q">Search tickets</Label>
            <Input id="ticket-q" name="q" defaultValue={q} placeholder="Number or subject" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-view">Show</Label>
          <NativeSelect
            id="ticket-view"
            value={view}
            onChange={(e) => update({ view: e.target.value, page: '' })}
          >
            {VIEWS.map((v) => (
              <option key={v.value} value={v.value}>
                {v.label}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-priority">Priority</Label>
          <NativeSelect
            id="ticket-priority"
            value={priority}
            onChange={(e) => update({ priority: e.target.value, page: '' })}
          >
            <option value="">Any priority</option>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {PRIORITY_LABELS[p]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-assignee">Assignee</Label>
          <NativeSelect
            id="ticket-assignee"
            value={assignee}
            onChange={(e) => update({ assignee: e.target.value, page: '' })}
          >
            <option value="">Anyone</option>
            <option value="me">Assigned to me</option>
            <option value="unassigned">Unassigned</option>
            {(agents.data ?? []).map((a) => (
              <option key={a.id} value={a.id}>
                {a.name}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-category">Category</Label>
          <NativeSelect
            id="ticket-category"
            value={category}
            onChange={(e) => update({ category: e.target.value, page: '' })}
          >
            <option value="">Any category</option>
            {(categories.data ?? []).map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="ticket-sla">SLA</Label>
          <NativeSelect
            id="ticket-sla"
            value={sla}
            onChange={(e) => update({ sla: e.target.value, page: '' })}
          >
            <option value="">Any</option>
            <option value="breached">Breached</option>
            <option value="at_risk">At risk</option>
          </NativeSelect>
        </div>
      </div>
      {tickets.isPending ? (
        <ListSkeleton />
      ) : tickets.isError ? (
        <ErrorState error={tickets.error} onRetry={() => void tickets.refetch()} />
      ) : tickets.data.items.length === 0 ? (
        <EmptyState
          title={filtered ? 'No tickets match these filters.' : 'No open tickets.'}
          description="New tickets appear here as customers get in touch."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Number</TableHead>
                  <TableHead>Subject</TableHead>
                  <TableHead>Requester</TableHead>
                  <TableHead>Priority</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Assignee</TableHead>
                  <TableHead>SLA</TableHead>
                  <TableHead>Created</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {tickets.data.items.map((t) => {
                  const target = currentTarget(t)
                  return (
                    <TableRow key={t.id}>
                      <TableCell>
                        <Link
                          to={`/app/helpdesk/tickets/${t.id}`}
                          className="font-medium underline-offset-4 hover:underline"
                        >
                          {t.number}
                        </Link>
                      </TableCell>
                      <TableCell className="max-w-md min-w-48 whitespace-normal">
                        {t.subject}
                      </TableCell>
                      <TableCell>{t.requester?.name ?? '—'}</TableCell>
                      <TableCell>
                        <Badge variant={t.priority === 'URGENT' ? 'destructive' : 'outline'}>
                          {PRIORITY_LABELS[t.priority]}
                        </Badge>
                      </TableCell>
                      <TableCell>{TICKET_STATUS_LABELS[t.status]}</TableCell>
                      <TableCell>
                        {t.assignee?.name ?? (
                          <span className="text-muted-foreground">Unassigned</span>
                        )}
                      </TableCell>
                      <TableCell>
                        <SlaBadge state={target.state} due={target.due} target={target.target} />
                      </TableCell>
                      <TableCell>{formatDateTime(t.createdAt)}</TableCell>
                    </TableRow>
                  )
                })}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={tickets.data.page}
            size={tickets.data.size}
            total={tickets.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <TicketFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/helpdesk/tickets/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
