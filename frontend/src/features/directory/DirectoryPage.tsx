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
import type { Page, PartySummary, PartyView } from '@/lib/api/types'
import { toQuery } from '@/lib/query'
import { KIND_LABELS, ROLE_LABELS } from './labels'
import { OrganizationFormDialog } from './OrganizationFormDialog'
import { PersonFormDialog } from './PersonFormDialog'

const SIZE = 20

export function DirectoryPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const kind = params.get('kind') ?? ''
  const role = params.get('role') ?? ''
  const archived = params.get('status') === 'archived'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState<'PERSON' | 'ORGANIZATION' | null>(null)
  const canManage = can(PERMISSIONS.partyManage)

  const parties = useQuery({
    queryKey: ['parties', { q, kind, role, archived, page }],
    queryFn: () =>
      api.get<Page<PartySummary>>(
        `/parties?${toQuery({ q, kind, role, archived: archived ? 'true' : '', page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
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

  function opened(saved: PartyView) {
    setCreating(null)
    navigate(`/app/directory/${saved.id}`)
  }

  return (
    <>
      <PageHeader
        title="Directory"
        description="People and organizations your business works with: one record each."
        actions={
          canManage && (
            <>
              <Button variant="outline" onClick={() => setCreating('ORGANIZATION')}>
                New organization
              </Button>
              <Button onClick={() => setCreating('PERSON')}>New person</Button>
            </>
          )
        }
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="directory-q">Search directory</Label>
            <Input id="directory-q" name="q" defaultValue={q} placeholder="Name, email or domain" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="directory-kind">Show</Label>
          <NativeSelect
            id="directory-kind"
            value={kind}
            onChange={(e) => update({ kind: e.target.value, page: '' })}
          >
            <option value="">Everyone</option>
            <option value="PERSON">People</option>
            <option value="ORGANIZATION">Organizations</option>
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="directory-role">Role</Label>
          <NativeSelect
            id="directory-role"
            value={role}
            onChange={(e) => update({ role: e.target.value, page: '' })}
          >
            <option value="">Any role</option>
            <option value="CUSTOMER">Customers</option>
            <option value="SUPPLIER">Suppliers</option>
            {can(PERMISSIONS.employeeRead) && <option value="EMPLOYEE">Employees</option>}
          </NativeSelect>
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="directory-status">Status</Label>
          <NativeSelect
            id="directory-status"
            value={archived ? 'archived' : ''}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Active</option>
            <option value="archived">Archived</option>
          </NativeSelect>
        </div>
      </div>

      {parties.isPending ? (
        <ListSkeleton />
      ) : parties.isError ? (
        <ErrorState error={parties.error} onRetry={() => void parties.refetch()} />
      ) : parties.data.items.length === 0 ? (
        <EmptyState
          title={
            q || kind || role || archived ? 'No records match these filters.' : 'Nothing here yet.'
          }
          description={
            canManage
              ? 'Add the people and organizations you work with.'
              : 'Try a different search or filter.'
          }
          action={
            page > 0 && (
              <Button variant="outline" onClick={() => update({ page: '' })}>
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
                  <TableHead>Name</TableHead>
                  <TableHead>Contact</TableHead>
                  <TableHead>Roles</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {parties.data.items.map((party) => (
                  <TableRow key={party.id}>
                    <TableCell>
                      <Link
                        to={`/app/directory/${party.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {party.name}
                      </Link>
                      <div className="text-xs text-muted-foreground">
                        {[KIND_LABELS[party.kind], party.organization?.name]
                          .filter(Boolean)
                          .join(' · ')}
                        {party.archived && ' · Archived'}
                      </div>
                    </TableCell>
                    <TableCell className="text-sm">
                      <div>{party.email ?? party.domain ?? '—'}</div>
                      {party.phone && (
                        <div className="text-xs text-muted-foreground">{party.phone}</div>
                      )}
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {party.roles.map((r) => (
                          <Badge key={r} variant="outline">
                            {ROLE_LABELS[r]}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={parties.data.page}
            size={parties.data.size}
            total={parties.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}

      {creating === 'PERSON' && (
        <PersonFormDialog onClose={() => setCreating(null)} onSaved={opened} />
      )}
      {creating === 'ORGANIZATION' && (
        <OrganizationFormDialog onClose={() => setCreating(null)} onSaved={opened} />
      )}
    </>
  )
}
