import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { RoleView } from '@/lib/api/types'
import { CreateRoleDialog } from './CreateRoleDialog'

export function RolesPage() {
  const api = useApi()
  const canManage = useCan()(PERMISSIONS.roleManage)
  const [creating, setCreating] = useState(false)
  const roles = useQuery({
    queryKey: ['roles'],
    queryFn: () => api.get<RoleView[]>('/roles'),
    refetchOnMount: 'always',
  })

  return (
    <>
      <PageHeader
        title="Roles"
        description="Bundles of permissions you assign to people."
        actions={
          canManage ? <Button onClick={() => setCreating(true)}>New role</Button> : undefined
        }
      />
      {roles.isPending ? (
        <ListSkeleton />
      ) : roles.isError ? (
        <ErrorState error={roles.error} onRetry={() => void roles.refetch()} />
      ) : roles.data.length === 0 ? (
        <EmptyState
          title="No roles yet."
          description="Create a role to describe what a job can do."
        />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Role</TableHead>
                <TableHead>Description</TableHead>
                <TableHead>Type</TableHead>
                <TableHead>Permissions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {roles.data.map((role) => (
                <TableRow key={role.id}>
                  <TableCell>
                    <Link
                      to={`/app/settings/roles/${role.id}`}
                      className="font-medium underline-offset-4 hover:underline"
                    >
                      {role.name}
                    </Link>
                  </TableCell>
                  <TableCell className="text-muted-foreground">{role.description ?? '—'}</TableCell>
                  <TableCell>
                    <Badge variant={role.system ? 'secondary' : 'outline'}>
                      {role.system ? 'System' : 'Custom'}
                    </Badge>
                  </TableCell>
                  <TableCell>{role.permissions.length}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {creating && <CreateRoleDialog onClose={() => setCreating(false)} />}
    </>
  )
}
