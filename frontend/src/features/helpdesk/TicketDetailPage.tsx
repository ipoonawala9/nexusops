import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectLink } from '@/features/records/SubjectLink'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type {
  ArticleSummary,
  ArticleView,
  MessageKind,
  MessageView,
  TicketView,
} from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { CHANNEL_LABELS, PRIORITY_LABELS, TICKET_STATUS_LABELS } from './labels'
import { MessageComposer } from './MessageComposer'
import { SlaBadge } from './SlaBadge'
import { TicketActions } from './TicketActions'
import { TicketContextPanel } from './TicketContextPanel'
import { TicketConversation } from './TicketConversation'

function SlaCard({ ticket }: { ticket: TicketView }) {
  const { sla } = ticket
  return (
    <Card role="region" aria-label="SLA">
      <CardHeader>
        <CardTitle>SLA</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="space-y-1">
          <SlaBadge state={sla.firstResponseState} target="First response" />
          <p className="text-muted-foreground">
            {sla.firstRespondedAt
              ? `Answered ${formatDateTime(sla.firstRespondedAt)}`
              : `Due ${formatDateTime(sla.firstResponseDueAt)}`}
          </p>
        </div>
        <div className="space-y-1">
          <SlaBadge state={sla.resolutionState} target="Resolution" />
          <p className="text-muted-foreground">
            {sla.resolvedAt
              ? `Resolved ${formatDateTime(sla.resolvedAt)}`
              : `Due ${formatDateTime(sla.resolutionDueAt)}`}
          </p>
          {sla.pausedAt && (
            <p className="text-muted-foreground">
              Paused since {formatDateTime(sla.pausedAt)} — waiting on the customer doesn&apos;t
              count.
            </p>
          )}
        </div>
        {ticket.reopenCount > 0 && (
          <p className="text-muted-foreground">
            Reopened {ticket.reopenCount} {ticket.reopenCount === 1 ? 'time' : 'times'}
          </p>
        )}
      </CardContent>
    </Card>
  )
}

function DetailsCard({ ticket: t }: { ticket: TicketView }) {
  const can = useCan()
  return (
    <Card role="region" aria-label="Details">
      <CardHeader>
        <CardTitle>Details</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <p className="whitespace-pre-wrap">{t.description}</p>
        <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-2">
          <dt className="text-muted-foreground">Requester</dt>
          <dd>
            {t.requester && can(PERMISSIONS.partyRead) ? (
              <Link
                to={`/app/directory/${t.requester.id}`}
                className="underline-offset-4 hover:underline"
              >
                {t.requester.name}
              </Link>
            ) : t.requester ? (
              t.requester.name
            ) : (
              '—'
            )}
          </dd>
          <dt className="text-muted-foreground">Product</dt>
          <dd>
            {t.product ? (
              <Link
                to={`/app/products/${t.product.id}`}
                className="underline-offset-4 hover:underline"
              >
                {t.product.sku} · {t.product.name}
              </Link>
            ) : (
              '—'
            )}
          </dd>
          <dt className="text-muted-foreground">Related record</dt>
          <dd>
            <SubjectLink subject={t.linked ? { ...t.linked, archived: false } : null} />
          </dd>
          <dt className="text-muted-foreground">Category</dt>
          <dd>{t.category?.name ?? '—'}</dd>
          <dt className="text-muted-foreground">Channel</dt>
          <dd>{CHANNEL_LABELS[t.channel]}</dd>
          <dt className="text-muted-foreground">Assignee</dt>
          <dd>{t.assignee?.name ?? 'Unassigned'}</dd>
          <dt className="text-muted-foreground">Created</dt>
          <dd>
            {formatDateTime(t.createdAt)}
            {t.createdBy ? ` by ${t.createdBy.name}` : ''}
          </dd>
          {t.resolutionNote && (
            <>
              <dt className="text-muted-foreground">Resolution</dt>
              <dd className="whitespace-pre-wrap">{t.resolutionNote}</dd>
            </>
          )}
          {t.closedAt && (
            <>
              <dt className="text-muted-foreground">Closed</dt>
              <dd>{formatDateTime(t.closedAt)}</dd>
            </>
          )}
        </dl>
      </CardContent>
    </Card>
  )
}

