import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type {
  ArticleSummary,
  PartyView,
  TicketContext,
  TicketSummary,
  TicketView,
} from '@/lib/api/types'
import { TICKET_STATUS_LABELS } from './labels'

function TicketList({ label, tickets }: { label: string; tickets: TicketSummary[] }) {
  if (tickets.length === 0) return null
  return (
    <section aria-label={label} className="space-y-1">
      <h3 className="font-medium">{label}</h3>
      <ul className="space-y-1">
        {tickets.map((t) => (
          <li key={t.id} className="flex flex-wrap gap-x-2">
            <Link
              to={`/app/helpdesk/tickets/${t.id}`}
              className="underline-offset-4 hover:underline"
            >
              {t.number}
            </Link>
            <span>{t.subject}</span>
            <span className="text-muted-foreground">{TICKET_STATUS_LABELS[t.status]}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

/** D12: who is asking, what they asked before, what looks the same, and what the knowledge base already says. */
export function TicketContextPanel({
  ticket,
  onInsertArticle,
}: {
  ticket: TicketView
  /** null when the user can't reply (no manage permission, or the ticket is closed). */
  onInsertArticle: ((article: ArticleSummary) => void) | null
}) {
  const api = useApi()
  const can = useCan()
  const context = useQuery({
    queryKey: ['helpdesk', 'context', ticket.id],
    queryFn: () => api.get<TicketContext>(`/helpdesk/tickets/${ticket.id}/context`),
  })
  const requesterId = ticket.requester?.id
  const party = useQuery({
    queryKey: ['party', requesterId],
    queryFn: () => api.get<PartyView>(`/parties/${requesterId}`),
    enabled: !!requesterId && can(PERMISSIONS.partyRead),
  })
  const articles = context.data?.suggestedArticles ?? []
  return (
    <Card role="region" aria-label="Context">
      <CardHeader>
        <CardTitle>Context</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        {party.data && (
          <section aria-label="Requester" className="space-y-1">
            <h3 className="font-medium">Requester</h3>
            <p>
              <Link
                to={`/app/directory/${party.data.id}`}
                className="underline-offset-4 hover:underline"
              >
                {party.data.name}
              </Link>
              {party.data.organization && (
                <span className="text-muted-foreground"> · {party.data.organization.name}</span>
              )}
            </p>
            {party.data.email && <p>{party.data.email}</p>}
            {party.data.phone && <p>{party.data.phone}</p>}
            {!party.data.email && (
              <p className="text-muted-foreground">
                No email address: replies won&apos;t be emailed.
              </p>
            )}
          </section>
        )}
        {context.isError && (
          <p className="text-muted-foreground">The context couldn&apos;t be loaded.</p>
        )}
        <TicketList label="Previous tickets" tickets={context.data?.previousTickets ?? []} />
        <TicketList label="Possible duplicates" tickets={context.data?.possibleDuplicates ?? []} />
        {articles.length > 0 && (
          <section aria-label="Suggested articles" className="space-y-2">
            <h3 className="font-medium">Suggested articles</h3>
            <ul className="space-y-2">
              {articles.map((a) => (
                <li key={a.id} className="space-y-1">
                  <Link
                    to={`/app/helpdesk/articles/${a.id}`}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {a.title}
                  </Link>
                  <p className="text-muted-foreground">{a.excerpt}</p>
                  {onInsertArticle && (
                    <Button
                      size="sm"
                      variant="outline"
                      aria-label={`Insert ${a.title} into reply`}
                      onClick={() => onInsertArticle(a)}
                    >
                      Insert into reply
                    </Button>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}
        {context.data &&
          context.data.previousTickets.length === 0 &&
          context.data.possibleDuplicates.length === 0 &&
          articles.length === 0 && (
            <p className="text-muted-foreground">
              No earlier tickets from this requester and no matching articles.
            </p>
          )}
      </CardContent>
    </Card>
  )
}
