import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
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
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PlatformTenant } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { PLATFORM_SUSPEND, usePlatformSession } from './platformSession'
import { StatusChangeDialog } from './StatusChangeDialog'

const SIZE = 20

export function PlatformTenantsPage() {
  const api = useApi()
  const session = usePlatformSession()
  const [params, setParams] = useSearchParams()
  const [change, setChange] = useState<{
    tenant: PlatformTenant
    action: 'suspend' | 'reactivate'
  } | null>(null)
  const canSuspend =
    session.state.status === 'authenticated' &&
    session.state.profile.permissions.includes(PLATFORM_SUSPEND)
  const q = params.get('q') ?? ''
  const status = params.get('status') ?? ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)

  const tenants = useQuery({
    queryKey: ['platform-tenants', { q, status, page }],
    queryFn: () =>
      api.get<Page<PlatformTenant>>(
        `/platform/tenants?${toQuery({ q, status, page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
  })

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const next = new URLSearchParams()
    for (const key of ['q', 'status']) {
      const value = data.get(key)
      if (typeof value === 'string' && value.trim()) next.set(key, value.trim())
    }
    setParams(next, { replace: true })
  }

  return (
    <>
      <PageHeader title="Workspaces" description="Every workspace on NexusOps." />
      <form onSubmit={onSearch} role="search" className="mb-4 flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="tenants-q">Search workspaces</Label>
          <Input id="tenants-q" name="q" defaultValue={q} placeholder="Name or URL" />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="tenants-status">Status</Label>
          <NativeSelect id="tenants-status" name="status" defaultValue={status}>
            <option value="">All statuses</option>
            <option value="ACTIVE">Active</option>
            <option value="SUSPENDED">Suspended</option>
            <option value="PENDING_VERIFICATION">Pending verification</option>
          </NativeSelect>
        </div>
        <Button type="submit" variant="outline">
          Search
        </Button>
      </form>

      {tenants.isPending ? (
        <ListSkeleton />
      ) : tenants.isError ? (
        <ErrorState error={tenants.error} onRetry={() => void tenants.refetch()} />
      ) : tenants.data.items.length === 0 ? (
        <EmptyState title="No workspaces match." description="Try a different search or status." />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Workspace</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>Plan</TableHead>
                  <TableHead>Active users</TableHead>
                  <TableHead>Owners</TableHead>
                  <TableHead>Created</TableHead>
                  {canSuspend && (
                    <TableHead>
                      <span className="sr-only">Actions</span>
                    </TableHead>
                  )}
                </TableRow>
              </TableHeader>
              <TableBody>
                {tenants.data.items.map((tenant) => (
                  <TableRow key={tenant.id}>
                    <TableCell>
                      <div className="font-medium">{tenant.name}</div>
                      <div className="text-xs text-muted-foreground">{tenant.slug}</div>
                    </TableCell>
                    <TableCell>
                      <StatusBadge status={tenant.status} />
                    </TableCell>
                    <TableCell>{tenant.planCode}</TableCell>
                    <TableCell>{tenant.activeUsers}</TableCell>
                    <TableCell>
                      {tenant.ownerEmails.map((email) => (
                        <div key={email}>{email}</div>
                      ))}
                    </TableCell>
                    <TableCell className="whitespace-nowrap">
                      {formatDateTime(tenant.createdAt)}
                    </TableCell>
                    {canSuspend && (
                      <TableCell className="text-right">
                        {tenant.status === 'ACTIVE' && (
                          <Button
                            variant="destructive"
                            size="sm"
                            aria-label={`Suspend ${tenant.name}`}
                            onClick={() => setChange({ tenant, action: 'suspend' })}
                          >
                            Suspend
                          </Button>
                        )}
                        {tenant.status === 'SUSPENDED' && (
                          <Button
                            variant="outline"
                            size="sm"
                            aria-label={`Reactivate ${tenant.name}`}
                            onClick={() => setChange({ tenant, action: 'reactivate' })}
                          >
                            Reactivate
                          </Button>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={tenants.data.page}
            size={tenants.data.size}
            total={tenants.data.total}
            onPage={(p) => {
              const next = new URLSearchParams(params)
              next.set('page', String(p))
              setParams(next, { replace: true })
            }}
          />
        </>
      )}
      {change && (
        <StatusChangeDialog
          key={change.tenant.id}
          tenant={change.tenant}
          action={change.action}
          onClose={() => setChange(null)}
        />
      )}
    </>
  )
}
