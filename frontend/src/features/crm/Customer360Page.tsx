import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type {
  CustomerSummary,
  LeadView,
  OpportunityView,
  Page,
  PartySummary,
} from '@/lib/api/types'
import { formatDate, formatMoney } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { LEAD_STATUS_LABELS, OPPORTUNITY_STATUS_LABELS } from './labels'
import { formatTotals } from './money'
import { OpportunityFormDialog } from './OpportunityFormDialog'

/** D8: one page joining identity, deals, converted leads and the whole timeline (including deals' and leads'). */
export function Customer360Page() {
  const { partyId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const [creating, setCreating] = useState(false)
  const summary = useQuery({
    queryKey: ['crm-customer', partyId],
    queryFn: () => api.get<CustomerSummary>(`/crm/customers/${partyId}`),
  })
  const isOrganization = summary.data?.party.kind === 'ORGANIZATION'
  const contacts = useQuery({
    queryKey: ['parties', { organizationId: partyId }],
    queryFn: () =>
      api.get<Page<PartySummary>>(`/parties?${toQuery({ organizationId: partyId, size: 50 })}`),
    enabled: isOrganization,
  })
  const deals = useQuery({
    queryKey: ['opportunities', { accountId: partyId }],
    queryFn: () =>
      api.get<Page<OpportunityView>>(`/opportunities?${toQuery({ accountId: partyId, size: 50 })}`),
    enabled: can(PERMISSIONS.opportunityRead),
  })
  const leads = useQuery({
    queryKey: ['leads', { partyId }],
    queryFn: () =>
      api.get<Page<LeadView>>(`/leads?${toQuery({ partyId, status: 'CONVERTED', size: 20 })}`),
    enabled: can(PERMISSIONS.leadRead),
  })
  const back = (
    <Link to="/app/crm/customers" className="text-sm underline-offset-4 hover:underline">
      ← Customers
    </Link>
  )
  if (summary.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (summary.isError)
    return (
      <>
        {back}
        <ErrorState error={summary.error} onRetry={() => void summary.refetch()} />
      </>
    )
  const s = summary.data
  const party = s.party
  const archived = party.archivedAt !== null
  const stats: Array<[string, string, string]> = [
    ['Open deals', String(s.openCount), formatTotals(s.openValue)],
    ['Weighted pipeline', '', formatTotals(s.weightedValue)],
    ['Won', String(s.wonCount), formatTotals(s.wonValue)],
    ['Lost', String(s.lostCount), ''],
    ['Leads converted', String(s.leadCount), ''],
  ]

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={party.name}
        description={[
          party.kind === 'ORGANIZATION' ? 'Organization' : 'Person',
          party.email ?? party.domain,
        ]
          .filter(Boolean)
          .join(' · ')}
        actions={
          <>
            <Link to={`/app/directory/${party.id}`} className="text-sm underline">
              Open in directory
            </Link>
            {can(PERMISSIONS.opportunityManage) && !archived && (
              <Button size="sm" onClick={() => setCreating(true)}>
                New opportunity
              </Button>
            )}
          </>
        }
      />
      <div className="grid gap-3 sm:grid-cols-5">
        {stats.map(([label, count, money]) => (
          <div key={label} className="rounded-lg border p-3">
            <p className="text-xs text-muted-foreground">{label}</p>
            {count && <p className="text-2xl font-semibold">{count}</p>}
            {money && <p className="text-sm">{money}</p>}
          </div>
        ))}
      </div>
      {isOrganization && (
        <Card>
          <CardHeader>
            <CardTitle>People</CardTitle>
          </CardHeader>
          <CardContent className="text-sm">
            {contacts.data?.items.length ? (
              <ul className="space-y-1">
                {contacts.data.items.map((p) => (
                  <li key={p.id}>
                    <Link
                      to={`/app/directory/${p.id}`}
                      className="underline-offset-4 hover:underline"
                    >
                      {p.name}
                    </Link>
                    {p.email && <span className="text-muted-foreground"> · {p.email}</span>}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground">No people linked yet.</p>
            )}
          </CardContent>
        </Card>
      )}
      {can(PERMISSIONS.opportunityRead) && (
        <section aria-label="Opportunities" className="space-y-2">
          <h2 className="text-lg font-semibold">Opportunities</h2>
          {deals.data?.items.length ? (
            <div className="overflow-x-auto rounded-lg border">
              <Table>
                <TableHeader>
                  <TableRow>
                    <TableHead>Name</TableHead>
                    <TableHead>Stage</TableHead>
                    <TableHead>Status</TableHead>
                    <TableHead>Amount</TableHead>
                    <TableHead>Expected close</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {deals.data.items.map((o) => (
                    <TableRow key={o.id}>
                      <TableCell>
                        <Link
                          to={`/app/crm/opportunities/${o.id}`}
                          className="underline-offset-4 hover:underline"
                        >
                          {o.name}
                        </Link>
                      </TableCell>
                      <TableCell>{o.stage.name}</TableCell>
                      <TableCell>
                        <Badge variant="outline">{OPPORTUNITY_STATUS_LABELS[o.status]}</Badge>
                      </TableCell>
                      <TableCell>
                        {o.amount != null && o.currency ? formatMoney(o.amount, o.currency) : '—'}
                      </TableCell>
                      <TableCell>{formatDate(o.expectedCloseOn)}</TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">No opportunities yet.</p>
          )}
        </section>
      )}
      {can(PERMISSIONS.leadRead) && leads.data && leads.data.items.length > 0 && (
        <section aria-label="Converted leads" className="space-y-2">
          <h2 className="text-lg font-semibold">Converted leads</h2>
          <ul className="space-y-1 text-sm">
            {leads.data.items.map((l) => (
              <li key={l.id}>
                <Link to={`/app/crm/leads/${l.id}`} className="underline-offset-4 hover:underline">
                  {l.name}
                </Link>{' '}
                <span className="text-muted-foreground">{LEAD_STATUS_LABELS[l.status]}</span>
              </li>
            ))}
          </ul>
        </section>
      )}
      <SubjectTasksPanel
        subjectType="PARTY"
        subjectId={party.id}
        label={party.name}
        archived={archived}
      />
      <ActivityPanel subjectType="PARTY" subjectId={party.id} archived={archived} includeRelated />
      <DocumentsPanel subjectType="PARTY" subjectId={party.id} archived={archived} />
      {creating && (
        <OpportunityFormDialog
          account={{ id: party.id, name: party.name }}
          onClose={() => setCreating(false)}
          onSaved={() => setCreating(false)}
        />
      )}
    </div>
  )
}
