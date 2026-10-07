import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { ArchiveControls } from '@/features/records/ArchiveControls'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PartySummary, PartyView } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { KIND_LABELS } from './labels'
import { OrganizationFormDialog } from './OrganizationFormDialog'
import { PartyRolesCard } from './PartyRolesCard'
import { PersonFormDialog } from './PersonFormDialog'

export function PartyDetailPage() {
  const { partyId = '' } = useParams()
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState(false)
  const party = useQuery({
    queryKey: ['party', partyId],
    queryFn: () => api.get<PartyView>(`/parties/${partyId}`),
  })

  const back = (
    <Link to="/app/directory" className="text-sm underline-offset-4 hover:underline">
      ← Directory
    </Link>
  )
  if (party.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (party.isError)
    return (
      <>
        {back}
        <ErrorState error={party.error} onRetry={() => void party.refetch()} />
      </>
    )

  const p = party.data
  const archived = p.archivedAt !== null
  const canManage = can(PERMISSIONS.partyManage)

  function stored(updated: PartyView) {
    queryClient.setQueryData(['party', updated.id], updated)
    void queryClient.invalidateQueries({ queryKey: ['parties'] })
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={p.name}
        description={[KIND_LABELS[p.kind], p.jobTitle].filter(Boolean).join(' · ')}
        actions={
          canManage && (
            <>
              {!archived && (
                <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
                  Edit
                </Button>
              )}
              <ArchiveControls
                name={p.name}
                archived={archived}
                onArchive={async () => stored(await api.post<PartyView>(`/parties/${p.id}/archive`))}
                onRestore={async () => stored(await api.post<PartyView>(`/parties/${p.id}/restore`))}
              />
            </>
          )
        }
      />
      {archived && (
        <p role="status" className="rounded-md border bg-muted/40 px-3 py-2 text-sm">
          This record is archived. Restore it to make changes or add activity, tasks and files.
        </p>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <DetailsCard party={p} />
        <PartyRolesCard party={p} onChange={stored} />
      </div>
      {p.kind === 'ORGANIZATION' && <OrganizationPeople organizationId={p.id} />}
      {/* record panels */}
      <SubjectTasksPanel subjectType="PARTY" subjectId={p.id} label={p.name} archived={archived} />
      <ActivityPanel subjectType="PARTY" subjectId={p.id} archived={archived} />
      <DocumentsPanel subjectType="PARTY" subjectId={p.id} archived={archived} />
      {editing &&
        (p.kind === 'PERSON' ? (
          <PersonFormDialog
            person={p}
            onClose={() => setEditing(false)}
            onSaved={(saved) => {
              stored(saved)
              setEditing(false)
            }}
          />
        ) : (
          <OrganizationFormDialog
            organization={p}
            onClose={() => setEditing(false)}
            onSaved={(saved) => {
              stored(saved)
              setEditing(false)
            }}
          />
        ))}
    </div>
  )
}

function DetailsCard({ party }: { party: PartyView }) {
  const rows: Array<[string, ReactNode]> = []
  if (party.email)
    rows.push([
      'Email',
      <a key="email" href={`mailto:${party.email}`} className="underline-offset-4 hover:underline">
        {party.email}
      </a>,
    ])
  if (party.phone) rows.push(['Phone', party.phone])
  if (party.organization)
    rows.push([
      'Organization',
      <Link key="org" to={`/app/directory/${party.organization.id}`} className="underline-offset-4 hover:underline">
        {party.organization.name}
      </Link>,
    ])
  if (party.domain) rows.push(['Domain', party.domain])
  if (party.website)
    rows.push([
      'Website',
      <a
        key="web"
        href={party.website}
        target="_blank"
        rel="noopener noreferrer"
        className="underline-offset-4 hover:underline"
      >
        {party.website}
      </a>,
    ])
  if (party.duplicateReason) rows.push(['Kept separate because', party.duplicateReason])
  return (
    <Card>
      <CardHeader>
        <CardTitle>Details</CardTitle>
      </CardHeader>
      <CardContent className="text-sm">
        {rows.length === 0 ? (
          <p className="text-muted-foreground">No contact details yet.</p>
        ) : (
          <dl className="grid grid-cols-[9rem_1fr] gap-x-3 gap-y-2">
            {rows.map(([label, value]) => (
              <div key={label} className="contents">
                <dt className="text-muted-foreground">{label}</dt>
                <dd className="break-words">{value}</dd>
              </div>
            ))}
          </dl>
        )}
      </CardContent>
    </Card>
  )
}

function OrganizationPeople({ organizationId }: { organizationId: string }) {
  const api = useApi()
  const people = useQuery({
    queryKey: ['parties', { organizationId }],
    queryFn: () => api.get<Page<PartySummary>>(`/parties?${toQuery({ organizationId, size: 100 })}`),
  })
  return (
    <Card role="region" aria-label="People">
      <CardHeader>
        <CardTitle>People</CardTitle>
      </CardHeader>
      <CardContent className="text-sm">
        {people.isPending ? (
          <ListSkeleton rows={2} />
        ) : people.isError ? (
          <ErrorState error={people.error} onRetry={() => void people.refetch()} />
        ) : people.data.items.length === 0 ? (
          <p className="text-muted-foreground">No people linked yet.</p>
        ) : (
          <ul className="space-y-1">
            {people.data.items.map((person) => (
              <li key={person.id}>
                <Link to={`/app/directory/${person.id}`} className="underline-offset-4 hover:underline">
                  {person.name}
                </Link>
                {person.email && <span className="text-muted-foreground"> · {person.email}</span>}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
