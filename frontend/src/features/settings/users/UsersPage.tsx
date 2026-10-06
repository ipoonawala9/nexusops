import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
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
import { StatusBadge } from '@/components/StatusBadge'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, UserView } from '@/lib/api/types'
import { formatDateTime, fullName } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { InvitationsPanel } from './InvitationsPanel'
import { UserDialog } from './UserDialog'

const SIZE = 20

export function UsersPage() {
  const api = useApi()
  const can = useCan()
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [selected, setSelected] = useState<UserView | null>(null)
  const canManage = can(PERMISSIONS.userUpdate, PERMISSIONS.userDisable, PERMISSIONS.roleAssign)

  const users = useQuery({
    queryKey: ['users', { q, status, page }],
    queryFn: () => api.get<Page<UserView>>(`/users?${toQuery({ q, status, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
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

  return (
    <>
      <PageHeader title="Users" description="People in this workspace and what they can do." />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="users-q">Search people</Label>
            <Input id="users-q" name="q" defaultValue={q} placeholder="Name or email" />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        <div className="space-y-1.5">
          <Label htmlFor="users-status">Status</Label>
          <NativeSelect
            id="users-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="INVITED">Invited</option>
            <option value="DISABLED">Disabled</option>
          </NativeSelect>
        </div>
      </div>

      {users.isPending ? (
        <ListSkeleton />
      ) : users.isError ? (
        <ErrorState error={users.error} onRetry={() => void users.refetch()} />
      ) : users.data.items.length === 0 ? (
        <EmptyState
          title="No people match these filters."
          description="Try a different search or status."
        />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Person</TableHead>
                  <TableHead>Roles</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Last sign-in</TableHead>
                  {canManage && (
                    <TableHead>
                      <span className="sr-only">Actions</span>
                    </TableHead>
                  )}
                </TableRow>
              </TableHeader>
              <TableBody>
                {users.data.items.map((person) => (
                  <TableRow key={person.id}>
                    <TableCell>
                      <div className="font-medium">{fullName(person)}</div>
                      <div className="text-xs text-muted-foreground">{person.email}</div>
                    </TableCell>
                    <TableCell>
                      <div className="flex flex-wrap gap-1">
                        {person.roles.map((role) => (
                          <Badge key={role.id} variant="outline">
                            {role.name}
                          </Badge>
                        ))}
                      </div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={person.status} />
                    </TableCell>
                    <TableCell>{formatDateTime(person.lastLoginAt)}</TableCell>
                    {canManage && (
                      <TableCell className="text-right">
                        <Button
                          variant="outline"
                          size="sm"
                          aria-label={`Manage ${fullName(person)}`}
                          onClick={() => setSelected(person)}
                        >
                          Manage
                        </Button>
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={users.data.page}
            size={users.data.size}
            total={users.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}

      <InvitationsPanel />

      {selected && (
        <UserDialog key={selected.id} person={selected} onClose={() => setSelected(null)} />
      )}
    </>
  )
}
