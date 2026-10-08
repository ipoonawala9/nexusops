import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { OpportunityView, StageView } from '@/lib/api/types'
import { formatDate, formatDateTime, formatMoney } from '@/lib/format'
import { OPPORTUNITY_STATUS_LABELS } from './labels'
import { MoveStageControl } from './MoveStageControl'
import { OpportunityFormDialog } from './OpportunityFormDialog'

export function OpportunityDetailPage() {
  const { opportunityId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const opportunity = useQuery({
    queryKey: ['opportunity', opportunityId],
    queryFn: () => api.get<OpportunityView>(`/opportunities/${opportunityId}`),
  })
  const canManage = can(PERMISSIONS.opportunityManage)
  const stages = useQuery({
    queryKey: ['crm-stages'],
    queryFn: () => api.get<StageView[]>('/crm/pipeline/stages'),
    enabled: canManage,
  })
  const back = (
    <Link to="/app/crm/pipeline" className="text-sm underline-offset-4 hover:underline">
      ← Pipeline
    </Link>
  )
  if (opportunity.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (opportunity.isError)
    return (
      <>
        {back}
        <ErrorState error={opportunity.error} onRetry={() => void opportunity.refetch()} />
      </>
    )
  const o = opportunity.data
  const accountPath = (id: string) =>
    can(PERMISSIONS.customerRead) ? `/app/crm/customers/${id}` : `/app/directory/${id}`
  function stored(updated: OpportunityView) {
    queryClient.setQueryData(['opportunity', updated.id], updated)
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={o.name}
        description={`${o.stage.name} · ${o.stage.probability}%`}
        actions={
          <>
            <Badge
              variant={
                o.status === 'WON' ? 'default' : o.status === 'LOST' ? 'outline' : 'secondary'
              }
            >
              {OPPORTUNITY_STATUS_LABELS[o.status]}
            </Badge>
            {canManage && stages.data && (
              <MoveStageControl opportunity={o} stages={stages.data} onMoved={stored} />
            )}
            {canManage && (
              <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                Edit
              </Button>
            )}
          </>
        }
      />
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {o.description && <p className="whitespace-pre-wrap">{o.description}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Account</dt>
            <dd>
              {o.account ? (
                <Link to={accountPath(o.account.id)} className="underline-offset-4 hover:underline">
                  {o.account.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Contact</dt>
            <dd>
              {o.contact ? (
                <Link
                  to={`/app/directory/${o.contact.id}`}
                  className="underline-offset-4 hover:underline"
                >
                  {o.contact.name}
                </Link>
              ) : (
                '—'
              )}
            </dd>
            <dt className="text-muted-foreground">Amount</dt>
            <dd>{o.amount != null && o.currency ? formatMoney(o.amount, o.currency) : '—'}</dd>
            <dt className="text-muted-foreground">Expected close</dt>
            <dd>{formatDate(o.expectedCloseOn)}</dd>
            <dt className="text-muted-foreground">Owner</dt>
            <dd>{o.owner?.name ?? 'Unassigned'}</dd>
            {o.closedAt && (
              <>
                <dt className="text-muted-foreground">Closed</dt>
                <dd>{formatDateTime(o.closedAt)}</dd>
              </>
            )}
            {o.lostReason && (
              <>
                <dt className="text-muted-foreground">Lost because</dt>
                <dd>{o.lostReason}</dd>
              </>
            )}
            {o.leadId && (
              <>
                <dt className="text-muted-foreground">Origin</dt>
                <dd>
                  <Link
                    to={`/app/crm/leads/${o.leadId}`}
                    className="underline-offset-4 hover:underline"
                  >
                    Source lead
                  </Link>
                </dd>
              </>
            )}
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel
        subjectType="OPPORTUNITY"
        subjectId={o.id}
        label={o.name}
        archived={false}
      />
      <ActivityPanel subjectType="OPPORTUNITY" subjectId={o.id} archived={false} />
      <DocumentsPanel subjectType="OPPORTUNITY" subjectId={o.id} archived={false} />
      {editing && (
        <OpportunityFormDialog
          opportunity={o}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            stored(saved)
            setEditing(false)
          }}
        />
      )}
    </div>
  )
}