/** The route reuses this page when only :ticketId changes, so the body is keyed: a reply draft or an open dialog never follows the user to another ticket. */
export function TicketDetailPage() {
  const { ticketId = '' } = useParams()
  return <TicketDetail key={ticketId} ticketId={ticketId} />
}

function TicketDetail({ ticketId }: { ticketId: string }) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const ticket = useQuery({
    queryKey: ['helpdesk', 'ticket', ticketId],
    queryFn: () => api.get<TicketView>(`/helpdesk/tickets/${ticketId}`),
  })
  const messages = useQuery({
    queryKey: ['helpdesk', 'messages', ticketId],
    queryFn: () => api.get<MessageView[]>(`/helpdesk/tickets/${ticketId}/messages`),
  })
  const [kind, setKind] = useState<MessageKind>('PUBLIC_REPLY')
  const [draft, setDraft] = useState('')

  async function insertArticle(summary: ArticleSummary) {
    try {
      const article = await queryClient.fetchQuery({
        queryKey: ['helpdesk', 'article', summary.id],
        queryFn: () => api.get<ArticleView>(`/helpdesk/articles/${summary.id}`),
      })
      const text = `${article.title}\n\n${article.body}`
      setKind('PUBLIC_REPLY')
      setDraft((current) => (current.trim() ? `${current.trimEnd()}\n\n${text}` : text))
    } catch (e) {
      toast.error(problemMessage(e))
    }
  }

  const back = (
    <Link to="/app/helpdesk/tickets" className="text-sm underline-offset-4 hover:underline">
      ← Tickets
    </Link>
  )
  if (ticket.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (ticket.isError)
    return (
      <>
        {back}
        <ErrorState error={ticket.error} onRetry={() => void ticket.refetch()} />
      </>
    )
  const t = ticket.data
  const closed = t.status === 'CLOSED'
  const canReply = can(PERMISSIONS.ticketManage) && !closed

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={`${t.number} · ${t.subject}`}
        description={`${t.requester?.name ?? 'Requester'} · ${CHANNEL_LABELS[t.channel]}`}
        actions={
          <>
            <Badge variant={t.priority === 'URGENT' ? 'destructive' : 'outline'}>
              {PRIORITY_LABELS[t.priority]}
            </Badge>
            <Badge variant="secondary">{TICKET_STATUS_LABELS[t.status]}</Badge>
          </>
        }
      />
      <TicketActions ticket={t} />
      {closed && (
        <p className="text-sm text-muted-foreground">
          This ticket is closed. Its history stays here.
        </p>
      )}
      <div className="grid gap-6 lg:grid-cols-[2fr_1fr]">
        <div className="space-y-6">
          <DetailsCard ticket={t} />
          <Card role="region" aria-label="Conversation">
            <CardHeader>
              <CardTitle>Conversation</CardTitle>
            </CardHeader>
            <CardContent className="space-y-4">
              {messages.isPending ? (
                <ListSkeleton />
              ) : messages.isError ? (
                <ErrorState error={messages.error} onRetry={() => void messages.refetch()} />
              ) : (
                <TicketConversation messages={messages.data} />
              )}
              {canReply && (
                <MessageComposer
                  ticket={t}
                  kind={kind}
                  onKind={setKind}
                  body={draft}
                  onBody={setDraft}
                />
              )}
            </CardContent>
          </Card>
        </div>
        <div className="space-y-6">
          <SlaCard ticket={t} />
          <TicketContextPanel
            ticket={t}
            onInsertArticle={canReply ? (a) => void insertArticle(a) : null}
          />
        </div>
      </div>
      <SubjectTasksPanel
        subjectType="TICKET"
        subjectId={t.id}
        label={`${t.number} · ${t.subject}`}
        archived={closed}
      />
      <ActivityPanel subjectType="TICKET" subjectId={t.id} archived={closed} />
      <DocumentsPanel subjectType="TICKET" subjectId={t.id} archived={closed} />
    </div>
  )
}
