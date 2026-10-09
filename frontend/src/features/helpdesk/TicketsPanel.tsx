import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PartyRef, TicketSummary } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { TICKET_STATUS_LABELS } from './labels'
import { currentTarget } from './sla'
import { SlaBadge } from './SlaBadge'
import { TicketFormDialog } from './TicketFormDialog'

const ALL_STATUSES = 'NEW,OPEN,PENDING,RESOLVED,CLOSED'

/** The latest tickets of a requester or about a product (D17), when HelpDesk is on. */
export function TicketsPanel({
  requester,
  productId,
}: {
  requester?: PartyRef
  productId?: string
}) {
  const session = useTenantSession()
  const can = useCan()
  const api = useApi()
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)
  const enabled =
    session.state.status === 'authenticated' &&
    session.state.profile.modules.includes('HELPDESK') &&
    can(PERMISSIONS.ticketRead)
  const filter = requester ? { requesterId: requester.id } : { productId }
  const tickets = useQuery({
    queryKey: ['helpdesk', 'tickets', filter],
    queryFn: () =>
      api.get<Page<TicketSummary>>(
        `/helpdesk/tickets?${toQuery({ ...filter, status: ALL_STATUSES, size: 10 })}`,
      ),
    enabled,
  })
  if (!enabled) return null
  const items = tickets.data?.items ?? []
  return (
    <Card role="region" aria-label="Tickets">
      <CardHeader className="flex flex-row items-center justify-between">
        <CardTitle>Tickets</CardTitle>
        {requester && can(PERMISSIONS.ticketManage) && (
          <Button size="sm" variant="outline" onClick={() => setCreating(true)}>
            New ticket
          </Button>
        )}
      </CardHeader>
      <CardContent className="text-sm">
        {tickets.isPending ? (
          <p className="text-muted-foreground">Loading tickets…</p>
        ) : tickets.isError ? (
          <div className="flex flex-wrap items-center gap-2">
            <p className="text-muted-foreground">Couldn&apos;t load tickets.</p>
            <Button size="sm" variant="outline" onClick={() => void tickets.refetch()}>
              Retry
            </Button>
          </div>
        ) : items.length === 0 ? (
          <p className="text-muted-foreground">No tickets yet.</p>
        ) : (
          <ul className="space-y-1">
            {items.map((t) => {
              const target = currentTarget(t)
              return (
                <li key={t.id} className="flex flex-wrap items-center gap-2">
                  <Link
                    to={`/app/helpdesk/tickets/${t.id}`}
                    className="underline-offset-4 hover:underline"
                  >
                    {t.number}
                  </Link>
                  <span>{t.subject}</span>
                  <span className="text-muted-foreground">{TICKET_STATUS_LABELS[t.status]}</span>
                  {(t.status === 'NEW' || t.status === 'OPEN' || t.status === 'PENDING') && (
                    <SlaBadge state={target.state} due={target.due} target={target.target} />
                  )}
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
      {creating && requester && (
        <TicketFormDialog
          requester={requester}
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/helpdesk/tickets/${saved.id}`)
          }}
        />
      )}
    </Card>
  )
}
