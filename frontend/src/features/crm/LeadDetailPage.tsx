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
import type { LeadView } from '@/lib/api/types'
import { formatDateTime, formatMoney } from '@/lib/format'
import { ConvertLeadDialog } from './ConvertLeadDialog'
import { LEAD_SOURCE_LABELS, LEAD_STATUS_LABELS } from './labels'
import { LeadFormDialog } from './LeadFormDialog'
import { LeadStatusActions } from './LeadStatusActions'

export function LeadDetailPage() {
  const { leadId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const [converting, setConverting] = useState(false)
  const lead = useQuery({
    queryKey: ['lead', leadId],
    queryFn: () => api.get<LeadView>(`/leads/${leadId}`),
  })
  const back = (
    <Link to="/app/crm/leads" className="text-sm underline-offset-4 hover:underline">
      ← Leads
    </Link>
  )
  if (lead.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (lead.isError)
    return (
      <>
        {back}
        <ErrorState error={lead.error} onRetry={() => void lead.refetch()} />
      </>
    )

  const l = lead.data
  const converted = l.status === 'CONVERTED'
  const canManage = can(PERMISSIONS.leadManage) && !converted
  function stored(updated: LeadView) {
    queryClient.setQueryData(['lead', updated.id], updated)
    void queryClient.invalidateQueries({ queryKey: ['leads'] })
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={l.name}
        description={[l.companyName !== l.name ? l.companyName : null, LEAD_SOURCE_LABELS[l.source]]
          .filter(Boolean)
          .join(' · ')}
        actions={
          canManage && (
            <>
              <Badge variant="secondary">{LEAD_STATUS_LABELS[l.status]}</Badge>
              <LeadStatusActions lead={l} onChanged={stored} />
              <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                Edit
              </Button>
              {l.status !== 'DISQUALIFIED' && (
                <Button size="sm" onClick={() => setConverting(true)}>
                  Convert
                </Button>
              )}
            </>
          )
        }
      />
      {!canManage && <Badge variant="secondary">{LEAD_STATUS_LABELS[l.status]}</Badge>}
      {l.status === 'DISQUALIFIED' && (
        <p role="status" className="rounded-md border bg-muted/40 px-3 py-2 text-sm">
          Disqualified: {l.disqualifyReason}
        </p>
      )}
      {converted && (
        <div role="status" className="space-y-1 rounded-md border bg-muted/40 px-3 py-2 text-sm">
          <p>
            This lead was converted on {formatDateTime(l.convertedAt)}. It can no longer be changed.
          </p>
          <p className="flex flex-wrap gap-3">
            {l.convertedOrganization && (
              <Link to={`/app/directory/${l.convertedOrganization.id}`} className="underline">
                {l.convertedOrganization.name}
              </Link>
            )}
            {l.convertedPerson && (
              <Link to={`/app/directory/${l.convertedPerson.id}`} className="underline">
                {l.convertedPerson.name}
              </Link>
            )}
            {l.convertedOpportunityId && (
              <Link to={`/app/crm/opportunities/${l.convertedOpportunityId}`} className="underline">
                Open the opportunity
              </Link>
            )}
          </p>
        </div>
      )}
      <Card>
        <CardHeader>
          <CardTitle>Details</CardTitle>
        </CardHeader>
        <CardContent className="space-y-2 text-sm">
          {l.description && <p className="whitespace-pre-wrap">{l.description}</p>}
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            <dt className="text-muted-foreground">Email</dt>
            <dd>{l.email ?? '—'}</dd>
            <dt className="text-muted-foreground">Phone</dt>
            <dd>{l.phone ?? '—'}</dd>
            <dt className="text-muted-foreground">Job title</dt>
            <dd>{l.jobTitle ?? '—'}</dd>
            <dt className="text-muted-foreground">Owner</dt>
            <dd>{l.owner?.name ?? 'Unassigned'}</dd>
            <dt className="text-muted-foreground">Estimated value</dt>
            <dd>
              {l.estimatedValue != null && l.currency
                ? formatMoney(l.estimatedValue, l.currency)
                : '—'}
            </dd>
          </dl>
        </CardContent>
      </Card>
      <SubjectTasksPanel subjectType="LEAD" subjectId={l.id} label={l.name} archived={converted} />
      <ActivityPanel subjectType="LEAD" subjectId={l.id} archived={converted} />
      <DocumentsPanel subjectType="LEAD" subjectId={l.id} archived={converted} />
      {editing && (
        <LeadFormDialog
          lead={l}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            stored(saved)
            setEditing(false)
          }}
        />
      )}
      {converting && (
        <ConvertLeadDialog
          lead={l}
          onClose={() => setConverting(false)}
          onConverted={(c) => {
            stored(c)
            setConverting(false)
          }}
        />
      )}
    </div>
  )
}
